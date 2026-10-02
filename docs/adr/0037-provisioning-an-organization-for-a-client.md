# ADR-0037: Provisioning an organization for a client and inviting its first administrator

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0023](0023-sign-up-verification-and-password-reset.md), [ADR-0024](0024-mail-queue-and-notification-v0.md),
  [ADR-0028](0028-invitations.md), [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md),
  [ADR-0032](0032-licences-pools-assignments-and-their-rules.md), [ADR-0038](0038-organization-lifecycle-by-platform-administrators.md)

## Context

Until now the only way to an organization was that its own first administrator signed up and founded it. A product owner who
onboards a client needs to set the organization up and hand it over, without the answer revealing whether the client's address
already has an account (ADR-0023) and without any default account or shared password.

## Decision

1. **The action.** A platform administrator gives a name, a short name, a plan and the address of the first administrator
   (`POST /api/v1/platform/organizations`). In **one transaction**, under the new organization's own context: the organization is
   created in the state `PROVISIONING`, the plan and its pools are attached, and an invitation is recorded with
   `founding_administrator = true`, `administrator = true` and a mark `invited_by_platform` (V019). Nothing else is created: no
   account, no membership, no licence is used.
2. **Closed until accepted.** A `PROVISIONING` organization's host answers "not available" (the tenant lifecycle already does).
   **Accepting the invitation opens it in the same transaction** that creates the membership (`InvitationLinkService` accepts a
   `PROVISIONING` organization, creates the account when needed, the membership with both markers, the default licence, and
   activates the organization): the organization is never open without its administrator. A closed (suspended or deactivated)
   organization cannot be accepted into.
3. **The same one-time link as every invitation** (opaque, hashed, single use, 7 days, after the `#`, created at send time by
   the notification module, never queued). Only the words differ: the relay chooses a **variant text** that names the
   organization (bounded by `MailTexts.safeName`, in the body only; **the subject is fixed** and holds no organization text) and
   says the person is invited "to set up and administer" it, for a new account ("choose your name and a password") and for an
   existing one ("sign in with that account"). No text typed by the platform administrator (a reason, a name of the platform
   person) goes into any mail.
4. **Uniform answers.** The request does **identical work for every address**: a format check, the limits (shared with
   invitations), one invitation row, one queue row, one audit record; it never looks at an account or a membership (a unit test
   guards it, `FirstAdministratorRequestTest`, and was checked to fail when a lookup is added). The answer to the platform
   administrator is the same whether the address has, had or never had an account; what is mailed (a link for a new account, a link
   for an existing one, nothing for a closed account) is decided later. The console shows the state of the invitation (open,
   expired, accepted, revoked, how many mails) but **never the address**.
5. **When nobody accepts.** The platform administrator (or a support person, for the resend) can **send it again** (a new link
   replaces the older ones and the time starts again), **invite another address** with the same action, or **cancel**: deactivating
   a `PROVISIONING` organization (typed confirmation and a reason) withdraws its open invitations and closes it.
6. **Repair.** An organization that lost every administrator (for example the only administrator's account was closed) gets a
   new first administrator through the same action (`POST .../first-administrator`); the person becomes an administrator but
   not the "founding" one (that stays a historical fact).
7. **Transfer between people is not built.** The first administrator names another administrator and steps down (ADR-0026);
   recorded as a deferral.

## Verification

`ProvisioningIT` (the whole path with the real mail catcher, the host closed then open, flags, the licence, the answer for an
address that has, hasn't and had an account, a coarse timing guard, resend by administrator and support, cancel, expiry and reuse,
repair, one winner for one name under concurrency, refusals that leave nothing behind, a mail-server outage), `FirstAdministratorRequestTest`,
`PlatformFlowsLogsAreCleanIT`, `InvitationFlowIT` (the ordinary invitation is unchanged).
