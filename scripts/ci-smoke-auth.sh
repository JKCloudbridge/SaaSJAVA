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
  password="a-long-smoke-test-passphrase-$RANDOM-$RANDOM"
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
  invitee_password="a-long-invited-passphrase-$RANDOM-$RANDOM"
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
  client_password="a-long-client-passphrase-$RANDOM-$RANDOM"
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
fi
