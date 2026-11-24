#!/bin/sh
# Entry point of the web image (Sprint 33, ADR-0289).
#
# The Next.js server exits the moment it is told to stop, which would cut the requests it is answering and leave a load
# balancer sending to a server that is gone. So on a stop signal this script first creates the marker that makes
# /healthz answer 503 (the balancer then takes the instance out of rotation), keeps the server running for
# WEB_SHUTDOWN_DELAY seconds (default 10, at least the balancer's health-check interval times its unhealthy threshold),
# and only then stops the server.
set -eu

marker="${WEB_DRAINING_MARKER:-/tmp/web-draining}"
rm -f "$marker"

node server.js &
server=$!

drain() {
    : > "$marker"
    sleep "${WEB_SHUTDOWN_DELAY:-10}"
    kill -TERM "$server" 2>/dev/null || true
}
trap drain TERM INT

# A trapped signal interrupts the wait, so wait again until the server has really ended, and leave with its own exit
# status (a crash must not look like a clean exit to the platform that restarts containers).
status=0
while kill -0 "$server" 2>/dev/null; do
    wait "$server" && status=0 || status=$?
done
exit "$status"
