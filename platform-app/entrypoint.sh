#!/bin/sh
# Entry point of the platform application image (Sprint 33, ADR-0287, ADR-0288).
#
#   serve    (the default) start the application. This is what the image did before this file existed.
#   migrate  run the schema migrations as the owner role and exit (the migration job of a deployment). Needs only
#            PLATFORM_DB_URL, PLATFORM_DB_OWNER_USER and PLATFORM_DB_OWNER_PASSWORD.
#
# Key files. A secret store hands a container its secrets as environment variables, but the token signing keys are read
# from files. Every variable named PLATFORM_KEYFILE__<name> is written to a file <name> in a private directory and then
# removed from the environment, so the value is not left in the environment of the running process. The settings then name
# the file, for example PLATFORM_SIGNING_PRIVATE_KEY_FILE=/tmp/platform-keys/<name>. Nothing is printed.
set -eu

keys_dir="${PLATFORM_KEYFILE_DIR:-/tmp/platform-keys}"
umask 077
for entry in $(env | sed -n 's/^PLATFORM_KEYFILE__\([A-Za-z0-9_][A-Za-z0-9_]*\)=.*/\1/p'); do
    mkdir -p "$keys_dir"
    eval "value=\${PLATFORM_KEYFILE__${entry}}"
    printf '%s\n' "$value" > "$keys_dir/$entry"
    unset "PLATFORM_KEYFILE__${entry}"
done
# The same, for a platform that cannot carry a value with line breaks: PLATFORM_KEYFILE_B64__<name> holds the file in Base64.
for entry in $(env | sed -n 's/^PLATFORM_KEYFILE_B64__\([A-Za-z0-9_][A-Za-z0-9_]*\)=.*/\1/p'); do
    mkdir -p "$keys_dir"
    eval "value=\${PLATFORM_KEYFILE_B64__${entry}}"
    printf '%s' "$value" | base64 -d > "$keys_dir/$entry"
    unset "PLATFORM_KEYFILE_B64__${entry}"
done
unset value entry

# Container-aware memory, UTC, and a restart (by the platform that runs the container) instead of a half-alive process
# after an out-of-memory error.
java_flags="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC -XX:+ExitOnOutOfMemoryError"

case "${1:-serve}" in
    serve)
        # shellcheck disable=SC2086
        exec java $java_flags -jar app.jar
        ;;
    migrate)
        # shellcheck disable=SC2086
        exec java $java_flags -Dlogback.configurationFile=migration-job-logback.xml -cp app.jar \
            app.platform.deployment.internal.MigrationJob
        ;;
    *)
        echo "Unknown command '$1': use 'serve' or 'migrate'" >&2
        exit 64
        ;;
esac
