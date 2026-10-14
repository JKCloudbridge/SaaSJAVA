#!/usr/bin/env bash
# Authentication checks of the container smoke test (see .github/workflows/ci.yml); also usable by hand against any local
# container started the same way (production profile, Redis, a token signing key pair). The image is already running
# against the database and Redis containers, as the application role.
#   usage: ci-smoke-auth.sh <database container> <redis container> <redis password> [<mail catcher address>]
# It proves, on the image: a protected endpoint refuses an unauthenticated caller in the error model, the public
# endpoints stay public, a state-changing request needs the forgery header, the cookies carry the Secure flag outside a
# developer machine, a failed sign-in answers the same 401 for an account that does not exist, the audit record is
# written, the published key set holds the public key and no private part, the authorization endpoint without a
# sign-in goes to the sign-in page, and Redis is really in use. With the address of a mail catcher (for example
# http://localhost:8025) it also runs a whole sign-up on the image: the request answers 202, the e-mail arrives with a link
# on the platform host, completing the link creates the account and the person signs in. It then runs one invitation
# round trip (Sprint 5): the person founds an organization, invites a second address as an administrator of it, the
# invitation e-mail arrives with a link on the platform host, accepting it once creates the account and the membership
# (twice does not), and the invited person signs in on the organization's host. Finally (Sprint 6) it creates the first
# platform administrator the documented way (manual migration M002, run as the owner, for the first person's account),
# provisions an organization for a client as that administrator, reads the first-administrator e-mail, accepts it, and
# checks that the organization was closed until then, that an ordinary person reaches no platform endpoint and that no
# token is stored in clear. Run it from the repository root (it reads db/manual/M002...). The owner's role name can be
# set with OWNER_USER. Since Sprint 7 the invitation chooses a profile for the invited person (the administrator profile here),
# and a last step creates a profile and an access policy as the first administrator, gives the policy, reads
# the access back and checks the audit records and that a platform token is refused on an organization host.
# Since Sprint 14 a step makes a record, changes it with its version, deletes and restores it and proves that an object with
# a record cannot be removed. Since Sprint 15 a step proves the write pipeline: a required field, a refused value, a rule,
# a formula, an auto-number. Since Sprint 15A a step proves record type assignments. Since Sprint 16A a step proves uniqueness and the recalculation of stored formula results. Since Sprint 17 a step proves record-level security, a file round trip, a bulk call, an import and a search (the image needs the object storage of the smoke job).
set -euo pipefail

db="${1:?database container name}"
redis="${2:?redis container name}"
redis_password="${3:?redis password}"
mail="${4:-}"
base="http://localhost:8080"
host="platform.example.test"

fail() {
  echo "FAILED: $1" >&2
  exit 1
}

body="$(mktemp)"
headers="$(mktemp)"
call() { # <extra curl arguments...>: writes $body and $headers, prints the status code
  curl -s -o "$body" -D "$headers" -w '%{http_code}' -H "Host: ${call_host:-$host}" "$@"
}

cookie_value() { # <name>: the value of a cookie the last response set
  grep -i "^set-cookie: $1=" "$headers" | head -1 | tr -d '\r' | sed -E 's/^[^=]*=([^;]*).*/\1/' || true
}

location_path() { # the path and query of the last redirect
  grep -i '^location:' "$headers" | head -1 | tr -d '\r' | sed -E 's/^[^:]*: *//; s#^https?://[^/]+##'
}

sign_in_token() { # <host> <email> <password>: the browser's whole sign-in on that host; prints the access token
  local on="$1" email="$2" secret="$3" xsrf login tx path
  call_host="$on"
  [ "$(call "$base/api/v1/auth/csrf")" = "204" ] || fail "no forgery cookie on $on"
  xsrf="$(cookie_value XSRF-TOKEN)"
  [ "$(call -X POST -H 'Content-Type: application/json' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"email\":\"$email\",\"password\":\"$secret\"}" "$base/api/v1/auth/sign-in")" = "204" ] \
    || fail "the sign-in on $on was refused"
  login="$(cookie_value platform_login)"
  [ "$(call -H "Cookie: platform_login=$login" "$base/api/v1/auth/start?continue=/")" = "302" ] || fail "no start on $on"
  tx="$(cookie_value platform_tx)"
  path="$(location_path)"
  [ "$(call -H "Cookie: platform_login=$login; platform_tx=$tx" "$base$path")" = "302" ] || fail "no code on $on"
  path="$(location_path)"
  [ "$(call -H "Cookie: platform_login=$login; platform_tx=$tx" "$base$path")" = "302" ] || fail "no callback on $on"
  cookie_value platform_at
  call_host=""
}

# 1. A protected endpoint refuses an unauthenticated caller, in the error model, with a bearer challenge.
status="$(call "$base/api/v1/auth/me")"
[ "$status" = "401" ] || fail "an unauthenticated call to /auth/me answered $status"
grep -q '"code":"UNAUTHENTICATED"' "$body" || fail "the 401 is not in the error model: $(cat "$body")"
grep -qi '^www-authenticate: Bearer' "$headers" || fail "the 401 has no bearer challenge"
echo "ok: a protected endpoint refuses an unauthenticated caller (401, error model)"

# 2. Public endpoints stay public.
status="$(call "$base/api/v1/platform/status")"
[ "$status" = "200" ] || fail "the status endpoint answered $status"
echo "ok: the status endpoint is still public"

# 3 and 4. The forgery cookie carries the Secure flag; a state-changing request needs the header.
status="$(call "$base/api/v1/auth/csrf")"
[ "$status" = "204" ] || fail "the forgery cookie endpoint answered $status"
cookie_line="$(grep -i '^set-cookie: XSRF-TOKEN=' "$headers" | head -1 | tr -d '\r')"
[ -n "$cookie_line" ] || fail "no forgery cookie was set"
echo "$cookie_line" | grep -qi 'secure' || fail "the forgery cookie has no Secure flag: $cookie_line"
echo "$cookie_line" | grep -qi 'samesite=strict' || fail "the forgery cookie is not SameSite=Strict: $cookie_line"
token="$(echo "$cookie_line" | sed -E 's/^[^=]*=([^;]*).*/\1/')"
echo "ok: the forgery cookie is Secure and SameSite=Strict"

payload='{"email":"nobody-smoke@example.test","password":"a wrong password for the smoke test"}'
status="$(call -X POST -H 'Content-Type: application/json' -d "$payload" "$base/api/v1/auth/sign-in")"
[ "$status" = "403" ] || fail "a sign-in without the forgery header answered $status"
grep -q '"code":"FORBIDDEN"' "$body" || fail "the 403 is not in the error model"
echo "ok: a sign-in without the forgery header is refused (403)"

# 5. A failed sign-in answers 401 in the error model, with the one message for every kind of failure.
status="$(call -X POST -H 'Content-Type: application/json' -H "Cookie: XSRF-TOKEN=$token" -H "X-XSRF-TOKEN: $token" \
  -d "$payload" "$base/api/v1/auth/sign-in")"
[ "$status" = "401" ] || fail "a sign-in for an unknown account answered $status: $(cat "$body")"
grep -q 'The email address or the password is not correct.' "$body" || fail "the failure message differs: $(cat "$body")"
grep -qi 'set-cookie: platform_' "$headers" && fail "a failed sign-in set a session cookie"
echo "ok: a failed sign-in answers the uniform 401 and sets no session cookie"

# 6. The authorization endpoint without a sign-in goes to the sign-in page, never to a login form of its own.
status="$(call "$base/api/v1/oauth2/authorize?response_type=code&client_id=platform-web&scope=openid&state=s&code_challenge=Ls3IWSHk8xlWXzwOQs8gLX2CAKtXcVZ0Ba-8Pp8VNoY&code_challenge_method=S256&redirect_uri=https%3A%2F%2F$host%2Fapi%2Fv1%2Fauth%2Fcallback")"
[ "$status" = "302" ] || fail "the authorization endpoint without a sign-in answered $status"
grep -qi '^location: /sign-in' "$headers" || fail "the authorization endpoint did not point to the sign-in page"
echo "ok: the authorization endpoint without a sign-in points to the sign-in page"

# 7. The published key set holds the configured public key and no private part.
status="$(call "$base/api/v1/oauth2/jwks")"
[ "$status" = "200" ] || fail "the key set answered $status"
grep -q 'ci-smoke-key' "$body" || fail "the key set does not hold the configured key: $(cat "$body")"
grep -q '"d"' "$body" && fail "the key set exposes a private part"
echo "ok: the key set publishes the configured public key and no private part"

# 8. The failed sign-ins were audited, with the true reason, and the application role can only add records.
owner_sql() { docker exec -i "$db" psql -U "${OWNER_USER:-platform_owner}" -d "${OWNER_DB:-platform}" -v ON_ERROR_STOP=1 -tA "$@"; }
audited="$(owner_sql -c "select count(*) from audit_record where event_type = 'auth.sign_in.failed' and reason = 'unknown_account'")"
[ "$audited" -ge 1 ] || fail "the failed sign-in was not audited"
if owner_sql -c "select attributes::text from audit_record" | grep -q 'a wrong password for the smoke test'; then
  fail "a password reached the audit table"
fi
echo "ok: the failed sign-in is audited with its true reason, and no password is in the table"

# 9. Redis is really in use by the image: the rate-limit counters are there.
keys="$(docker exec "$redis" redis-cli -a "$redis_password" --no-auth-warning --scan --pattern 'platform:identity:*' | head -1)"
[ -n "$keys" ] || fail "the image wrote no rate-limit counter to Redis"
echo "ok: the rate-limit counters live in Redis"

# 10. With a mail catcher: a whole sign-up on the image. The request answers 202; the e-mail (sent by the mail relay,
# asynchronously) arrives with a link on the platform host; completing it creates the account; the person signs in.
if [ -n "$mail" ]; then
  address="smoke-$(date +%s)@example.test"
  password="A-long-smoke-test-passphrase-$RANDOM-$RANDOM"
  json_headers=(-H 'Content-Type: application/json' -H "Cookie: XSRF-TOKEN=$token" -H "X-XSRF-TOKEN: $token")
  status="$(call -X POST "${json_headers[@]}" -d "{\"email\":\"$address\"}" "$base/api/v1/auth/sign-up")"
  [ "$status" = "202" ] || fail "a sign-up request answered $status: $(cat "$body")"
  status="$(call -X POST "${json_headers[@]}" -d '{"email":"nobody-smoke@example.test"}' "$base/api/v1/auth/sign-up")"
  [ "$status" = "202" ] || fail "a sign-up request for another address answered $status"
  echo "ok: a sign-up request answers 202"

  link=""
  for _ in $(seq 1 30); do
    message_id="$(curl -fsS "$mail/api/v1/search?query=to:$address" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -n "$message_id" ]; then
      link="$(curl -fsS "$mail/api/v1/message/$message_id" | grep -o 'https\?://[A-Za-z0-9./:_-]*#token=[A-Za-z0-9_-]*' | head -1 || true)"
      break
    fi
    sleep 2
  done
  [ -n "$link" ] || fail "no sign-up e-mail arrived for the address"
  case "$link" in
    "https://$host/sign-up/complete#token="*) ;;
    *) fail "the link does not point at the platform host: ${link%%#*}" ;;
  esac
  echo "ok: the sign-up e-mail arrived with a link on the platform host"

  link_token="${link#*#token=}"
  complete="{\"token\":\"$link_token\",\"displayName\":\"Smoke Person\",\"password\":\"$password\"}"
  status="$(call -X POST "${json_headers[@]}" -d "$complete" "$base/api/v1/auth/sign-up/complete")"
  [ "$status" = "204" ] || fail "completing the sign-up answered $status: $(cat "$body")"
  status="$(call -X POST "${json_headers[@]}" -d "$complete" "$base/api/v1/auth/sign-up/complete")"
  [ "$status" = "400" ] || fail "the link worked a second time ($status)"
  echo "ok: the link creates the account once and not twice"

  status="$(call -X POST "${json_headers[@]}" -d "{\"email\":\"$address\",\"password\":\"$password\"}" \
    "$base/api/v1/auth/sign-in")"
  [ "$status" = "204" ] || fail "the new person could not sign in ($status)"
  echo "ok: the new person signs in"

  stored="$(owner_sql -c "select t::text from account_token t union all select t::text from mail_queue t \
    union all select attributes::text from audit_record")"
  if echo "$stored" | grep -q "$link_token"; then
    fail "a link token was stored in clear"
  fi
  echo "ok: no token is stored in clear (tokens, queue, audit)"



  # 11. An invitation round trip on the image (Sprint 5). The first person founds an organization and signs in on its
  # host; they invite a second address as an administrator; the invitation e-mail arrives with a link on the platform
  # host; accepting creates the account and the membership once; the invited person signs in on the organization's host.
  founder_token="$(sign_in_token "$host" "$address" "$password")"
  [ -n "$founder_token" ] || fail "the founder has no access token"
  org_slug="smoke-org-$(date +%s)"
  status="$(call -X POST -H "Authorization: Bearer $founder_token" -H 'Content-Type: application/json' \
    -d "{\"displayName\":\"Smoke Organization\",\"slug\":\"$org_slug\"}" "$base/api/v1/organizations")"
  [ "$status" = "201" ] || fail "founding an organization answered $status: $(cat "$body")"
  org_host="$org_slug.$host"
  org_token="$(sign_in_token "$org_host" "$address" "$password")"
  [ -n "$org_token" ] || fail "the founder could not sign in on the organization's host"
  echo "ok: a person founds an organization and signs in on its host as its member"

  # Sprint 7: the administrator chooses the profile of the new member; the administrator profile is the one of the system.
  status="$(call_host="$org_host" call -H "Authorization: Bearer $org_token" "$base/api/v1/profiles")"
  [ "$status" = "200" ] || fail "listing the profiles answered $status: $(cat "$body")"
  admin_profile="$(grep -o '"id":"[^"]*","name":"Organization administrator"' "$body" | head -1 | cut -d'"' -f4)"
  [ -n "$admin_profile" ] || fail "the organization has no administrator profile: $(cat "$body")"
  invitee="smoke-invitee-$(date +%s)@example.test"
  invitee_password="A-long-invited-passphrase-$RANDOM-$RANDOM"
  status="$(call_host="$org_host" call -X POST -H "Authorization: Bearer $org_token" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$invitee\",\"displayName\":\"Invited Person\",\"profileId\":\"$admin_profile\"}" \
    "$base/api/v1/invitations")"
  [ "$status" = "202" ] || fail "inviting answered $status: $(cat "$body")"
  status="$(call_host="$org_host" call -X POST -H "Authorization: Bearer $org_token" -H 'Content-Type: application/json' \
    -d '{"email":"nobody-smoke@example.test"}' "$base/api/v1/invitations")"
  [ "$status" = "202" ] || fail "inviting another address answered $status"
  echo "ok: an administrator's invitation request answers 202 whatever the address"

  invite_link=""
  for _ in $(seq 1 30); do
    message_id="$(curl -fsS "$mail/api/v1/search?query=to:$invitee" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -n "$message_id" ]; then
      invite_link="$(curl -fsS "$mail/api/v1/message/$message_id" | grep -o 'https\?://[A-Za-z0-9./:_-]*#token=[A-Za-z0-9_-]*' | head -1 || true)"
      break
    fi
    sleep 2
  done
  [ -n "$invite_link" ] || fail "no invitation e-mail arrived"
  case "$invite_link" in
    "https://$host/invitations/accept#token="*) ;;
    *) fail "the invitation link does not point at the platform host: ${invite_link%%#*}" ;;
  esac
  echo "ok: the invitation e-mail arrived with a link on the platform host"

  invite_token="${invite_link#*#token=}"
  accept="{\"token\":\"$invite_token\",\"password\":\"$invitee_password\"}"
  status="$(call -X POST "${json_headers[@]}" -d "{\"token\":\"$invite_token\"}" "$base/api/v1/auth/invitations/preview")"
  [ "$status" = "200" ] || fail "reading the invitation answered $status: $(cat "$body")"
  grep -q '"existingAccount":false' "$body" || fail "the preview does not say the address has no account: $(cat "$body")"
  status="$(call -X POST "${json_headers[@]}" -d "$accept" "$base/api/v1/auth/invitations/accept-new")"
  [ "$status" = "200" ] || fail "accepting the invitation answered $status: $(cat "$body")"
  grep -q "\"host\":\"$org_host\"" "$body" || fail "the answer does not name the organization's host: $(cat "$body")"
  status="$(call -X POST "${json_headers[@]}" -d "$accept" "$base/api/v1/auth/invitations/accept-new")"
  [ "$status" = "400" ] || fail "the invitation worked a second time ($status)"
  echo "ok: accepting creates the account and the membership once and not twice"

  invitee_token="$(sign_in_token "$org_host" "$invitee" "$invitee_password")"
  [ -n "$invitee_token" ] || fail "the invited person could not sign in on the organization's host"
  status="$(call_host="$org_host" call -H "Authorization: Bearer $invitee_token" "$base/api/v1/members")"
  [ "$status" = "200" ] || fail "the invited administrator could not list the members ($status)"
  [ "$(owner_sql -c "select count(*) from membership m join tenant t on t.id = m.tenant_id where t.slug = '$org_slug'")" = "2" ] \
    || fail "the organization does not have exactly two members"
  echo "ok: the invited person signs in on the organization's host and is its second member"

  stored="$(owner_sql -c "select t::text from account_token t union all select t::text from mail_queue t \
    union all select attributes::text from audit_record union all select t::text from organization_handoff t")"
  if echo "$stored" | grep -q "$invite_token"; then
    fail "an invitation token was stored in clear"
  fi
  if echo "$stored" | grep -q "$invitee_password"; then
    fail "a password reached a table"
  fi
  echo "ok: no invitation token or password is stored in clear"

  # 12. Provisioning an organization for a client (Sprint 6). The first platform administrator is created the documented
  # way: manual migration M002, run as the owner, for the account of the first person. They sign in on the platform
  # host, set an organization up for a client with a plan and the address of its first administrator, and the
  # organization stays closed until that person accepts the mailed invitation, which opens it.
  m002_output="$(docker exec -i "$db" psql -U "${OWNER_USER:-platform_owner}" -d "${OWNER_DB:-platform}" \
    -v ON_ERROR_STOP=1 -v admin_email="$address" < db/manual/M002__grant_first_platform_administrator.sql 2>&1)" \
    || fail "the manual step that creates the first platform administrator failed: $m002_output"
  case "$m002_output" in
    *"M002 done"*) ;;
    *"already exists"*)
      # Only on a database that an earlier run of this script used: the documented repair path adds this person as well.
      m002_output="$(docker exec -i "$db" psql -U "${OWNER_USER:-platform_owner}" -d "${OWNER_DB:-platform}" \
        -v ON_ERROR_STOP=1 -v admin_email="$address" -v allow_additional=yes \
        < db/manual/M002__grant_first_platform_administrator.sql 2>&1)" \
        || fail "the repair form of the manual step failed: $m002_output"
      ;;
    *) fail "the manual step answered something unexpected: $m002_output" ;;
  esac
  [ "$(owner_sql -c "select count(*) from platform_role_assignment a join platform_user u on u.id = a.user_id \
    where u.email = '$address' and a.role = 'PLATFORM_ADMIN' and a.deleted_at is null")" -ge 1 ] \
    || fail "the person does not hold the platform administrator role"
  echo "ok: the first platform administrator was created by the documented manual step"

  console_token="$(sign_in_token "$host" "$address" "$password")"
  [ -n "$console_token" ] || fail "the platform administrator could not sign in on the platform host"
  status="$(call -H "Authorization: Bearer $console_token" "$base/api/v1/platform/organizations")"
  [ "$status" = "200" ] || fail "the console list answered $status: $(cat "$body")"
  status="$(call_host="$org_host" call -H "Authorization: Bearer $org_token" "$base/api/v1/platform/organizations")"
  [ "$status" = "404" ] || fail "the console answered $status on an organization's host"
  invitee_platform_token="$(sign_in_token "$host" "$invitee" "$invitee_password")"
  status="$(call -H "Authorization: Bearer $invitee_platform_token" "$base/api/v1/platform/organizations")"
  [ "$status" = "403" ] || fail "an ordinary person reached the console ($status)"
  echo "ok: the console answers the platform administrator on the platform host only, and an ordinary person is refused"

  client="smoke-client-$(date +%s)"
  client_slug="smoke-client"
  client_admin="smoke-client-admin-$(date +%s)@example.test"
  client_password="A-long-client-passphrase-$RANDOM-$RANDOM"
  status="$(call -X POST -H "Authorization: Bearer $console_token" -H 'Content-Type: application/json' \
    -d "{\"displayName\":\"Smoke Client\",\"slug\":\"$client_slug-$RANDOM\",\"planKey\":\"trial\",\"email\":\"$client_admin\"}" \
    "$base/api/v1/platform/organizations")"
  [ "$status" = "201" ] || fail "provisioning answered $status: $(cat "$body")"
  grep -q '"status":"PROVISIONING"' "$body" || fail "the new organization is not being set up: $(cat "$body")"
  grep -q "$client_admin" "$body" && fail "the provisioning answer shows the address"
  client_host="$(grep -o '"slug":"[^"]*"' "$body" | head -1 | cut -d'"' -f4).$host"
  status="$(call_host="$client_host" call "$base/api/v1/tenant/current")"
  [ "$status" = "403" ] || fail "the organization is not closed before acceptance ($status)"
  echo "ok: provisioning answers 201 and the organization stays closed until its first administrator accepts"

  client_link=""
  for _ in $(seq 1 30); do
    message_id="$(curl -fsS "$mail/api/v1/search?query=to:$client_admin" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -n "$message_id" ]; then
      client_link="$(curl -fsS "$mail/api/v1/message/$message_id" | grep -o 'https\?://[A-Za-z0-9./:_-]*#token=[A-Za-z0-9_-]*' | head -1 || true)"
      break
    fi
    sleep 2
  done
  [ -n "$client_link" ] || fail "no first-administrator e-mail arrived"
  case "$client_link" in
    "https://$host/invitations/accept#token="*) ;;
    *) fail "the first-administrator link does not point at the platform host: ${client_link%%#*}" ;;
  esac
  client_token="${client_link#*#token=}"
  status="$(call -X POST "${json_headers[@]}" \
    -d "{\"token\":\"$client_token\",\"displayName\":\"Client Administrator\",\"password\":\"$client_password\"}" \
    "$base/api/v1/auth/invitations/accept-new")"
  [ "$status" = "200" ] || fail "the first administrator could not accept ($status): $(cat "$body")"
  grep -q "\"host\":\"$client_host\"" "$body" || fail "the answer does not name the organization's host: $(cat "$body")"
  status="$(call_host="$client_host" call "$base/api/v1/tenant/current")"
  [ "$status" = "200" ] || fail "accepting did not open the organization ($status)"
  client_access="$(sign_in_token "$client_host" "$client_admin" "$client_password")"
  [ -n "$client_access" ] || fail "the first administrator could not sign in on the organization's host"
  status="$(call_host="$client_host" call -H "Authorization: Bearer $client_access" "$base/api/v1/members")"
  [ "$status" = "200" ] || fail "the first administrator could not list the members ($status)"
  [ "$(owner_sql -c "select m.founding_administrator and p.system_key = 'administrator' and l.id is not null \
    from membership m join tenant t on t.id = m.tenant_id \
    join member_access a on a.membership_id = m.id and a.deleted_at is null \
    join profile p on p.id = a.profile_id \
    left join licence_assignment l on l.membership_id = m.id and l.purpose = 'PROFILE' and l.deleted_at is null \
    where t.slug = '${client_host%%.*}'")" = "t" ] \
    || fail "the first administrator is not the founder with the administrator profile and an administrator licence"
  echo "ok: the first administrator accepts, the organization opens and they sign in as its founder"

  # 13. An assignment round trip on the image (Sprint 7): the first administrator creates a profile and an
  # access policy, gives the policy to themselves, reads what decides their
  # access, and a platform person's token is refused on the organization's host. The abilities come from the profile.
  client_call() {
    call_host="$client_host" call -H "Authorization: Bearer $client_access" "$@"
  }
  status="$(client_call -X POST -H 'Content-Type: application/json' \
    -d '{"name":"smoke-profile","description":"","licenceType":"admin","abilities":["members.view"]}' \
    "$base/api/v1/profiles")"
  [ "$status" = "201" ] || fail "creating a profile answered $status: $(cat "$body")"
  status="$(client_call -X POST -H 'Content-Type: application/json' \
    -d '{"name":"smoke-policy","description":"","abilities":["members.invite"]}' "$base/api/v1/access-policies")"
  [ "$status" = "201" ] || fail "creating an access policy answered $status: $(cat "$body")"
  policy_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  own_membership="$(owner_sql -c "select m.id from membership m join tenant t on t.id = m.tenant_id \
    where t.slug = '${client_host%%.*}' and m.founding_administrator")"
  status="$(client_call -X POST -H 'Content-Type: application/json' -d "{\"policyId\":\"$policy_id\"}" \
    "$base/api/v1/members/$own_membership/policies")"
  [ "$status" = "204" ] || fail "giving an access policy answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/members/$own_membership/access")"
  [ "$status" = "200" ] || fail "reading the access of a member answered $status: $(cat "$body")"
  grep -q '"profileName":"Organization administrator"' "$body" || fail "the access view lacks the profile: $(cat "$body")"
  grep -q '"licenceHeld":true' "$body" || fail "the founder does not hold the licence of their profile: $(cat "$body")"
  grep -q '"policies":\[{' "$body" || fail "the access view lacks the policy: $(cat "$body")"
  status="$(client_call "$base/api/v1/auth/me")"
  grep -q 'access.manage' "$body" || fail "the caller's abilities do not name access.manage: $(cat "$body")"
  # A platform person's token on an organization's host is no member: refused, the same as everywhere else.
  status="$(call_host="$client_host" call -H "Authorization: Bearer $console_token" "$base/api/v1/profiles")"
  [ "$status" = "401" ] || fail "a platform administrator's token was accepted on an organization's host ($status)"
  [ "$(owner_sql -c "select count(*) from audit_record where event_type like 'access.%' and context_tenant_id is not null")" -ge 3 ] \
    || fail "the changes of access were not audited"
  echo "ok: a profile and an access policy are created and given, the access is read back, and the changes are audited"

  # 14. Groups and permissions on data on the image (Sprint 8): the first administrator creates two groups, puts themselves
  # and the second group into the first, gives the policy to the group, a loop is refused, the catalogue lists the standard
  # objects (Sprint 10), and a permission on an object that does not exist is refused.
  group_json='Content-Type: application/json'
  status="$(client_call -X POST -H "$group_json" -d '{"name":"smoke-group-a","description":""}' "$base/api/v1/groups")"
  [ "$status" = "201" ] || fail "creating a group answered $status: $(cat "$body")"
  group_a="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"smoke-group-b","description":""}' "$base/api/v1/groups")"
  [ "$status" = "201" ] || fail "creating a second group answered $status: $(cat "$body")"
  group_b="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" -d "{\"membershipId\":\"$own_membership\"}" "$base/api/v1/groups/$group_a/members")"
  [ "$status" = "200" ] || fail "putting a person into a group answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "{\"groupId\":\"$group_b\"}" "$base/api/v1/groups/$group_a/members")"
  [ "$status" = "200" ] || fail "putting a group into a group answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "{\"groupId\":\"$group_a\"}" "$base/api/v1/groups/$group_b/members")"
  [ "$status" = "409" ] || fail "a loop of groups was not refused ($status): $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "{\"policyId\":\"$policy_id\"}" "$base/api/v1/groups/$group_a/policies")"
  [ "$status" = "200" ] || fail "giving a policy to a group answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/members/$own_membership/access")"
  grep -Fq '"groups":[{' "$body" || fail "the access view lacks the group: $(cat "$body")"
  status="$(client_call "$base/api/v1/data-catalogue")"
  [ "$status" = "200" ] || fail "reading the data catalogue answered $status: $(cat "$body")"
  grep -Fq '"key":"Account"' "$body" || fail "the catalogue lacks the standard objects: $(cat "$body")"
  profile_id="$(client_call "$base/api/v1/profiles" >/dev/null; grep -o '"id":"[^"]*","name":"smoke-profile"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X PUT -H "$group_json" -d '{"objects":[{"key":"Ghost__c","actions":["read"]}],"fields":[]}'     "$base/api/v1/profiles/$profile_id/data-access")"
  [ "$status" = "400" ] || fail "a permission on an object that does not exist was not refused ($status): $(cat "$body")"
  status="$(client_call "$base/api/v1/data-access/mine")"
  grep -q '"everything":true' "$body" || fail "the administrator profile does not hold everything: $(cat "$body")"
  [ "$(owner_sql -c "select count(*) from audit_record where event_type like 'access.group.%' and context_tenant_id is not null")" -ge 3 ]     || fail "the changes of groups were not audited"
  echo "ok: groups are created, filled and given a policy, a loop is refused, the standard objects are in the catalogue, an object that does not exist is refused, and the changes are audited"

  # 15. The audit viewer on the image (Sprint 9): the administrator reads the group changes back through the API, the
  # events carry no typed text, a malformed filter is refused in words, and the platform endpoint is not served here.
  status="$(client_call "$base/api/v1/audit-events?kind=access.group&limit=50")"
  [ "$status" = "200" ] || fail "the audit viewer answered $status: $(cat "$body")"
  grep -Fq '"type":"access.group.created"' "$body" || fail "the viewer does not show the group that was created: $(cat "$body")"
  grep -Fq '"type":"access.group.member_added"' "$body" || fail "the viewer does not show the person added to a group: $(cat "$body")"
  status="$(client_call "$base/api/v1/audit-events?kind=Not%20A%20Kind")"
  [ "$status" = "400" ] || fail "a malformed audit filter was not refused ($status): $(cat "$body")"
  status="$(client_call "$base/api/v1/platform/audit-events")"
  [ "$status" = "404" ] || [ "$status" = "403" ] || fail "the platform audit endpoint answered an organization host ($status)"
  echo "ok: the audit viewer shows the changes of groups, refuses a malformed filter, and does not serve the platform endpoint here"

  # 16. Objects and fields on the image (Sprint 10): the first administrator sees the standard objects, makes an object of
  # their own and a field on it, is refused in words when they try to change a standard object, sees the changes in the
  # audit viewer without anything they typed, and removes what they made.
  status="$(client_call "$base/api/v1/metadata/objects")"
  [ "$status" = "200" ] || fail "listing the objects answered $status: $(cat "$body")"
  grep -Fq '"apiName":"Account"' "$body" || fail "the object list lacks the standard objects: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Employee","label":"Employee smoke label","pluralLabel":"Employees","description":""}' \
    "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "creating an object answered $status: $(cat "$body")"
  grep -Fq '"apiName":"Employee__c"' "$body" || fail "the new object does not end in __c: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"salary","label":"Salary","type":"CURRENCY","required":true}' \
    "$base/api/v1/metadata/objects/Employee__c/fields")"
  [ "$status" = "201" ] || fail "adding a field answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Employee__c")"
  [ "$status" = "200" ] || fail "reading the new object answered $status: $(cat "$body")"
  grep -Fq '"apiName":"salary__c"' "$body" || fail "the object lacks the new field: $(cat "$body")"
  status="$(client_call -X PUT -H "$group_json" -d '{"label":"Hacked","pluralLabel":"Hacked","description":"","version":0}' \
    "$base/api/v1/metadata/objects/Account")"
  [ "$status" = "403" ] || fail "a change to a standard object was not refused ($status): $(cat "$body")"
  grep -Fq 'defined by the platform' "$body" || fail "the refusal does not say the platform defines it: $(cat "$body")"
  status="$(client_call "$base/api/v1/audit-events?kind=metadata&limit=50")"
  [ "$status" = "200" ] || fail "the audit viewer answered $status: $(cat "$body")"
  grep -Fq '"type":"metadata.object.created"' "$body" || fail "the viewer does not show the new object: $(cat "$body")"
  grep -Fq '"type":"metadata.change.refused"' "$body" || fail "the viewer does not show the refused change: $(cat "$body")"
  if grep -Fq 'Employee smoke label' "$body"; then
    fail "a label that was typed reached the audit viewer"
  fi
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Employee__c")"
  [ "$status" = "204" ] || fail "removing the object answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Employee__c")"
  [ "$status" = "404" ] || fail "a removed object is still there ($status)"
  echo "ok: the standard objects are listed, an object and a field are made, a change to a standard object is refused in words, the changes are audited without typed text, and the object is removed"

  # 17. The metadata lifecycle on the image (Sprint 11): a record type offers a field, so a change set that removes the
  # field is refused with the record type named and nothing is kept; a good change set is checked, published as one
  # release, appears in the history and is rolled back.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Deal","label":"Deal","pluralLabel":"Deals","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "creating the lifecycle object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"department","label":"Department","type":"TEXT"}' \
    "$base/api/v1/metadata/objects/Deal__c/fields")"
  [ "$status" = "201" ] || fail "adding the lifecycle field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Core","label":"Core","availableFields":["department__c"],"picklistSubsets":[]}' \
    "$base/api/v1/metadata/objects/Deal__c/record-types")"
  [ "$status" = "201" ] || fail "adding a record type answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/fields/department__c")"
  [ "$status" = "409" ] || fail "removing a field a record type offers was not refused ($status): $(cat "$body")"
  grep -Fq 'record type Core__c of Deal__c offers field Deal__c.department__c' "$body" \
    || fail "the refusal does not name the record type: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"Drop","description":""}' \
    "$base/api/v1/metadata/change-sets")"
  [ "$status" = "201" ] || fail "starting a change set answered $status: $(cat "$body")"
  set_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  [ -n "$set_id" ] || fail "the change set has no identifier: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"kind":"DELETE_FIELD","objectApiName":"Deal__c","itemApiName":"department__c"}' \
    "$base/api/v1/metadata/change-sets/$set_id/changes")"
  [ "$status" = "201" ] || fail "adding a change answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/metadata/change-sets/$set_id/validate")"
  [ "$status" = "200" ] || fail "checking the change set answered $status: $(cat "$body")"
  grep -Fq '"valid":false' "$body" || fail "an invalid change set was reported valid: $(cat "$body")"
  grep -Fq 'record type Core__c of Deal__c' "$body" || fail "the report does not name the dependent: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/metadata/change-sets/$set_id/publish")"
  [ "$status" = "409" ] || fail "publishing an invalid change set answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Deal__c")"
  grep -Fq '"apiName":"department__c"' "$body" || fail "a refused publication removed the field: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/change-sets/$set_id")"
  [ "$status" = "204" ] || fail "discarding the change set answered $status"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"Extra","description":""}' \
    "$base/api/v1/metadata/change-sets")"
  set_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"kind":"CREATE_FIELD","objectApiName":"Deal__c","createField":{"name":"region","label":"Region","type":"TEXT"}}' \
    "$base/api/v1/metadata/change-sets/$set_id/changes")"
  [ "$status" = "201" ] || fail "adding the second change answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/metadata/change-sets/$set_id/publish")"
  [ "$status" = "200" ] || fail "publishing a good change set answered $status: $(cat "$body")"
  grep -Fq '"status":"PUBLISHED"' "$body" || fail "the published change set is not marked published: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/releases")"
  [ "$status" = "200" ] || fail "the history answered $status: $(cat "$body")"
  grep -Fq '"kind":"CHANGE_SET"' "$body" || fail "the history lacks the change set release: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/metadata/releases/latest/rollback")"
  [ "$status" = "200" ] || fail "rolling back answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Deal__c")"
  if grep -Fq '"apiName":"region__c"' "$body"; then
    fail "the rolled back field is still there"
  fi
  status="$(client_call "$base/api/v1/audit-events?kind=metadata&limit=200")"
  grep -Fq '"type":"metadata.publish.refused"' "$body" || fail "the refused publication is not in the trail: $(cat "$body")"
  grep -Fq '"type":"metadata.release.rolledback"' "$body" || fail "the rollback is not in the trail: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/record-types/Core__c")"
  [ "$status" = "204" ] || fail "removing the record type answered $status"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c")"
  [ "$status" = "204" ] || fail "removing the lifecycle object answered $status: $(cat "$body")"
  echo "ok: a field a record type offers cannot be removed (live or in a change set) and the error names the record type, a good change set publishes as one release and is rolled back, and the trail shows it"

  # 18. Layouts, record pages and applications on the image (Sprint 12): a layout is checked against its schema (the
  # refusal names the place), a record page and an application are made, the member's own menu comes from the API, a
  # layout that is shown cannot lose its field and the error names the layout, a record page keeps its layout, and the
  # default layout is chosen.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Deal","label":"Deal","pluralLabel":"Deals","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "creating the layout object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"department","label":"Department","type":"TEXT"}' \
    "$base/api/v1/metadata/objects/Deal__c/fields")"
  [ "$status" = "201" ] || fail "adding the layout field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Main","label":"Main","description":"","definition":{"sections":[{"label":"Main","fields":[{"field":"department__c","behaviour":"SHOUT"}]}]}}' \
    "$base/api/v1/metadata/objects/Deal__c/layouts")"
  [ "$status" = "400" ] || fail "a layout with a wrong behaviour answered $status: $(cat "$body")"
  grep -Fq 'definition.sections[0].fields[0].behaviour' "$body" || fail "the refusal does not name the place: $(cat "$body")"
  layout_json='{"name":"Main","label":"Main","description":"","definition":{"sections":[{"label":"Main","fields":[{"field":"department__c","behaviour":"REQUIRED"}]}]}}'
  status="$(client_call -X POST -H "$group_json" -d "$layout_json" "$base/api/v1/metadata/objects/Deal__c/layouts")"
  [ "$status" = "201" ] || fail "adding the layout answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Deal__c/layouts/Main__c")"
  [ "$status" = "200" ] || fail "reading the layout back answered $status: $(cat "$body")"
  grep -Fq '"behaviour":"REQUIRED"' "$body" || fail "the layout did not come back as it was sent: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Overview","label":"Overview","description":"","definition":{"layout":"Main__c","components":[{"key":"details","type":"DETAILS"}]}}' \
    "$base/api/v1/metadata/objects/Deal__c/record-pages")"
  [ "$status" = "201" ] || fail "adding the record page answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Sales","label":"Sales","description":"","definition":{"iconKey":"briefcase","items":[{"type":"OBJECT","object":"Deal__c"},{"type":"LINK","label":"Members","path":"/members"}]}}' \
    "$base/api/v1/metadata/applications")"
  [ "$status" = "201" ] || fail "adding the application answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Bad","label":"Bad","description":"","definition":{"items":[{"type":"LINK","label":"Away","path":"https://example.test"}]}}' \
    "$base/api/v1/metadata/applications")"
  [ "$status" = "400" ] || fail "a link outside the organization answered $status: $(cat "$body")"
  grep -Fq 'definition.items[0].path' "$body" || fail "the refusal does not name the link: $(cat "$body")"
  status="$(client_call "$base/api/v1/applications")"
  [ "$status" = "200" ] || fail "the member's menu answered $status: $(cat "$body")"
  grep -Fq '"apiName":"Sales__c"' "$body" || fail "the menu lacks the application: $(cat "$body")"
  grep -Fq '"path":"/members"' "$body" || fail "the menu lacks the link: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"layout":"Main__c"}' \
    "$base/api/v1/metadata/objects/Deal__c/layout-assignments")"
  [ "$status" = "201" ] || fail "choosing the default layout answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Deal__c/layout-resolution")"
  grep -Fq '"rule":"DEFAULT"' "$body" || fail "the default layout is not the answer: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/fields/department__c")"
  [ "$status" = "409" ] || fail "removing a field a layout shows was not refused ($status): $(cat "$body")"
  grep -Fq 'page layout Main__c of Deal__c shows field Deal__c.department__c' "$body" \
    || fail "the refusal does not name the layout: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/layouts/Main__c")"
  [ "$status" = "409" ] || fail "removing a layout a page uses was not refused ($status): $(cat "$body")"
  grep -Fq 'record page Overview__c of Deal__c shows the details of page layout Main__c' "$body" \
    || fail "the refusal does not name the record page: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/applications/Sales__c")"
  [ "$status" = "204" ] || fail "removing the application answered $status"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/layout-assignments/default")"
  [ "$status" = "204" ] || fail "removing the layout choice answered $status"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/record-pages/Overview__c")"
  [ "$status" = "204" ] || fail "removing the record page answered $status"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c/layouts/Main__c")"
  [ "$status" = "204" ] || fail "removing the layout answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Deal__c")"
  [ "$status" = "204" ] || fail "removing the layout object answered $status: $(cat "$body")"
  echo "ok: a layout is checked against its schema and read back, a record page and an application are made, the member's menu comes from the API, a link outside the organization is refused with its place, and a layout, a field and a record page that are still needed cannot be removed (the error names who needs them)"

  # 19. Formulas, validation rules and the expression engine on the image (Sprint 13): a formula is checked and refused
  # with its place, a field a formula uses cannot be removed and the error names the formula, a rule is made and tried on
  # sample values, a circle of formulas is refused naming the fields, and checking an expression answers with the place.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Invoice","label":"Invoice","pluralLabel":"Invoices","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "creating the invoice object answered $status: $(cat "$body")"
  for field in quantity:NUMBER unitPrice:CURRENCY status:TEXT; do
    status="$(client_call -X POST -H "$group_json" \
      -d "{\"name\":\"${field%%:*}\",\"label\":\"${field%%:*}\",\"type\":\"${field##*:}\"}" \
      "$base/api/v1/metadata/objects/Invoice__c/fields")"
    [ "$status" = "201" ] || fail "adding the field ${field%%:*} answered $status: $(cat "$body")"
  done
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"total","label":"Total","type":"FORMULA","settings":{"expression":"quantity__c *","resultType":"NUMBER"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/fields")"
  [ "$status" = "400" ] || fail "a formula with a mistake answered $status: $(cat "$body")"
  grep -Fq 'Line 1, column 14' "$body" || fail "the refusal does not name the place of the formula: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"total","label":"Total","type":"FORMULA","settings":{"expression":"ghost__c * 2","resultType":"NUMBER"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/fields")"
  [ "$status" = "409" ] || fail "a formula that names a missing field answered $status: $(cat "$body")"
  grep -Fq 'field Invoice__c.total__c uses field Invoice__c.ghost__c, which does not exist' "$body" \
    || fail "the refusal does not name the formula and the field: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"total","label":"Total","type":"FORMULA","settings":{"expression":"quantity__c * unitPrice__c","resultType":"CURRENCY"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/fields")"
  [ "$status" = "201" ] || fail "adding a good formula answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Invoice__c/fields/quantity__c")"
  [ "$status" = "409" ] || fail "removing a field a formula uses was not refused ($status): $(cat "$body")"
  grep -Fq 'field Invoice__c.total__c uses field Invoice__c.quantity__c' "$body" \
    || fail "the refusal does not name the formula: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"loop","label":"Loop","type":"FORMULA","settings":{"expression":"loop__c + 1","resultType":"NUMBER"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/fields")"
  [ "$status" = "409" ] || fail "a formula that uses itself answered $status: $(cat "$body")"
  grep -Fq 'use each other in a circle' "$body" || fail "the refusal does not name the circle: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"objectApiName":"Invoice__c","expression":"quantity__c * unitPrice__c","purpose":"FORMULA","resultType":"CURRENCY"}' \
    "$base/api/v1/metadata/expressions/check")"
  [ "$status" = "200" ] || fail "checking a formula answered $status: $(cat "$body")"
  grep -Fq '"valid":true' "$body" || fail "a good formula was not valid: $(cat "$body")"
  grep -Fq '"type":"NUMBER"' "$body" || fail "the check does not say the type: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"objectApiName":"Invoice__c","expression":"status__c * 2","purpose":"FORMULA"}' \
    "$base/api/v1/metadata/expressions/check")"
  grep -Fq '"valid":false' "$body" || fail "a formula that multiplies text was valid: $(cat "$body")"
  grep -Fq 'Line 1, column 11' "$body" || fail "the check does not say where it is wrong: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"NotAnAnswer","label":"Not an answer","expression":"quantity__c + 1","errorMessage":"No."}' \
    "$base/api/v1/metadata/objects/Invoice__c/validation-rules")"
  [ "$status" = "409" ] || fail "a rule that does not give true or false answered $status: $(cat "$body")"
  grep -Fq 'true or false' "$body" || fail "the refusal does not say what a rule must give: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"NoNegative","label":"No negatives","expression":"quantity__c < 0","errorMessage":"No negative quantities.","errorField":"quantity__c"}' \
    "$base/api/v1/metadata/objects/Invoice__c/validation-rules")"
  [ "$status" = "201" ] || fail "adding a validation rule answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"values":{"quantity__c":"-5"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/validation-rules/NoNegative__c/test")"
  [ "$status" = "200" ] || fail "trying the rule answered $status: $(cat "$body")"
  grep -Fq '"outcome":"REJECTED"' "$body" || fail "the rule did not reject a negative quantity: $(cat "$body")"
  grep -Fq 'No negative quantities.' "$body" || fail "the rejection lacks the message: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"values":{"quantity__c":"5"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/validation-rules/NoNegative__c/test")"
  grep -Fq '"outcome":"PASSED"' "$body" || fail "the rule did not pass a good quantity: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"values":{"quantity__c":"3","unitPrice__c":"2.50"}}' \
    "$base/api/v1/metadata/objects/Invoice__c/fields/total__c/test")"
  [ "$status" = "200" ] || fail "trying the formula answered $status: $(cat "$body")"
  grep -Fq '"value":"7.5"' "$body" || fail "the formula did not give 7.5: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Invoice__c/validation-rules/NoNegative__c")"
  [ "$status" = "204" ] || fail "removing the validation rule answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Invoice__c")"
  [ "$status" = "204" ] || fail "removing the invoice object answered $status: $(cat "$body")"
  echo "ok: a formula is checked and refused with its place, a field a formula uses cannot be removed (the error names the formula), a circle is refused naming the fields, a rule is made and tried on sample values, and the check says where an expression is wrong"

  # 20. Records on the image (Sprint 14): a record is created, read, changed with its version, a stale change is refused,
  # the record is deleted and restored, an object that has a record cannot be removed (the answer says so), and once the
  # record is removed for good the object can be removed.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Ticket","label":"Ticket","pluralLabel":"Tickets","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the ticket object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"title","label":"Title","type":"TEXT"}' \
    "$base/api/v1/metadata/objects/Ticket__c/fields")"
  [ "$status" = "201" ] || fail "adding the title field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"First ticket"}}' \
    "$base/api/v1/objects/Ticket__c/records")"
  [ "$status" = "201" ] || fail "creating a record answered $status: $(cat "$body")"
  record_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  [ -n "$record_id" ] || fail "the new record has no identifier: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "200" ] || fail "reading the record answered $status: $(cat "$body")"
  grep -Fq '"title__c":"First ticket"' "$body" || fail "the record lacks its value: $(cat "$body")"
  status="$(client_call -X PATCH -H "$group_json" -d '{"version":0,"fields":{"title__c":"Second title"}}' \
    "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "200" ] || fail "changing the record answered $status: $(cat "$body")"
  grep -Fq '"version":1' "$body" || fail "the change did not raise the version: $(cat "$body")"
  status="$(client_call -X PATCH -H "$group_json" -d '{"version":0,"fields":{"title__c":"Stale title"}}' \
    "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "409" ] || fail "a stale change answered $status: $(cat "$body")"
  grep -Fq 'CONCURRENT_MODIFICATION' "$body" || fail "the stale change was not named as such: $(cat "$body")"
  if grep -Fq 'Stale title' "$body"; then fail "the refusal repeats a value"; fi
  status="$(client_call -X DELETE "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "204" ] || fail "deleting the record answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "404" ] || fail "a deleted record was still readable ($status)"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/objects/Ticket__c/recycle-bin/$record_id/restore")"
  [ "$status" = "200" ] || fail "restoring the record answered $status: $(cat "$body")"
  grep -Fq '"title__c":"Second title"' "$body" || fail "the restored record lost its value: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Ticket__c")"
  [ "$status" = "409" ] || fail "removing an object with a record answered $status: $(cat "$body")"
  grep -Fq 'still has records' "$body" || fail "the refusal does not say why: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/objects/Ticket__c/records/$record_id")"
  [ "$status" = "204" ] || fail "deleting the record again answered $status"
  status="$(client_call -X DELETE "$base/api/v1/objects/Ticket__c/recycle-bin/$record_id")"
  [ "$status" = "204" ] || fail "removing the record for good answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Ticket__c")"
  [ "$status" = "204" ] || fail "removing the emptied object answered $status: $(cat "$body")"
  if owner_sql -c "select count(*) from object_record where payload::text like '%First ticket%' or payload::text like '%Second title%'" | grep -Eq '^ *[1-9]'; then
    fail "a removed record is still stored"
  fi
  echo "ok: a record is created, read, changed with its version, a stale change is refused, the record is deleted and restored, an object with a record cannot be removed, and once the record is removed for good the object can be removed"

  # 21. The write pipeline on the image (Sprint 15): a required field is enforced, a typed value is refused with its place,
  # a validation rule rejects a record with its message, a formula is calculated and stored on write, an auto-number
  # counts, and nobody can write a calculated value. (A member without permission on a field is refused: the integration
  # tests prove it for all 19 types; this script holds one organization with one administrator.)
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Quote","label":"Quote","pluralLabel":"Quotes","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the quote object answered $status: $(cat "$body")"
  for field in \
    '{"name":"title","label":"Title","type":"TEXT","required":true,"settings":{"maxLength":10}}' \
    '{"name":"amount","label":"Amount","type":"NUMBER","settings":{"digits":6}}' \
    '{"name":"tax","label":"Tax","type":"FORMULA","settings":{"expression":"amount__c * 2","resultType":"NUMBER"}}' \
    '{"name":"number","label":"Number","type":"AUTO_NUMBER","settings":{"prefix":"Q-","startAt":1,"width":4}}'; do
    status="$(client_call -X POST -H "$group_json" -d "$field" "$base/api/v1/metadata/objects/Quote__c/fields")"
    [ "$status" = "201" ] || fail "adding a quote field answered $status: $(cat "$body")"
  done
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"NoZero","label":"No zero","expression":"amount__c = 0","errorMessage":"Amount must not be zero.","errorField":"amount__c"}' \
    "$base/api/v1/metadata/objects/Quote__c/validation-rules")"
  [ "$status" = "201" ] || fail "making the quote rule answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"amount__c":5}}' "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "400" ] || fail "a record without its required title answered $status: $(cat "$body")"
  grep -Fq '"title__c":["A value is required."]' "$body" || fail "the refusal does not name the title: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"far too long a title"}}' \
    "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "400" ] || fail "a title that is too long answered $status: $(cat "$body")"
  grep -Fq '"title__c"' "$body" || fail "the refusal does not name the title: $(cat "$body")"
  if grep -Fq 'far too long' "$body"; then fail "the refusal repeats the value"; fi
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"zero","amount__c":0}}' \
    "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "400" ] || fail "a record the rule rejects answered $status: $(cat "$body")"
  grep -Fq 'Amount must not be zero.' "$body" || fail "the rejection lacks the rule's message: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"calculated","tax__c":1}}' \
    "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "400" ] || fail "writing a formula answered $status: $(cat "$body")"
  grep -Fq 'cannot be written' "$body" || fail "writing a formula was not refused as such: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"first","amount__c":5}}' \
    "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "201" ] || fail "creating a good quote answered $status: $(cat "$body")"
  grep -Fq '"tax__c":10' "$body" || fail "the formula was not calculated on write: $(cat "$body")"
  grep -Fq '"number__c":"Q-0001"' "$body" || fail "the auto-number did not start: $(cat "$body")"
  record_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"second","amount__c":7}}' \
    "$base/api/v1/objects/Quote__c/records")"
  [ "$status" = "201" ] || fail "creating a second quote answered $status: $(cat "$body")"
  grep -Fq '"number__c":"Q-0002"' "$body" || fail "the auto-number did not count: $(cat "$body")"
  second_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X PATCH -H "$group_json" -d '{"version":0,"fields":{"amount__c":9}}' \
    "$base/api/v1/objects/Quote__c/records/$record_id")"
  [ "$status" = "200" ] || fail "changing a quote answered $status: $(cat "$body")"
  grep -Fq '"tax__c":18' "$body" || fail "the formula was not recalculated on update: $(cat "$body")"
  grep -Fq '"number__c":"Q-0001"' "$body" || fail "an update changed the auto-number: $(cat "$body")"
  status="$(client_call -X PATCH -H "$group_json" -d '{"version":1,"clear":["title__c"]}' \
    "$base/api/v1/objects/Quote__c/records/$record_id")"
  [ "$status" = "400" ] || fail "clearing a required field answered $status: $(cat "$body")"
  for id in "$record_id" "$second_id"; do
    status="$(client_call -X DELETE "$base/api/v1/objects/Quote__c/records/$id")"
    [ "$status" = "204" ] || fail "deleting a quote answered $status: $(cat "$body")"
    status="$(client_call -X DELETE "$base/api/v1/objects/Quote__c/recycle-bin/$id")"
    [ "$status" = "204" ] || fail "removing a quote for good answered $status: $(cat "$body")"
  done
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Quote__c/validation-rules/NoZero__c")"
  [ "$status" = "204" ] || fail "removing the quote rule answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Quote__c")"
  [ "$status" = "204" ] || fail "removing the emptied quote object answered $status: $(cat "$body")"
  echo "ok: a required field is enforced, a typed value is refused with its place and without being repeated, a validation rule rejects with its message, a formula is calculated on create and update, an auto-number counts and stays put on update, a calculated value cannot be written, and clearing a required field is refused"

  # 22. Record type assignments on the image (Sprint 15A): a record type is open to every profile until a profile is
  # assigned, the assignment is listed with its profile, a record type that has assignments cannot be removed (the
  # refusal says why), and once the assignment is removed the type can go.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Pipeline","label":"Pipeline","pluralLabel":"Pipelines","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the pipeline object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"Renewal","label":"Renewal"}' \
    "$base/api/v1/metadata/objects/Pipeline__c/record-types")"
  [ "$status" = "201" ] || fail "making the record type answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c/profiles")"
  [ "$status" = "200" ] || fail "listing the profiles of a record type answered $status: $(cat "$body")"
  grep -Fq '"data":[]' "$body" || fail "a record type nobody is assigned should list nobody (open to every profile): $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "{\"profileId\":\"$profile_id\",\"defaultForProfile\":true}" \
    "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c/profiles")"
  [ "$status" = "201" ] || fail "assigning a profile to the record type answered $status: $(cat "$body")"
  grep -Fq "\"key\":\"Renewal__c~$profile_id\"" "$body" || fail "the assignment lacks its key: $(cat "$body")"
  grep -Fq '"defaultForProfile":true' "$body" || fail "the assignment is not the default of the profile: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "{\"profileId\":\"$profile_id\"}" \
    "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c/profiles")"
  [ "$status" = "400" ] || fail "assigning the same profile twice answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c")"
  [ "$status" = "409" ] || fail "removing a record type that has assignments answered $status: $(cat "$body")"
  grep -Fq 'record type assignment' "$body" || fail "the refusal does not name the assignment: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c/profiles/$profile_id")"
  [ "$status" = "204" ] || fail "removing the assignment answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Pipeline__c/record-types/Renewal__c")"
  [ "$status" = "204" ] || fail "removing the record type answered $status: $(cat "$body")"
  status="$(client_call -X DELETE "$base/api/v1/metadata/objects/Pipeline__c")"
  [ "$status" = "204" ] || fail "removing the pipeline object answered $status: $(cat "$body")"
  echo "ok: a record type is open to every profile until one is assigned, an assignment is listed with its key and default, the same profile cannot be assigned twice, a record type that has assignments cannot be removed (the answer names them), and once the assignment is removed the type can be removed"

  stored="$(owner_sql -c "select t::text from account_token t union all select t::text from mail_queue t \
    union all select attributes::text from audit_record union all select t::text from invitation t")"
  if echo "$stored" | grep -q "$client_token"; then
    fail "a first-administrator token was stored in clear"
  fi
  if echo "$stored" | grep -q "$client_password"; then
    fail "a client password reached a table"
  fi
  if owner_sql -c "select attributes::text from audit_record" | grep -q "$client_admin"; then
    fail "the first administrator's address reached the audit table"
  fi
  echo "ok: no first-administrator token, password or address is stored where it must not be"

  # 23. The query engine, the index policy, delete behaviours and the limits on the image (Sprint 16): a query
  # filters, sorts and pages; a flagged field becomes indexed (the background fill finishes) and the answer does not
  # change; a master with a detail shows the delete behaviour; an organization that floods the server gets answers of
  # the model. (The refusal of a filter on a large object without an index needs more than 20,000 records and is
  # proved by QueryEndpointIT.)
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Ledger","label":"Ledger","pluralLabel":"Ledgers","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the ledger object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"code","label":"code","type":"TEXT"}' \
    "$base/api/v1/metadata/objects/Ledger__c/fields")"
  [ "$status" = "201" ] || fail "making the code field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"amount","label":"amount","type":"NUMBER"}' \
    "$base/api/v1/metadata/objects/Ledger__c/fields")"
  [ "$status" = "201" ] || fail "making the amount field answered $status: $(cat "$body")"
  for n in 1 2 3 4 5; do
    status="$(client_call -X POST -H "$group_json" -d "{\"fields\":{\"code__c\":\"c$n\",\"amount__c\":$((n * 10))}}" \
      "$base/api/v1/objects/Ledger__c/records")"
    [ "$status" = "201" ] || fail "creating a ledger record answered $status: $(cat "$body")"
  done
  query='{"filter":{"op":"GTE","field":"amount__c","values":[20]},"sort":[{"field":"code__c","descending":true}],"limit":2}'
  status="$(client_call -X POST -H "$group_json" -d "$query" "$base/api/v1/objects/Ledger__c/records/query")"
  [ "$status" = "200" ] || fail "a query answered $status: $(cat "$body")"
  before="$(grep -o '"code__c":"[^"]*"' "$body" | tr '\n' ' ' || true)"
  [ -n "$before" ] || fail "the first query returned no code values: $(cat "$body")"
  grep -Fq '"hasMore":true' "$body" || fail "the first page does not say there is more: $(cat "$body")"
  cursor="$(grep -o '"nextCursor":"[^"]*"' "$body" | cut -d'"' -f4 || true)"
  [ -n "$cursor" ] || fail "the first page has no cursor"
  status="$(client_call -X POST -H "$group_json" -d "${query%\}},\"cursor\":\"$cursor\"}" \
    "$base/api/v1/objects/Ledger__c/records/query")"
  [ "$status" = "200" ] || fail "the second page answered $status: $(cat "$body")"
  grep -Fq '"hasMore":false' "$body" || fail "the last page says there is more: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"filter":{"op":"EQ","field":"nothing__c","values":["x"]}}' \
    "$base/api/v1/objects/Ledger__c/records/query")"
  [ "$status" = "400" ] || fail "a query on a field that does not exist answered $status: $(cat "$body")"
  grep -Fq 'This is not a field of the object.' "$body" || fail "the refusal lacks its words: $(cat "$body")"
  for field in code amount; do
    status="$(client_call -X PUT -H "$group_json" \
      -d "{\"label\":\"$field\",\"description\":\"\",\"indexed\":true,\"version\":0}" \
      "$base/api/v1/metadata/objects/Ledger__c/fields/${field}__c")"
    [ "$status" = "200" ] || fail "flagging $field as indexed answered $status: $(cat "$body")"
  done
  filled=""
  for attempt in $(seq 1 60); do
    status="$(client_call "$base/api/v1/objects/Ledger__c/index-status")"
    [ "$status" = "200" ] || fail "the index status answered $status: $(cat "$body")"
    if [ "$(grep -o '"state":"FILLED"' "$body" | wc -l)" = "2" ]; then filled=yes; break; fi
    sleep 2
  done
  [ -n "$filled" ] || fail "the indexes did not become complete: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d "$query" "$base/api/v1/objects/Ledger__c/records/query")"
  [ "$status" = "200" ] || fail "the same query after indexing answered $status: $(cat "$body")"
  after="$(grep -o '"code__c":"[^"]*"' "$body" | tr '\n' ' ' || true)"
  [ "$before" = "$after" ] || fail "indexing changed the answer: $before became $after"
  # A master with a detail: removing the master removes the detail with it, and restoring brings it back.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Folder","label":"Folder","pluralLabel":"Folders","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the folder object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Sheet","label":"Sheet","pluralLabel":"Sheets","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the sheet object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"folder","label":"folder","type":"MASTER_DETAIL","settings":{"targetObject":"Folder__c"}}' \
    "$base/api/v1/metadata/objects/Sheet__c/fields")"
  [ "$status" = "201" ] || fail "making the master-detail answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{}}' "$base/api/v1/objects/Folder__c/records")"
  [ "$status" = "201" ] || fail "creating a folder answered $status: $(cat "$body")"
  folder_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" -d "{\"fields\":{\"folder__c\":\"$folder_id\"}}" \
    "$base/api/v1/objects/Sheet__c/records")"
  [ "$status" = "201" ] || fail "creating a sheet answered $status: $(cat "$body")"
  sheet_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X DELETE "$base/api/v1/objects/Folder__c/records/$folder_id")"
  [ "$status" = "204" ] || fail "removing a folder with a sheet answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Sheet__c/records/$sheet_id")"
  [ "$status" = "404" ] || fail "the sheet is still there after its folder was removed ($status)"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/objects/Folder__c/recycle-bin/$folder_id/restore")"
  [ "$status" = "200" ] || fail "restoring the folder answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Sheet__c/records/$sheet_id")"
  [ "$status" = "200" ] || fail "the sheet did not come back with its folder ($status)"
  # An organization that floods the server: forty requests at once; each answer is 200 or the 429 of the limit.
  flood_dir="$(mktemp -d)"
  for n in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}\n' -H "Host: $client_host" -H "Authorization: Bearer $client_access" \
      -H 'Content-Type: application/json' -d '{"limit":1}' "$base/api/v1/objects/Ledger__c/records/query" \
      > "$flood_dir/$n" &
  done
  wait
  codes="$(cat "$flood_dir"/* | sort | uniq -c | tr '\n' ' ')"
  rm -rf "$flood_dir"
  case "$codes" in *" 5"[0-9][0-9]*) fail "a flood produced a server error: $codes" ;; esac
  status="$(client_call -X POST -H "$group_json" -d '{"limit":1}' "$base/api/v1/objects/Ledger__c/records/query")"
  [ "$status" = "200" ] || fail "the organization does not answer again after the flood ($status)"
  echo "ok: a query filters, sorts and pages with a cursor, a field that does not exist is refused in the words of the platform, a flagged field became indexed and the answer did not change, removing a master removed its detail and restoring brought it back, and a flood of requests ended in answers of the model (counts: $codes)"

  # 24. Uniqueness and the recalculation of stored formula results on the image (Sprint 16A): a unique field is
  # checked in by the background pass and then refuses a duplicate in the words of the platform; the recycle bin does
  # not hold the value but a restore that would duplicate it is refused; editing a formula leaves the stored results
  # until the background pass has rewritten them, and the state endpoint says when they are current.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Badge","label":"Badge","pluralLabel":"Badges","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the badge object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"tag","label":"tag","type":"TEXT","unique":true}' \
    "$base/api/v1/metadata/objects/Badge__c/fields")"
  [ "$status" = "201" ] || fail "making the unique tag field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"size","label":"size","type":"NUMBER"}' \
    "$base/api/v1/metadata/objects/Badge__c/fields")"
  [ "$status" = "201" ] || fail "making the size field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"dbl","label":"dbl","type":"FORMULA","settings":{"expression":"size__c * 2","resultType":"NUMBER"}}' \
    "$base/api/v1/metadata/objects/Badge__c/fields")"
  [ "$status" = "201" ] || fail "making the formula field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"tag__c":"t1","size__c":3}}' \
    "$base/api/v1/objects/Badge__c/records")"
  [ "$status" = "201" ] || fail "creating the first badge answered $status: $(cat "$body")"
  badge_one="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  enforced=""
  for attempt in $(seq 1 60); do
    status="$(client_call "$base/api/v1/objects/Badge__c/index-status")"
    [ "$status" = "200" ] || fail "the index status of the badges answered $status: $(cat "$body")"
    if grep -Fq '"uniqueness":[{"field":"tag__c"' "$body" && grep -Fq '"state":"ENFORCED"' "$body"; then
      enforced=yes
      break
    fi
    sleep 2
  done
  [ -n "$enforced" ] || fail "the uniqueness of the tag did not become enforced: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"tag__c":"t1"}}' "$base/api/v1/objects/Badge__c/records")"
  [ "$status" = "400" ] || fail "a duplicate tag answered $status: $(cat "$body")"
  grep -Fq 'This value is already in use' "$body" || fail "the refusal lacks its words: $(cat "$body")"
  if grep -Fq "$badge_one" "$body"; then fail "the refusal of a duplicate names the other record: $(cat "$body")"; fi
  status="$(client_call -X DELETE "$base/api/v1/objects/Badge__c/records/$badge_one")"
  [ "$status" = "204" ] || fail "removing the first badge answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"tag__c":"t1","size__c":4}}' \
    "$base/api/v1/objects/Badge__c/records")"
  [ "$status" = "201" ] || fail "the recycle bin held the tag: a new badge with it answered $status: $(cat "$body")"
  badge_two="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/objects/Badge__c/recycle-bin/$badge_one/restore")"
  [ "$status" = "409" ] || fail "a restore that would duplicate the tag answered $status: $(cat "$body")"
  grep -Fq 'cannot be brought back' "$body" || fail "the refusal of the restore lacks its words: $(cat "$body")"
  # Editing the formula: the results are rewritten by the background pass, and the state says when they are current.
  status="$(client_call -X PUT -H "$group_json" \
    -d '{"label":"dbl","description":"","settings":{"expression":"size__c * 3","resultType":"NUMBER"},"version":0}' \
    "$base/api/v1/metadata/objects/Badge__c/fields/dbl__c")"
  [ "$status" = "200" ] || fail "editing the formula answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"filter":{"op":"GT","field":"dbl__c","values":[1]}}' \
    "$base/api/v1/objects/Badge__c/records/query")"
  if [ "$status" = "400" ]; then
    grep -Fq 'brought up to date' "$body" || fail "a filter on a formula whose results are old lacks its words: $(cat "$body")"
  fi
  status="$(client_call -X POST -H "$group_json" -d '{}' "$base/api/v1/objects/Badge__c/formula-recalculation")"
  [ "$status" = "202" ] || fail "starting the recalculation answered $status: $(cat "$body")"
  current=""
  for attempt in $(seq 1 90); do
    status="$(client_call "$base/api/v1/objects/Badge__c/formula-recalculation")"
    [ "$status" = "200" ] || fail "the state of the recalculation answered $status: $(cat "$body")"
    if grep -Fq '"state":"CURRENT"' "$body"; then current=yes; break; fi
    sleep 2
  done
  [ -n "$current" ] || fail "the stored results did not become current: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Badge__c/records/$badge_two")"
  [ "$status" = "200" ] || fail "reading the second badge answered $status: $(cat "$body")"
  grep -Eq '"dbl__c":12(\.0+)?[,}]' "$body" || fail "the stored result was not rewritten by the pass (4 * 3 = 12): $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"filter":{"op":"GT","field":"dbl__c","values":[1]}}' \
    "$base/api/v1/objects/Badge__c/records/query")"
  [ "$status" = "200" ] || fail "a filter on the formula answered $status once the results are current: $(cat "$body")"
  echo "ok: a unique field was checked in and refuses a duplicate in the words of the platform without naming the other record, the recycle bin did not hold the value and a restore that would duplicate it was refused, and after the formula was edited the background pass rewrote the stored result and the state became current"

  # 25. Record-level security, files, bulk, import and search on the image (Sprint 17): an object is made Private
  # (the owner still reads; the setting reads back), a file is stored in the object store, comes back byte for byte as an
  # attachment and is gone after it is deleted, a bulk call saves what is good and says which record was refused, an
  # import upserts by an external identifier (twice, with nothing new the second time), and a search finds a word of a
  # searchable field once its words are complete and never echoes a value in its refusal.
  status="$(client_call -X POST -H "$group_json" \
    -d '{"name":"Lead","label":"Lead","pluralLabel":"Leads","description":""}' "$base/api/v1/metadata/objects")"
  [ "$status" = "201" ] || fail "making the lead object answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"title","label":"title","type":"TEXT","searchable":true}' \
    "$base/api/v1/metadata/objects/Lead__c/fields")"
  [ "$status" = "201" ] || fail "making the searchable title field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"ref","label":"ref","type":"TEXT","externalId":true}' \
    "$base/api/v1/metadata/objects/Lead__c/fields")"
  [ "$status" = "201" ] || fail "making the external identifier field answered $status: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"name":"qty","label":"qty","type":"NUMBER"}' \
    "$base/api/v1/metadata/objects/Lead__c/fields")"
  [ "$status" = "201" ] || fail "making the quantity field answered $status: $(cat "$body")"
  # The organization-wide default: unset is Public Read/Write; setting it to Private reads back; the owner still reads.
  status="$(client_call "$base/api/v1/sharing/objects/Lead__c")"
  [ "$status" = "200" ] || fail "reading the sharing of the lead answered $status: $(cat "$body")"
  grep -Fq '"defaultAccess":"PUBLIC_READ_WRITE"' "$body" || fail "an unset object is not Public Read/Write: $(cat "$body")"
  status="$(client_call -X PUT -H "$group_json" -d '{"defaultAccess":"PRIVATE","grantViaHierarchy":true}' \
    "$base/api/v1/sharing/objects/Lead__c")"
  [ "$status" = "200" ] || fail "making the lead Private answered $status: $(cat "$body")"
  grep -Fq '"defaultAccess":"PRIVATE"' "$body" || fail "the Private default did not read back: $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"fields":{"title__c":"Northwind harvest","ref__c":"L-1"}}' \
    "$base/api/v1/objects/Lead__c/records")"
  [ "$status" = "201" ] || fail "creating a lead answered $status: $(cat "$body")"
  lead_one="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call "$base/api/v1/objects/Lead__c/records/$lead_one")"
  [ "$status" = "200" ] || fail "the owner does not read their own lead on a Private object ($status)"
  # A file: stored, handed back byte for byte as an attachment, and gone once deleted.
  file_in="$(mktemp)"
  head -c 5000 /dev/urandom > "$file_in"
  printf 'PK' | dd of="$file_in" conv=notrunc 2>/dev/null
  status="$(client_call -X POST -H 'Content-Type: application/zip' -H 'X-File-Name: quote.zip' \
    --data-binary "@$file_in" "$base/api/v1/objects/Lead__c/records/$lead_one/files")"
  [ "$status" = "201" ] || fail "storing a file answered $status: $(cat "$body")"
  file_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
  status="$(client_call "$base/api/v1/objects/Lead__c/records/$lead_one/files/$file_id/content")"
  [ "$status" = "200" ] || fail "downloading the file answered $status"
  cmp -s "$file_in" "$body" || fail "the downloaded file differs from the one stored"
  grep -iq '^content-disposition: attachment' "$headers" || fail "the download is not an attachment"
  grep -iq '^x-content-type-options: nosniff' "$headers" || fail "the download lacks nosniff"
  status="$(client_call -X DELETE "$base/api/v1/objects/Lead__c/records/$lead_one/files/$file_id")"
  [ "$status" = "204" ] || fail "deleting the file answered $status: $(cat "$body")"
  status="$(client_call "$base/api/v1/objects/Lead__c/records/$lead_one/files/$file_id/content")"
  [ "$status" = "404" ] || fail "the file is still there after it was deleted ($status)"
  # A bulk call: two good records and one refused, each with its own outcome.
  status="$(client_call -X POST -H "$group_json" -d '{"operation":"CREATE","allOrNone":false,"records":[
    {"fields":{"title__c":"bulk one","ref__c":"B-1"}},{"fields":{"qty__c":"not a number"}},
    {"fields":{"title__c":"bulk two","ref__c":"B-2"}}]}' "$base/api/v1/objects/Lead__c/records/bulk")"
  [ "$status" = "200" ] || fail "a bulk call answered $status: $(cat "$body")"
  grep -Fq '"succeeded":2' "$body" || fail "the bulk call did not save the two good records: $(cat "$body")"
  grep -Fq '"failed":1' "$body" || fail "the bulk call did not refuse the bad record: $(cat "$body")"
  if grep -Fq 'not a number' "$body"; then fail "the bulk refusal repeats the value: $(cat "$body")"; fi
  # An import upserts by the external identifier: L-1 is updated, I-1 is made; the same file again changes nothing.
  import_csv="$(mktemp)"
  printf 'ref__c,title__c\nL-1,Renamed lead\nI-1,Imported lead\n' > "$import_csv"
  for round in 1 2; do
    status="$(client_call -X POST -H "$group_json" -d '{"operation":"UPSERT","externalIdField":"ref__c"}' \
      "$base/api/v1/objects/Lead__c/imports")"
    [ "$status" = "201" ] || fail "starting import $round answered $status: $(cat "$body")"
    import_id="$(grep -o '"id":"[^"]*"' "$body" | head -1 | cut -d'"' -f4)"
    status="$(client_call -X PUT -H 'Content-Type: text/csv' --data-binary "@$import_csv" \
      "$base/api/v1/objects/Lead__c/imports/$import_id/file")"
    [ "$status" = "202" ] || fail "sending the file of import $round answered $status: $(cat "$body")"
    finished=""
    for attempt in $(seq 1 60); do
      status="$(client_call "$base/api/v1/objects/Lead__c/imports/$import_id")"
      [ "$status" = "200" ] || fail "reading import $round answered $status: $(cat "$body")"
      if grep -Fq '"state":"COMPLETED"' "$body"; then finished=yes; break; fi
      if grep -Fq '"state":"FAILED"' "$body"; then fail "import $round failed: $(cat "$body")"; fi
      sleep 2
    done
    [ -n "$finished" ] || fail "import $round did not finish: $(cat "$body")"
    if [ "$round" = "1" ]; then
      grep -Fq '"createdRows":1' "$body" && grep -Fq '"updatedRows":1' "$body" \
        || fail "the first import did not make one record and change one: $(cat "$body")"
    else
      grep -Fq '"createdRows":0' "$body" || fail "the second import made a record again: $(cat "$body")"
    fi
  done
  # Search: the words of the searchable field are complete after the background fill; a refusal never echoes the text.
  found=""
  for attempt in $(seq 1 60); do
    status="$(client_call -X POST -H "$group_json" -d '{"text":"imported"}' "$base/api/v1/objects/Lead__c/records/search")"
    if [ "$status" = "200" ] && grep -Fq 'Imported lead' "$body"; then found=yes; break; fi
    sleep 2
  done
  [ -n "$found" ] || fail "the search did not find the imported lead ($status): $(cat "$body")"
  status="$(client_call -X POST -H "$group_json" -d '{"text":"!!! ???"}' "$base/api/v1/objects/Lead__c/records/search")"
  [ "$status" = "400" ] || fail "a search without a word answered $status: $(cat "$body")"
  if grep -Fq '???' "$body"; then fail "the refusal of a search repeats the text: $(cat "$body")"; fi
  rm -f "$file_in" "$import_csv"
  echo "ok: an object made Private still shows the owner their record, a file was stored, handed back byte for byte as an attachment and removed, a bulk call saved two records and refused one without repeating its value, an import upserted by the external identifier and a second run made nothing new, and a search found the imported record once the words were complete"
fi
