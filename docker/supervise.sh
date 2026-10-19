#!/bin/sh
# Liveness supervisor for the Game Host (ADR-0018 §2).
#
# Roughly one cold boot in ten deadlocks the *whole* JVM inside Truffle's native interface (glibc
# 2.34: nfi-gc against the boot thread's dlsym): the VM Thread waits for a safepoint that never
# comes, so no Java thread runs, jcmd cannot attach, SIGTERM is ignored and the actuator does not
# answer. Nothing inside the JVM can notice that, which is why this is a separate process.
#
# This script is PID 1 and runs the JVM in a loop, so a wedge costs one JVM start and never a
# container restart (the kubelet's CrashLoopBackOff grows to five minutes). Two ways to learn that a
# JVM is wedged:
#   A. silence: after GRACE seconds, FAILURES consecutive probes of /actuator/health/liveness that
#      get no 2xx answer (a 503 is silent too). The liveness group excludes Redis and Postgres, so a
#      dependency outage never restarts the host.
#   B. the marker file: the in-JVM BootWatchdog (HostWedge) writes MARKER when a guest boot is stuck
#      in an otherwise live JVM. It is checked every second, grace or not.
# Either way the JVM is SIGKILLed (kill is a shell builtin; the image has no kill binary), the marker
# is removed and the next JVM starts at once. After MAX_DEATHS JVM deaths within WINDOW seconds the
# script exits non-zero and the container's restart policy takes over. SIGTERM/SIGINT go to the JVM
# and the script exits with its status without respawning.
set -u

PORT="${MANAGEMENT_SERVER_PORT:-8081}"
GRACE="${TURNBASE_SUPERVISOR_GRACE:-45}"
FAILURES="${TURNBASE_SUPERVISOR_FAILURES:-4}"
PIDFILE="${TURNBASE_SUPERVISOR_PIDFILE:-/tmp/jvm.pid}"
MARKER="${TURNBASE_SUPERVISOR_MARKER:-/tmp/turnbase-wedged}"
MAX_DEATHS="${TURNBASE_SUPERVISOR_MAX_DEATHS:-5}"
WINDOW="${TURNBASE_SUPERVISOR_WINDOW:-600}"
URL="http://127.0.0.1:${PORT}/actuator/health/liveness"

log() {
  printf '%s supervisor: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

# Sleeps 2 s in two halves so the marker is noticed within about a second.
nap() {
  sleep 1
  [ -e "$MARKER" ] && return 0
  sleep 1
}

# Runs in the background for one JVM ($1 is its pid).
monitor() {
  pid=$1
  grace_end=$(($(date +%s) + GRACE))
  silent=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ -e "$MARKER" ]; then
      log "the host reported a wedged guest boot (${MARKER}); killing JVM ${pid}"
      rm -f "$MARKER"
      kill -KILL "$pid"
      return 0
    fi
    if [ "$(date +%s)" -ge "$grace_end" ]; then
      if curl -sf -m 3 -o /dev/null "$URL"; then
        silent=0
      else
        silent=$((silent + 1))
        if [ "$silent" -ge "$FAILURES" ]; then
          log "JVM ${pid} has not answered ${silent} liveness probes in a row; killing it"
          kill -KILL "$pid"
          return 0
        fi
      fi
    fi
    nap
  done
}

term=0
child=0
trap 'term=1; kill -TERM "$child" 2>/dev/null' TERM INT

deaths=""
while :; do
  rm -f "$MARKER"
  java -jar app.jar &
  child=$!
  echo "$child" >"$PIDFILE"
  log "started JVM ${child}"
  monitor "$child" &
  monitor_pid=$!

  wait "$child" 2>/dev/null
  status=$?
  # A trapped signal ends the first wait early; the JVM may still be shutting down.
  while kill -0 "$child" 2>/dev/null; do
    wait "$child" 2>/dev/null
    status=$?
  done
  kill "$monitor_pid" 2>/dev/null
  wait "$monitor_pid" 2>/dev/null

  if [ "$term" -eq 1 ]; then
    log "JVM ${child} exited with status ${status} after a stop request"
    exit "$status"
  fi

  now=$(date +%s)
  recent=""
  count=0
  for t in $deaths; do
    if [ $((now - t)) -lt "$WINDOW" ]; then
      recent="$recent $t"
      count=$((count + 1))
    fi
  done
  deaths="$recent $now"
  count=$((count + 1))
  log "JVM ${child} exited with status ${status} (death ${count} of ${MAX_DEATHS} within ${WINDOW} s)"
  if [ "$count" -ge "$MAX_DEATHS" ]; then
    log "too many JVM deaths; exiting so the container restarts"
    exit 1
  fi
done
