#!/bin/sh
# Liveness supervisor for the Game Host (ADR-0018 §2).
#
# Roughly one cold boot in ten deadlocks the *whole* JVM inside Truffle's native interface (glibc
# 2.34: nfi-gc against the boot thread's dlsym): the VM Thread waits for a safepoint that never
# comes, so no Java thread runs, jcmd cannot attach, SIGTERM is ignored and /actuator/health does not
# answer. Nothing inside the JVM can notice that, which is why this is a separate process. Docker
# does not restart an unhealthy container, and a Kubernetes liveness probe would be the only other
# thing to do so; this makes both runtimes behave the same.
#
# Any HTTP answer counts as alive, including a 503 from a host that is still registering. After
# GRACE seconds, FAILURES consecutive silent probes SIGKILL the JVM and exit non-zero, and the
# restart policy does the rest.
set -u

PORT="${MANAGEMENT_SERVER_PORT:-8081}"
GRACE="${TURNBASE_SUPERVISOR_GRACE:-45}"
FAILURES="${TURNBASE_SUPERVISOR_FAILURES:-4}"

java -jar app.jar &
child=$!

(
  sleep "$GRACE"
  silent=0
  while kill -0 "$child" 2>/dev/null; do
    if curl -s -m 3 -o /dev/null "http://127.0.0.1:${PORT}/actuator/health"; then
      silent=0
    else
      silent=$((silent + 1))
      if [ "$silent" -ge "$FAILURES" ]; then
        echo "supervisor: the JVM has not answered ${silent} probes in a row; killing it" >&2
        kill -KILL "$child"
        exit 0
      fi
    fi
    sleep 2
  done
) &
monitor=$!

# docker stop sends SIGTERM to this script; pass it on and wait for the JVM to finish.
trap 'kill -TERM "$child" 2>/dev/null' TERM INT
wait "$child"
status=$?
# A trapped signal ends the first wait early; the JVM may still be shutting down.
while kill -0 "$child" 2>/dev/null; do
  wait "$child"
  status=$?
done
kill "$monitor" 2>/dev/null
exit "$status"
