# ADR-0156: Preferences and the floor

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0154](0154-the-notification-model-in-app-notices.md), [ADR-0024](0024-mail-queue-and-notification-v0.md),
  [ADR-0157](0157-asynchronous-delivery-and-once.md)

## Context

Some of what the platform tells a person is theirs to silence (a reminder for the tenth time); some is not (a lock on their account, a
password changed, the link that lets them in, the standing of their organization). Where the choice is read decides whether a switched-off
notice costs a row, a mail or nothing.

## Decision

1. **A choice is a person's switch for one category and one channel**, per organization: categories `APPROVALS`, `WORKFLOWS`, `ACCOUNT`,
   channels `IN_APP` and `MAIL`. The table `notification_preference` (V056) holds an explicit choice only; **no row means on**. The unique
   index is by organization, person, category and channel.
2. **The floor is applied in code on every read and held by the database.** The `ACCOUNT` category cannot be switched off, and a kind
   whose floor is set (the trial notice) cannot either. `PreferenceRules` is the one pure function that turns a stored choice and the floor
   into an answer; a row written by a bug or by hand can never silence what the platform requires, and a check constraint refuses a row that
   would switch the account category off. An attempt to switch a locked switch off through the API is a `VALIDATION_ERROR` naming the
   place, never silently kept.
3. **Account mail is outside the model.** Sign-up, reset and invitation links, the password-changed notice and the lock notice are
   platform-level (they belong to a person, not to an organization), have no organization context and are never subject to a preference.
   The lock notice of Sprint 3 matches this model: always sent.
4. **Where the choice is read.** For an in-app notice, **at creation**: a switch that is off creates nothing (`SUPPRESSED`, counted), so
   there is no row to delete and no count to explain. For a mail, **at send time** in the relay, in the organization's own tenant context:
   the relay already decides then what a queued mail becomes (ADR-0024), so a person who switches mail off stops the mails already queued
   too. An unreadable choice (the organization is gone) is treated as on and the organization's own checks decide.
5. **Endpoints** (the member's own, no ability): `GET` and `PUT /api/v1/notification-preferences`. The answer lists every category and
   channel with the words to show, whether it is on and whether the platform fixes it on; the screen holds none of it. Only the switches
   named in a request change. A change is audited as a count (`notification.preferences.changed`).
6. **No digest, no quiet hours, no per-record mute.** Out of scope (ADR-0166).

## Consequences

- A person can stop the noise without being able to miss a security notice.
- The cost of a preference is one indexed read per notice or per mail.
- A new kind of notice must declare its category and its floor (the enum forces it); a new category is a code change and a migration.

## Alternatives considered

- **Read the choice at delivery for in-app notices:** keeps a row for something nobody wants and makes the unread count lie.
- **A per-kind switch:** too many switches for a person; categories carry the meaning.
- **Store the floor as rows:** a floor that is data can be deleted; this one is a rule.

## References

- `platform-app/.../notification/internal/{PreferenceRules,NotificationPreferences,PreferenceStore}.java`
