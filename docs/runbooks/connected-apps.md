# Runbook: connected applications

For the person who operates the platform and for an organization's administrators. What a customer does with an application
is in [../public-api.md](../public-api.md) (section 2a); the decisions are in ADR-0267 to ADR-0273.

## Settings

| Setting (environment variable) | Default | What it does |
|---|---|---|
| `platform.identity.clients.enabled` (`PLATFORM_CLIENTS_ENABLED`) | `true` | `false` is the kill switch: the token endpoint refuses client credentials with the same `invalid_client` as a wrong secret, the management endpoints (`/api/v1/connected-apps`) answer `404`, and tokens already issued stop working at their next request. Nothing is lost: registrations and secrets stay |
| `platform.identity.clients.tokens.default-life`, `.min`, `.max` | `15m`, `5m`, `60m` | the life of a token when none is chosen, and the range an administrator may choose from (the database holds 5 to 60 minutes whatever this says) |
| `platform.identity.clients.max-active-secrets` | `3` | how many secrets of one application may work at once (a rotation with an overlap needs two) |
| `platform.identity.clients.max-overlap` | `24h` | the longest overlap of an old secret after a rotation (the database holds 24 hours) |
| `platform.identity.clients.max-per-organization` | `50` | how many applications one organization may have |
| `platform.identity.clients.failures.per-source`, `.source-window` | `20`, `10m` | failed authentications allowed from one source address |
| `platform.identity.clients.failures.per-client`, `.client-window` | `10`, `1m` | failed authentications allowed for one client identifier, from any source |
| `platform.identity.clients.last-used-interval` | `1m` | the shortest time between two writes of "last used" for one application |
| `platform.identity.clients.kept-ended-secrets` | `10` | how many ended secrets the list of an application still shows |

Every setting has a safe default; the `local` profile starts unchanged. The plan feature `integrations` must be on for the
organization (it also gates credentials and connections); the abilities are `clients.view` and `clients.manage`.

## Switching it off, and back

Set `PLATFORM_CLIENTS_ENABLED=false` and restart (or roll) the instances. Applications can no longer get or use tokens and
their administrators see `404` on the screens. The person-token door of the public API is not touched. Switch it on again
the same way. Use this when a defect in the machine path must be stopped faster than a fix can be shipped. To stop **one**
organization's applications, switch the plan feature `integrations` off for that organization in the console: the
management endpoints refuse, the token endpoint refuses and live tokens stop at the next request.

## Reading it

Metrics (micrometer): the only tags are a result or a closed reason; **a client identifier is never a tag.**

- `platform.clients.tokens{result}`: `issued`, or the OAuth error code of a refused request (`invalid_scope` and so on).
- `platform.clients.auth.failures{reason}`: `unknown_client`, `wrong_secret`, `disabled`, `member_not_active`, `feature_off`,
  `switched_off`, `limited`. A rise in `wrong_secret` or `unknown_client` from many sources is guessing; a rise in `limited` means
  the limits are working; a steady `member_not_active` or `disabled` is a customer's integration that someone forgot.
- The log line "request completed" of a call made through an application carries `client_id` (the public identifier, not a
  secret), with the request ID and the organization as everywhere.

Nothing a caller typed, no secret, no token and no hash is ever logged, audited or used as a metric tag.

### Reading the audit trail

In the audit viewer an organization's administrator sees, with the ability `audit.view`:

- **Management:** `clients.created`, `clients.updated`, `clients.secret_created`, `clients.secret_rotated`,
  `clients.secret_revoked`, `clients.disabled`, `clients.enabled`, `clients.removed`, by the application's public identifier and
  the member who did it, with counts (for example the number of tokens ended). Never a name, a description or a secret.
- **Refusals:** `clients.token_refused` with the true reason (`wrong_secret`, `disabled`, `member_not_active`, `feature_off`),
  at most once for each application, reason and window. The caller never learns the reason.
- **Changes made through an application:** every audit record of a change carries the attribute **`via_client`** with the
  application's public identifier next to the member. To answer "who changed this record": read the record's audit entry,
  take `via_client`, and find the application in Setup, Connected applications by that identifier. No `via_client` means the
  member did it themselves.

## When a secret leaks

1. In Setup, Connected applications, open the application and **make a new secret** with "end its tokens now" ticked and no
   overlap. The old secret stops at once and the tokens it made stop with it. (If the software must keep running while the
   new secret is installed, choose a short overlap instead and end the old secret by hand as soon as the change is done.)
2. Give the new secret to the software through its secret store. It is shown once.
3. If the application may have been misused, read the audit trail for `via_client` with its identifier, and review what it
   changed. If in doubt, **switch the application off** first (its tokens end now), investigate, then rotate and switch it on.
4. A secret that was only pasted into a ticket or a chat counts as leaked.

## When a member leaves, is suspended or is deactivated

Every application they registered **stops at the next request** and is listed as "stopped: its member is no longer active".
Nothing is transferred automatically and nothing needs doing to stop it. To keep the integration running: have an
administrator create a **dedicated integration member** (an ordinary, licensed member with an access policy that gives only
what the integration needs), sign in as that member (or give a person that member's access through your usual process),
register a new application as it, give the software the new identifier and secret, then remove the stopped application. A
password change or "sign out everywhere" by a person does **not** stop an application they registered.

## Why a customer cannot get a token

The token endpoint gives one answer, `401 invalid_client`, for every cause, so the customer cannot be told which. Check, in
this order: the application exists on **this organization's** address and is on; its secret is the one that works (look at the
states and the last-used times of its secrets); its member is an active, licensed member; the organization has the plan
feature; the kill switch is not off; `clients.token_refused` in the audit trail names the true reason. A `429` is the failed-attempt
limit: wait `Retry-After` seconds.

## Where the data is

`oauth_client` and `oauth_client_secret` (migration V095): tenant-scoped with forced row level security, no system scope, no
delete (a removed application is marked and kept), secrets as hashes only. Grants are rows of the existing
`oauth2_authorization` table with `oauth_client_id` and `client_secret_id` set. Ended grants are removed by the existing
identity clean-up.
