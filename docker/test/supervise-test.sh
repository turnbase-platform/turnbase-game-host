#!/bin/bash
# Runs the real docker/supervise.sh (under `sh`, as in the image) against a fake `java` and a stub
# HTTP server for the liveness URL. No cluster, no JVM. IMP-M16-02.
#
#   bash docker/test/supervise-test.sh
set -u

here="$(cd "$(dirname "$0")" && pwd)"
supervise="$here/../supervise.sh"
work="$(mktemp -d)"
port="${STUB_PORT:-18081}"
fails=0
sup=""

# The fake JVMs are `exec sleep`; kill every pid they recorded.
reap_fakes() {
  [ -f "$work/starts" ] && xargs -r kill -KILL <"$work/starts" 2>/dev/null
  return 0
}

cleanup() {
  [ -n "$sup" ] && kill -KILL "$sup" 2>/dev/null
  reap_fakes
  [ -n "${stub:-}" ] && kill -KILL "$stub" 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT

mkdir -p "$work/fakebin"
# The fake JVM: records its pid, optionally ignores TERM or exits at once, otherwise sleeps. `exec`
# keeps the pid and an ignored TERM stays ignored across it.
cat >"$work/fakebin/java" <<EOS
#!/bin/sh
echo "\$\$" >>"$work/starts"
[ -n "\${FAKE_LIFE:-}" ] && { sleep "\${FAKE_LIFE}"; exit 4; }
[ -n "\${FAKE_EXIT:-}" ] && exit "\${FAKE_EXIT}"
[ -n "\${FAKE_IGNORE_TERM:-}" ] && trap '' TERM
exec sleep 600
EOS
chmod +x "$work/fakebin/java"

# The stub: /actuator/health/liveness answers with the status in $work/liveness, /actuator/health
# with $work/health. A status of "drop" holds the connection open and never answers.
cat >"$work/stub.py" <<'EOS'
import http.server, socketserver, sys, time
work, port = sys.argv[1], int(sys.argv[2])
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name = "liveness" if self.path.endswith("/liveness") else "health"
        status = open(f"{work}/{name}").read().strip()
        if status == "drop":
            time.sleep(30)
            return
        self.send_response(int(status)); self.send_header("Content-Length", "0"); self.end_headers()
    def log_message(self, *a): pass
class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True
S(("127.0.0.1", port), H).serve_forever()
EOS
python3 -I "$work/stub.py" "$work" "$port" &
stub=$!

set_status() { echo "$1" >"$work/liveness"; echo "${2:-200}" >"$work/health"; }
starts() { [ -f "$work/starts" ] && wc -l <"$work/starts" | tr -d ' ' || echo 0; }
last_pid() { tail -n 1 "$work/starts"; }
started() { [ "$(starts)" -ge "$1" ]; }
alive() { kill -0 "$1" 2>/dev/null; }

# wait_until <seconds> <command...>
wait_until() {
  local limit=$1 i
  shift
  for ((i = 0; i < limit * 10; i++)); do
    "$@" && return 0
    sleep 0.1
  done
  return 1
}

check() { # check <name> <command...>
  local name=$1
  shift
  if "$@"; then echo "ok   $name"; else echo "FAIL $name"; fails=$((fails + 1)); fi
}

start_supervisor() { # start_supervisor [VAR=value...]
  rm -f "$work/starts" "$work/marker" "$work/jvm.pid" "$work/log"
  (
    export PATH="$work/fakebin:$PATH" MANAGEMENT_SERVER_PORT="$port" \
      TURNBASE_SUPERVISOR_GRACE=1 TURNBASE_SUPERVISOR_FAILURES=2 \
      TURNBASE_SUPERVISOR_PIDFILE="$work/jvm.pid" TURNBASE_SUPERVISOR_MARKER="$work/marker"
    for kv in "$@"; do export "${kv?}"; done
    cd "$work" && exec sh "$supervise"
  ) >"$work/log" 2>&1 &
  sup=$!
  wait_until 5 started 1
}

stop_supervisor() {
  if [ -n "$sup" ] && alive "$sup"; then
    { kill -KILL "$sup" && wait "$sup"; } 2>/dev/null
  fi
  reap_fakes
  sup=""
  sleep 0.3
}

# --- mode A: silence ---------------------------------------------------------------------------
echo "== a silent child is killed after FAILURES probes and respawned"
set_status drop
start_supervisor
first=$(last_pid)
check "pid file holds the JVM's pid" test "$(cat "$work/jvm.pid")" = "$first"
check "the silent JVM is killed" wait_until 20 test ! -d "/proc/$first"
check "a new JVM is started" wait_until 5 started 2
check "the supervisor is still running" alive "$sup"
check "the pid file follows the new JVM" test "$(cat "$work/jvm.pid")" = "$(last_pid)"
check "every log line has a timestamp" bash -c "! grep -vE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]+Z supervisor: ' '$work/log'"
check "the exit status is logged" grep -q "exited with status" "$work/log"
stop_supervisor

echo "== a stopped (SIGSTOP) child is killed too"
set_status 200
start_supervisor
first=$(last_pid)
kill -STOP "$first"
set_status drop
check "the stopped JVM is killed" wait_until 20 test ! -d "/proc/$first"
check "a new JVM is started" wait_until 5 started 2
stop_supervisor

echo "== a 503 on the liveness URL counts as silent"
set_status 503 200
start_supervisor
first=$(last_pid)
check "the JVM is killed" wait_until 20 test ! -d "/proc/$first"
check "a new JVM is started" wait_until 5 started 2
stop_supervisor

echo "== a child that ignores TERM is still killed"
set_status drop
start_supervisor FAKE_IGNORE_TERM=1
first=$(last_pid)
check "the JVM is killed" wait_until 20 test ! -d "/proc/$first"
check "a new JVM is started" wait_until 5 started 2
stop_supervisor

echo "== overall health DOWN with liveness UP does not kill the child"
set_status 200 503
start_supervisor
first=$(last_pid)
sleep 9
check "the JVM is still the same one" alive "$first"
check "no second JVM was started" test "$(starts)" -eq 1
stop_supervisor

# --- mode B: the marker file -------------------------------------------------------------------
echo "== the marker file kills a live child within 3 s, even inside the grace"
set_status 200
start_supervisor TURNBASE_SUPERVISOR_GRACE=60
first=$(last_pid)
touch "$work/marker"
check "the JVM is killed within 3 s" wait_until 3 test ! -d "/proc/$first"
check "the marker is removed" wait_until 2 test ! -e "$work/marker"
check "a new JVM is started" wait_until 5 started 2
check "the supervisor is still running" alive "$sup"
stop_supervisor

# --- stop request ------------------------------------------------------------------------------
echo "== SIGTERM reaches the JVM and the script exits without respawning"
set_status 200
start_supervisor
first=$(last_pid)
kill -TERM "$sup"
check "the JVM is gone" wait_until 5 test ! -d "/proc/$first"
check "the supervisor exits" wait_until 5 test ! -d "/proc/$sup"
check "no JVM was respawned" test "$(starts)" -eq 1
sup=""
stop_supervisor

# --- crash loop --------------------------------------------------------------------------------
echo "== 5 deaths within the window make the script exit non-zero"
set_status 200
start_supervisor FAKE_EXIT=3
check "the supervisor exits" wait_until 10 test ! -d "/proc/$sup"
wait "$sup"
code=$?
check "its status is non-zero" test "$code" -ne 0
check "exactly 5 JVMs were started" test "$(starts)" -eq 5
sup=""
stop_supervisor

echo "== deaths outside the window do not count"
set_status 200
start_supervisor FAKE_LIFE=2 TURNBASE_SUPERVISOR_MAX_DEATHS=3 TURNBASE_SUPERVISOR_WINDOW=1
check "four JVMs come and go" wait_until 12 started 4
check "the supervisor keeps respawning" alive "$sup"
stop_supervisor

if [ "$fails" -eq 0 ]; then
  echo "PASS"
else
  echo "$fails check(s) failed"
  echo "--- last supervisor log ---"
  cat "$work/log" 2>/dev/null
  exit 1
fi
