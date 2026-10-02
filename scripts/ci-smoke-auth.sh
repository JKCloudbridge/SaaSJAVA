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
# on the platform host, completing the link creates the account and the person signs in. The owner's role name can be set
# with OWNER_USER.
set -euo pipefail

db="${1:?database container name}"
redis="${2:?redis container name}"
redis_password="${3:?redis password}"
mail="${4:-}"
base="http://localhost:8080"
host="platform.example.test"

fail() {
  echo "FAILED: $1"
  exit 1
}

body="$(mktemp)"
headers="$(mktemp)"
call() { # <extra curl arguments...>: writes $body and $headers, prints the status code
  curl -s -o "$body" -D "$headers" -w '%{http_code}' -H "Host: $host" "$@"
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
fi
