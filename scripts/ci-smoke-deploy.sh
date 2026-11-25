#!/usr/bin/env bash
# Deployment checks of the container smoke test (Sprint 33, ADR-0288; see .github/workflows/ci.yml). It runs after the application
# image has passed the earlier smoke steps, on the same containers (database, Redis, object store, mail catcher, all on the
# network "smoke"). It proves, on the built image:
#   1. the image runs as a user without rights and has the two commands it documents;
#   2. the MIGRATION JOB (the image's `migrate` command, as the owner, with three values and nothing else) brings an empty
#      database to the latest schema version, and a second run has nothing to do;
#   3. the application starts on that database WITHOUT the right to migrate (PLATFORM_MIGRATE_ON_START=false, the application
#      role only), and its readiness turns UP: the version probe works for the role that cannot read the migration history;
#   4. an application started on a database that is BEHIND its release refuses to start, in one sentence, and says nothing about
#      the address or the password;
#   5. the release stamp is in /actuator/info;
#   6. a stop is orderly: the application says "not ready" and exits by itself within the time allowed.
#   usage: ci-smoke-deploy.sh <image> <owner password> <application password> <redis password> <store key> <store secret> <keys folder>
set -euo pipefail

image="${1:?image}"
owner_password="${2:?owner password}"
app_password="${3:?application password}"
redis_password="${4:?redis password}"
store_key="${5:?store key}"
store_secret="${6:?store secret}"
keys="${7:?keys folder}"
network=smoke

fail() {
  echo "FAILED: $1" >&2
  exit 1
}

owner_sql() { # <database> <sql...>
  local database="$1"
  shift
  docker exec -i smoke-db psql -U platform_owner -d "$database" -v ON_ERROR_STOP=1 -tA "$@"
}

# 1. Non-root, and the entry point refuses a command it does not know.
user="$(docker run --rm --entrypoint id "$image" -u)"
[ "$user" != "0" ] || fail "the image runs as root"
status=0
docker run --rm "$image" nonsense >/dev/null 2>&1 || status=$?
[ "$status" = "64" ] || fail "an unknown command ended with $status, not 64"
echo "ok: the image runs as user $user and refuses a command it does not know"

# 2. The migration job on an empty database, twice.
owner_sql platform -c "drop database if exists smoke_job"
owner_sql platform -c "create database smoke_job"
docker exec -i smoke-db psql -U platform_owner -d smoke_job -v ON_ERROR_STOP=1 -q < db/manual/M001__create_application_role.sql
job_url="jdbc:postgresql://smoke-db:5432/smoke_job"
docker run --rm --network "$network" -e PLATFORM_DB_URL="$job_url" -e PLATFORM_DB_OWNER_USER=platform_owner \
  -e PLATFORM_DB_OWNER_PASSWORD="$owner_password" "$image" migrate > job.log 2>&1 || { cat job.log; fail "the migration job failed"; }
grep -q 'migrations applied' job.log || { cat job.log; fail "the migration job did not say what it did"; }
if grep -q "$owner_password" job.log || grep -q 'jdbc:' job.log; then fail "the migration job's log names a password or an address"; fi
version="$(owner_sql smoke_job -c "select platform_schema_version()")"
latest="$(ls platform-app/src/main/resources/db/migration | sed -E 's/^V0*([0-9]+)__.*/\1/' | sort -n | tail -1)"
[ "$version" = "$latest" ] || fail "the schema is at version $version, the release has $latest"
docker run --rm --network "$network" -e PLATFORM_DB_URL="$job_url" -e PLATFORM_DB_OWNER_USER=platform_owner \
  -e PLATFORM_DB_OWNER_PASSWORD="$owner_password" "$image" migrate > job2.log 2>&1 || { cat job2.log; fail "the second job failed"; }
grep -q '0 migrations applied' job2.log || { cat job2.log; fail "a second job had something to do"; }
echo "ok: the migration job brought an empty database to version $version and a second run had nothing to do"

settings=(
  -e PLATFORM_DB_APP_USER=platform_app -e PLATFORM_DB_APP_PASSWORD="$app_password"
  -e PLATFORM_BASE_DOMAIN=platform.example.test
  -e PLATFORM_REDIS_HOST=smoke-redis -e PLATFORM_REDIS_PASSWORD="$redis_password"
  -e PLATFORM_SMTP_HOST=smoke-mail -e PLATFORM_SMTP_PORT=1025 -e PLATFORM_SMTP_AUTH=false
  -e PLATFORM_SMTP_STARTTLS_REQUIRED=false
  -e PLATFORM_MAIL_SENDER=no-reply@example.test -e PLATFORM_PUBLIC_BASE_URL=https://platform.example.test
  -e PLATFORM_STORAGE_ENDPOINT=http://smoke-store:8333 -e PLATFORM_STORAGE_PATH_STYLE=true
  -e PLATFORM_STORAGE_BUCKET=smoke-files
  -e PLATFORM_STORAGE_ACCESS_KEY="$store_key" -e PLATFORM_STORAGE_SECRET_KEY="$store_secret"
  -e PLATFORM_SECRETS_KEK_ACTIVE_ID=ci-deploy-kek -e PLATFORM_SECRETS_KEK_ACTIVE_KEY="$(openssl rand -base64 32)"
  -e PLATFORM_SIGNING_KEY_ID=ci-deploy-key
  -e PLATFORM_SIGNING_PRIVATE_KEY_FILE=/keys/private.pem -e PLATFORM_SIGNING_PUBLIC_KEY_FILE=/keys/public.pem
  -v "$keys:/keys:ro"
  -e PLATFORM_MIGRATE_ON_START=false
)

# 3. The application on the migrated database, without the right to migrate and without the owner's password.
docker rm -f smoke-validate >/dev/null 2>&1 || true
docker run -d --name smoke-validate --network "$network" -p 18080:8080 -p 18081:8081 \
  -e PLATFORM_DB_URL="$job_url" "${settings[@]}" -e PLATFORM_BUILD_COMMIT=0123456789abcdef -e PLATFORM_SHUTDOWN_DELAY=2s \
  "$image" >/dev/null
ready=""
for _ in $(seq 1 40); do
  if curl -fsS http://localhost:18081/actuator/health/readiness >/dev/null 2>&1; then ready=yes; break; fi
  sleep 2
done
[ -n "$ready" ] || { docker logs smoke-validate; fail "the application did not become ready on a migrated database without the right to migrate"; }
env_text="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' smoke-validate)"
if echo "$env_text" | grep -q 'PLATFORM_DB_OWNER'; then fail "the owner's credentials were given to the application"; fi
echo "ok: the application is ready on the migrated schema without the right to migrate"

# 5. The stamp.
curl -fsS http://localhost:18081/actuator/info | grep -q '"commit":"0123456789abcdef"' \
  || fail "the release stamp is not in /actuator/info"
echo "ok: the release stamp is shown"

# 6. An orderly stop: readiness turns false first, and the process ends by itself within the time allowed.
began="$(date +%s)"
docker stop -t 60 smoke-validate >/dev/null
took="$(( $(date +%s) - began ))"
code="$(docker inspect -f '{{.State.ExitCode}}' smoke-validate)"
[ "$took" -lt 55 ] || fail "the stop took $took seconds"
[ "$code" = "0" ] || [ "$code" = "143" ] || fail "the application ended with exit code $code"
docker logs smoke-validate 2>&1 | grep -q 'Not ready any more' || fail "the shutdown delay was not logged"
docker rm smoke-validate >/dev/null
echo "ok: a stop was orderly (it took $took seconds)"

# 4. A database that is behind: empty, so older than the probe. The application must refuse to start and say so.
owner_sql platform -c "drop database if exists smoke_behind"
owner_sql platform -c "create database smoke_behind"
docker exec -i smoke-db psql -U platform_owner -d smoke_behind -v ON_ERROR_STOP=1 -q < db/manual/M001__create_application_role.sql
status=0
docker run --rm --name smoke-behind --network "$network" -e PLATFORM_DB_URL="jdbc:postgresql://smoke-db:5432/smoke_behind" \
  "${settings[@]}" "$image" > behind.log 2>&1 || status=$?
[ "$status" != "0" ] || fail "an application on a database that is behind started"
grep -q 'schema is behind this release' behind.log || { cat behind.log; fail "the refusal does not say that the schema is behind"; }
if grep -q "$app_password" behind.log || grep -q 'jdbc:' behind.log; then fail "the refusal names a password or an address"; fi
echo "ok: an application on a database that is behind its release refuses to start, in one sentence"

owner_sql platform -c "drop database smoke_job" >/dev/null
owner_sql platform -c "drop database smoke_behind" >/dev/null
rm -f job.log job2.log behind.log
