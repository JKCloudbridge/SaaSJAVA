# ADR-0028: Invitations: a record of the organization, a one-time link, a membership only on acceptance

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S5
- **Related:** [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0023](0023-sign-up-verification-and-password-reset.md),
  [ADR-0024](0024-mail-queue-and-notification-v0.md), [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md)

## Context

An organization's administrators must be able to bring people in. The one-time link table of Sprint 4 (`account_token`) was built
for sign-up and reset: it has no organization column and the organization must never come from the browser. The invitation must
look the same to the administrator whether the address has an account or not (the rule of ADR-0023), must not create a user for an
address that may never answer, and must be reusable unchanged by Sprint 6, where a platform administrator invites a client's first
administrator.

## Decision

1. **The invitation is a record of the organization**: table `invitation`, **tenant-scoped** (tenant column, row level security
   enabled and forced, guard, tenant-first index, registered in the isolation harness). It holds the address, whether the person
   becomes an administrator, whether they become the founding administrator (only the platform path of Sprint 6 sets that), the
   expiry, a count of mails requested, and the result (accepted with the membership it created, or revoked). Status `OPEN`,
   `ACCEPTED`, `REVOKED` (an open invitation past its expiry is shown as expired); the database allows only `OPEN -> ACCEPTED` or
   `REVOKED` and makes a closed invitation read-only. One **open** invitation per address and organization (a partial unique
   index): inviting the same address again renews the open one, also when two administrators ask at once.
2. **The link resolves to the record on the server.** `account_token` gets a third purpose, `INVITATION`, and two columns:
   `context_tenant_id` and `invitation_id` (a check makes them required for this purpose and forbidden for the others). The
   token is created at send time by the notification module, like the others; creating one cancels the older links of the **same
   invitation** (not of the address: two organizations may invite one address). Everything else of ADR-0023 holds: 32 random bytes,
   only the hash is stored, single use, in the part of the link after `#`. **Lifetime 7 days** (setting, `7d`). The organization
   is therefore never in the request: not in a body, header, parameter or path of the accepting step.
3. **Asking for an invitation does identical work for every address**: a format check, the limits, one invitation row, one queue
   row and one audit record; no look at users or memberships (a unit test proves it and was checked to fail when a lookup is added).
   The answer is always `202` with one sentence. **What is mailed is decided later, at send time** (`Invitations.forMail`):
   an open invitation to an address with no account gets "choose a name and a password"; to an address with an active account that
   is not a member, "sign in with that account"; **nothing** for an address that is already a member (active or deactivated, for
   those the administrators have deactivate and reactivate), a suspended or closed account, or an invitation that is no longer
   open. So the administrator cannot find out who has an account. The cost is stated: an administrator sees that an invitation
   exists for an address they typed (they typed it).
4. **The mail says the organization's name, bounded.** The name is text an administrator chose, so in a message from the
   platform's own address it could mislead. It appears only in the body, reduced to letters, digits, spaces and a few quiet marks,
   at most 80 characters (nothing that can form a link or an address: no colon, slash, at-sign, dot; no line breaks), inside a fixed
   sentence and inside quotation marks; **the subject is fixed and holds no organization text**; the only link is the platform's
   own. The risk that remains (a name such as "Your bank" in quotes) is small, and stated.
5. **The invited person's steps** (all on the platform host, where the mailed link points):
   - *Preview* (public, the token is the proof): the organization's name, the address and whether it has an account. The holder of
     the link read the mail, so may know this.
   - *Accept as a new person* (public): name and password (the policy of ADR-0020 applies; a weak password leaves the link usable)
     create the account and the membership in one transaction.
   - *Accept as a person with an account* (signed in): only the person the invitation was sent to; anybody else gets exactly the
     answer of an unusable link and the link stays usable for the right person.
   Every unusable link (unknown, used, replaced, revoked, expired, organization closed, wrong person, the address became a member
   or got an account meanwhile) is one answer (`400`, field `token`, one message); the true reason is audited after the
   transaction ended. Acceptance uses the token first (a conditional update, so simultaneous uses have one winner), then locks the
   invitation, then checks everything again under the organization's context. Tests: six simultaneous acceptances, one winner.
6. **Limits** (Redis with per-instance fall-back, ADR-0021): per organization 20 invitations an hour, per administrator 10 an hour,
   per address the shared 5 mails an hour (the price is stated in ADR-0023: the allowance of an address can be used up by
   others), 20 token attempts per 10 minutes per source for preview and accept.
7. **Who may invite, list, send again, withdraw:** an administrator of the organization of the host
   ([ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md)). Invitations of another organization are not found
   (row level security): `404`, not `403`.
8. **For Sprint 6:** the platform administrator's "provision an organization and invite its first administrator" writes the same
   record (`founding_administrator = true`, `administrator = true`) and the same link; acceptance on a `PROVISIONING` organization is
   the one change Sprint 6 makes (today the organization must be `ACTIVE`).

## Consequences

- The address of an invitation is stored in a tenant-scoped table for as long as the row exists; retention of closed invitations
  is not decided (deferred to Sprint 9 with the audit retention).
- `account_token` stays platform-level; its new column is named `context_tenant_id` as the schema rule demands.

## Verification

`InvitationFlowIT` (23 tests: new and existing person, once, expiry, revoke, send again, wrong person, forged organization,
concurrency, enumeration with five address states byte-for-byte equal, timing guard, limits, outage), `MailTextsAndComposerTest`,
`MembershipGuardIT`, `MembershipFlowsLogsAreCleanIT` (no token, password or hash in any log, response, audit record, token table or
queue), `EndpointExposureIT`.
