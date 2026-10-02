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
# (twice does not), and the invited person signs in on the organization's host. The owner's role name can be set
# with OWNER_USER.
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
  status="$(call -X POST -H "Authorization: Bearer $founder_token" -H 'Content-Type: application/json' \
    -d '{"displayName":"Smoke Organization","slug":"smoke-org"}' "$base/api/v1/organizations")"
  [ "$status" = "201" ] || fail "founding an organization answered $status: $(cat "$body")"
  org_host="smoke-org.$host"
  org_token="$(sign_in_token "$org_host" "$address" "$password")"
  [ -n "$org_token" ] || fail "the founder could not sign in on the organization's host"
  echo "ok: a person founds an organization and signs in on its host as its member"

  invitee="smoke-invitee-$(date +%s)@example.test"
  invitee_password="a-long-invited-passphrase-$RANDOM-$RANDOM"
  status="$(call_host="$org_host" call -X POST -H "Authorization: Bearer $org_token" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$invitee\",\"administrator\":true}" "$base/api/v1/invitations")"
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
  accept="{\"token\":\"$invite_token\",\"displayName\":\"Invited Person\",\"password\":\"$invitee_password\"}"
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
  [ "$(owner_sql -c "select count(*) from membership m join tenant t on t.id = m.tenant_id where t.slug = 'smoke-org'")" = "2" ] \
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
fi
