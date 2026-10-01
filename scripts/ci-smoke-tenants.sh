#!/usr/bin/env bash
# Tenant checks of the container smoke test (see .github/workflows/ci.yml); also usable by hand against any local
# container started the same way. The image is already running against the database container, as the application role.
#   usage: ci-smoke-tenants.sh <database container> <application role password>
# It creates one open and one closed organization directly in the database (as the owner; the API has no way to
# create one yet), then asks the API on port 8080 for each host name and checks the answers. Exits non-zero on a mismatch.
set -euo pipefail

db="${1:?database container name}"
app_password="${2:?application role password}"
domain="platform.example.test"
nobody="00000000-0000-0000-0000-000000000000"

owner_sql() {
  docker exec -i "$db" psql -U platform_owner -d platform -v ON_ERROR_STOP=1 -tA "$@"
}

owner_sql <<SQL
insert into tenant (slug, display_name, created_by, updated_by) values ('smoke-open', 'Smoke Open', '$nobody', '$nobody');
insert into tenant (slug, display_name, created_by, updated_by) values ('smoke-closed', 'Smoke Closed', '$nobody', '$nobody');
update tenant set status = 'ACTIVE', updated_by = '$nobody', version = version + 1 where slug in ('smoke-open', 'smoke-closed');
update tenant set status = 'SUSPENDED', updated_by = '$nobody', version = version + 1 where slug = 'smoke-closed';
insert into outbox_event (tenant_id, event_type, payload, created_by, updated_by)
    select id, 'smoke.probe', '{}'::jsonb, '$nobody', '$nobody' from tenant where slug = 'smoke-open';
SQL

expect() { # <description> <host> <expected status> <expected text>
  local body status
  body="$(mktemp)"
  status="$(curl -s -o "$body" -w '%{http_code}' -H "Host: $2" http://localhost:8080/api/v1/tenant/current)"
  if [ "$status" != "$3" ] || ! grep -q "$4" "$body"; then
    echo "FAILED: $1 (host $2): expected $3 with '$4', got $status:"
    cat "$body"
    echo
    exit 1
  fi
  echo "ok: $1 -> $status"
}

expect "an open organization is served" "smoke-open.$domain" 200 '"displayName":"Smoke Open"'
expect "an unknown organization is not found" "nobody-here.$domain" 404 '"code":"NOT_FOUND"'
expect "a closed organization is unavailable" "smoke-closed.$domain" 403 '"code":"TENANT_UNAVAILABLE"'
expect "the platform host addresses no organization" "$domain" 404 '"code":"NOT_FOUND"'
# The tenant can only come from the host: a header naming the open organization changes nothing for a closed one.
status="$(curl -s -o /dev/null -w '%{http_code}' -H "Host: smoke-closed.$domain" -H 'X-Tenant-ID: smoke-open' \
  http://localhost:8080/api/v1/tenant/current)"
[ "$status" = "403" ] || { echo "FAILED: a forged tenant header changed the answer ($status)"; exit 1; }
echo "ok: a forged tenant header changes nothing -> $status"

# The role the image runs as: unprivileged, row level security applies, no access to the migration history.
app_sql() {
  docker exec -i -e PGPASSWORD="$app_password" "$db" psql -h 127.0.0.1 -U platform_app -d platform -v ON_ERROR_STOP=1 -tA "$@"
}
flags="$(app_sql -c "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole from pg_roles where rolname = current_user")"
[ "$flags" = "f" ] || { echo "FAILED: the application role has special privileges"; exit 1; }
# Both tenants exist and the application role can read them (the tenant table is platform-level)...
[ "$(app_sql -c "select count(*) from tenant where slug like 'smoke-%'")" = "2" ] || { echo "FAILED: tenant table"; exit 1; }
# ...but with no tenant set, a tenant-scoped table shows nothing even though the owner sees events.
owner_events="$(owner_sql -c "select count(*) from outbox_event")"
app_events="$(app_sql -c "select count(*) from outbox_event")"
[ "$owner_events" -ge 1 ] || { echo "FAILED: the probe event was not written"; exit 1; }
[ "$app_events" = "0" ] || { echo "FAILED: the application role saw $app_events outbox rows without a tenant"; exit 1; }
echo "ok: application role unprivileged; tenant-scoped rows invisible without a tenant (owner sees $owner_events)"
if app_sql -c "select count(*) from flyway_schema_history" >/dev/null 2>&1; then
  echo "FAILED: the application role can read the migration history"
  exit 1
fi
echo "ok: the migration history is out of reach of the application role"

# The outbox relay inside the image, running as the application role, delivers the probe event (it reads across tenants
# through the system scope, which is the only way the role may).
for _ in $(seq 1 30); do
  status="$(owner_sql -c "select status from outbox_event where event_type = 'smoke.probe'")"
  [ "$status" = "DELIVERED" ] && break
  sleep 1
done
[ "$status" = "DELIVERED" ] || { echo "FAILED: the outbox relay did not deliver the probe event (status '$status')"; exit 1; }
echo "ok: the outbox relay delivered the probe event"
