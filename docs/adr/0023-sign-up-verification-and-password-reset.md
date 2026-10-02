# ADR-0023: Sign-up, e-mail verification and password reset: address first, one answer for every address, one-time links

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S4
- **Stories:** S3-SEC-07 (uniformity for reset, verification and sign-up), S3-SEC-10 (notify the account owner), and the
  Sprint 4 stories of the delivery plan
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0020](0020-passwords-and-hashing.md),
  [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md),
  [ADR-0024](0024-mail-queue-and-notification-v0.md), [ADR-0025](0025-founding-an-organization-and-the-minimal-membership.md)

## Context

A sign-up form and a "forgot password" form are the two places where anybody, with no account and no session, can make
the platform do something, and both are attacked in the same ways as sign-in (ADR-0021): **probing** (which addresses
have an account, from the answer or from how long it takes), **flooding** (using the service to fill somebody's inbox),
and **take-over** (signing up with somebody else's address). Two details of the written story text made the first and the
last of these possible:

- *"Sign-up creates the identity, the tenant and the membership."* If a sign-up for an address that already has an
  account creates nothing, but a sign-up for a new address reserves an organization's web address, then trying the same
  web address a second time tells the attacker whether the first address was new. And if the form asks for a password,
  an attacker can sign up with a victim's address and a password of their own choosing; if the victim opens the mail they
  were sent, the account is the attacker's.
- Mail is sent over a network, so a request that waits for the mail server is slower for the addresses that get a mail.

The user's decision at the start of the sprint: a person who signs up gets **an individual account, not an organization**;
an organization is founded later, while signed in ([ADR-0025](0025-founding-an-organization-and-the-minimal-membership.md));
users that an administrator creates get a link to set their own password (Sprint 5, the same machinery).

## Decision

1. **Address first.** Sign-up asks only for an e-mail address. A link is mailed to it; opening the link shows a page where the
   person chooses their name and a password; completing it creates the account (`ACTIVE`, address verified). Nothing exists
   and nothing is reserved before the link is used, and nobody but the owner of the mailbox ever chooses anything about the
   account, so there is nothing to take over and no web address to probe.
2. **The request does identical work for every address** (S3-SEC-07). `POST /api/v1/auth/sign-up` and
   `POST /api/v1/auth/password/forgot` check the format of the address, count the request against the limits, write **one
   mail-queue row and one audit record**, and answer `202` with one fixed sentence. They **never look at the user table or
   the token table**; a unit test fails if a lookup is added (checked by adding one), and an integration test compares the
   status, headers and body for six states of an address (unknown, active, suspended, deactivated, invited, deleted) and
   watches the time. What is actually sent is decided later, by the mail relay, where nobody waits for it
   ([ADR-0024](0024-mail-queue-and-notification-v0.md)): for a sign-up, a link for an address without an account, a notice
   "you already have an account" for an active one, and nothing for any other state; for a reset, a link only for an active
   account. Submitting the same address again is the resend: a newer link replaces the older one.
3. **Links.** A token is 32 random bytes (URL-safe), **stored only as a SHA-256 hash** in `account_token`, created **at the
   moment the mail is sent** (so the queue never holds a secret), **single use**, expiring (**24 hours** for a sign-up,
   **60 minutes** for a reset), and a newer token of the same purpose for the same address cancels the older ones. Presenting
   a stored hash as if it were a token does not work (a test). The token travels in the part of the address after `#`,
   which a browser never sends to a server, so it cannot appear in an access log or a `Referer` header; the page reads it,
   removes it from the address bar and posts it in a request body. **Opening the link changes nothing**: only the button
   on the page uses the token, so mail scanners that open links cannot use one up.
4. **One answer for an unusable link.** An unknown, used, replaced, expired or wrong-purpose token, a link for an address
   that got an account in the meantime, and a reset link of an account that is no longer active are all the same
   `VALIDATION_ERROR` on the field `token` with one message (a test compares them byte for byte); the true reason goes to
   the audit record only. A password that breaks the policy is refused **inside the same transaction as the use of the token**,
   so the link stays usable for another try. Two simultaneous uses of one link have one winner (a test with eight threads).
5. **Reset** (ADR-0021 carry-over). Completing a reset is **never blocked by a lock**, clears the lock (storing a password
   does), **ends every session and token of the user** (the security version rises), cancels every other open reset link,
   and queues a "your password was changed" notice to the owner. A change of password through the signed-in endpoint also
   cancels open reset links.
6. **Limits** (counted in Redis with the per-instance fall-back of ADR-0021; they count every address alike, so a refusal
   (`429`, with `Retry-After`) reveals nothing about an account): **5 sign-up requests per hour per source**, **10 reset
   requests per hour per source**, **5 mails per hour per address for all kinds together, from any source** (nobody's inbox
   can be flooded through this API), and **20 attempts to complete a link per 10 minutes per source** (a token cannot be
   guessed, but guessing is not free). All of it is configuration (`platform.identity.account.*`).
7. **The lock notice** (carry-over from Sprint 3). When a failure locks an account, the owner is mailed at most **once per 24
   hours**; the check runs under the row lock of the credential, so two instances cannot both send it, and an address
   without an account is never mailed (it has no lock).
8. **Where.** The four endpoints answer only on the platform host (`NOT_FOUND` on an organization host); the pages live
   there and the links in mails point there. They are public paths: added to the explicit list in `SecurityConfiguration`
   and to `EndpointExposureIT` with a reason; they are protected against cross-site forgery like every state-changing call.
9. **Audit.** `auth.sign_up.requested`, `auth.password_reset.requested` (only a hash of the address), `auth.link.created`,
   `auth.link.refused` (with the true reason), `auth.sign_up.completed`, `auth.password_reset.completed`, plus the
   existing password and session events. No token, password or address is in any record (a test searches the table).
10. **Two tables, both platform-level** (a security decision, as ADR-0015 requires): `account_token` (V010). A sign-up token
    is issued for an address that has no user and no tenant, and a reset happens on the platform host, so the table cannot be
    tenant-scoped; it has no tenant column at all. It is added to `SchemaConventions.PLATFORM_TABLES`, which the schema test
    asserts exactly. `mail_queue` is decided in ADR-0024.

## Differences from the written story text (flagged to the user at the start of the sprint)

- Sign-up does not create the tenant or the membership; a signed-in person founds an organization in a separate step
  (ADR-0025). The Sprint 2 note "the first authenticated way to create a tenant" is honoured literally.
- There is no separate "resend verification" endpoint: asking again is the resend.
- "Try for free" has no trial period; a plan or trial attaches to an organization in Sprint 6.

## Consequences

- The sign-up page cannot tell a person that an address is taken. The person who already has an account gets a mail that
  says so, with the ways to sign in or reset; that is the price of not leaking accounts.
- **A persistent caller can keep a victim's reset mail from being queued**: five requests per hour for an address use up
  its mail allowance for the rest of that hour. The alternatives (a per-address allowance that never refuses, or none) are
  worse: the first makes the flood invisible, the second makes the inbox flood possible. The owner's other ways in are the
  next window and, for a lock, the notice.
- The mail of a sign-up for an address that is mid-way through an invitation (Sprint 5) is suppressed, not answered; Sprint
  5 decides what an invited person sees.
- Timing equality is by construction (the same statements run), proven by a unit test of the request path and a coarse
  timing test (known/unknown median ratio between 0.5 and 2). Both were checked to fail by adding a lookup and a delay.
- A reset does not sign the person in; they sign in with the new password.

## Alternatives considered

- **A password in the sign-up form, activation by the link** (the written story): the take-over above.
- **Create the user as `INVITED` at sign-up** and let the link activate it: needs a user row for every typed address
  (a lookup at request time, an observable difference, rows for addresses that never verify, and a place to clean up).
- **Reserve the organization's web address at sign-up**: the probe above, and squatting of good names.
- **Answer differently, with an honest "address taken"**: the common choice, and exactly the leak this ADR avoids.
- **Query-string tokens**: they reach access logs and `Referer` headers.
- **A CAPTCHA now**: needs a hosted third-party service; the limits, the mailbox proof and the fact that no tenant can exist
  without a proven mailbox are the protection until Sprint 33.

## References

- Migration: `platform-app/src/main/resources/db/migration/V010__create_account_token_table.sql`
- Code: `platform-app/src/main/java/app/platform/identity/` (`AccountTokens`, `internal/SignUpService`,
  `internal/PasswordResetService`, `internal/AccountController`, `internal/AccountLimiter`,
  `internal/AccountTokenRepository`), `platform-web/src/components/account/`, `platform-web/src/lib/account/`
- Tests: `SignUpFlowIT`, `PasswordResetFlowIT`, `AccountRequestsIT`, `AccountFlowsLogsAreCleanIT`,
  `SignUpAndResetRequestTest`, `AccountLimiterTest`, `EndpointExposureIT`, `SchemaConventionsIT`
