# ADR-0057: Retention of closed invitations

- **Status:** Accepted
- **Date:** 2026-10-04
- **Sprint:** S9
- **Related:** [ADR-0028](0028-invitations.md), [ADR-0055](0055-audit-retention-and-the-append-only-door.md); delivery plan S5, S9

## Decision

1. **A closed invitation keeps the invited address and name for 30 days** after it was accepted or revoked (the user chose 30 days on
   2026-10-04; setting `platform.identity.cleanup.keep-closed-invitations`). Then the address becomes `anonymised-<invitation id>` and the name
   is removed; the row stays, so counts and history do not change. Reasoning in plain words: thirty days is long enough to answer "I never got
   the mail" and short enough to be modest with a stranger's address.
2. **An invitation never answered** and expired for 30 days is closed (REVOKED) and blanked in the same step, because an open invitation can
   still be sent again. Open and valid invitations are never touched.
3. **The database guard** (V030): a closed invitation is read-only except that the retention job may blank the address and name once
   (`anonymised_at` marks it, and only the exact blanked values are accepted); an anonymised invitation never changes again.
4. **How it runs:** a step of the identity clean-up (hourly), through the organizations one at a time, each under its own tenant context and
   transaction (no cross-tenant scope was added for it). One audit record per organization where something was blanked
   (`retention.invitations.anonymised`, the count only, never an address), in the same transaction.
5. **Not covered here (flagged):** addresses that also sit in the mail queue and in users' own accounts follow their own rules; a legal
   review of what else holds personal data is in the Sprint 9 manual steps.

## Tests

`InvitationRetentionIT` (before and after the period, only once, open and expired, the audit record, the guard, the clean-up job with a clock
the test moves).
