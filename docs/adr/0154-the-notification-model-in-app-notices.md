# ADR-0154: The notification model: in-app notices

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0024](0024-mail-queue-and-notification-v0.md),
  [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md), [ADR-0152](0152-entitlement-abilities-visibility-and-telling-the-approver.md),
  [ADR-0155](0155-the-text-of-notices-and-mails.md), [ADR-0156](0156-preferences-and-the-floor.md), [ADR-0157](0157-asynchronous-delivery-and-once.md)

## Context

Until Sprint 24 the platform could tell a person something by one closed mail and by the inbox of approvals. Automation (Sprints 22 to 24)
now runs, retries, waits for people and ends, and a member of an organization has no place in the application where the platform says
"this needs you". The notification module (Sprint 4) had mail only.

## Decision

1. **A notice is a row of the tenant-scoped table `notification`** (V056): the recipient (a user), a closed **kind**, **facts**, the record
   it is about (object API name and identifier, both or neither), a **dedupe key**, the time it was read and the base columns. Row level
   security is enabled and forced; no system scope admits it.
2. **The kinds are a closed list in the shared kernel** (`NoticeKind`): `APPROVAL_TASK`, `APPROVAL_REMINDER`, `APPROVAL_DECIDED`,
   `WORKFLOW_NOTICE`, `TRIAL_NOTICE`. Each belongs to one **category** (`APPROVALS`, `WORKFLOWS`, `ACCOUNT`), lists the **facts** it may
   carry, and says whether an administrator may write its text. A database check holds the same list.
3. **Facts are identifiers and API names, never a value and never text.** A request (`NoticeRequest`) is checked when it is built: a fact not
   listed by the kind, or a value that is not shaped like an API name or a stable key, is refused. A notice never holds the title of a
   record, a comment, a typed label or a link. The metric of notices carries the kind and the result (both closed), never a recipient, a
   record or a key.
4. **The words are built when the notice is read** (ADR-0155) from the template of the kind and labels looked up now, so a change of text
   or a rename reaches old notices. A label that no longer exists leaves its place empty; a notice is never an error.
5. **Once.** The unique index `(tenant, recipient, dedupe key)` is the guarantee. The producer makes the key from identifiers it already
   has (a step and a person, a request, the idempotency key of a workflow step), so a retry, a replay, a redelivered event and two instances
   racing all make the same request and get `DUPLICATE`.
6. **One door.** Everyone who needs to tell a member something calls the shared-kernel contract `Notices` (the pattern of `MailQueue`),
   in the transaction of the change and in the organization's own tenant context; the implementation lives in `notification`, and an
   architecture test fails the build if any other class names the notice tables. A recipient who is not an active member (asked through
   the existing reference directory of users) gets nothing.
7. **Reading is the member's own.** `GET /api/v1/notifications` (cursor paging, newest first, `unread=true` narrows), `GET .../summary`
   (the count for the bell), `POST .../{id}/read`, `POST .../read-all`. The member comes from the token and the host; a notice of someone
   else answers "not found", like one that does not exist. No ability is asked: nothing here reaches anybody else's notices. The platform
   host answers "not found".
8. **The link** (`href`) is a path on the organization's own host: the inbox for a task or a reminder, the record otherwise. The page it
   opens decides what the member may see (a record they cannot read is "not found"): a notice grants no access, as an approval grants none.
9. **Retention.** Read notices are removed after 30 days, unread ones after 180, and **nothing younger than the floor of 7 days is ever
   removed**, whatever the other two say (settings `platform.notification.notices.retention.*`, validated at start; the floor is at least a
   day). The clean-up walks the organizations and works in each one's own tenant context, as the recycle bin clean-up does, writes one
   audit record per organization and pass with the count, and needs no system scope. Page size 30, at most 100.

## Consequences

- A member sees what needs them in one place, and an administrator can rewrite the words without a release.
- A notice is cheap: a small row with no text. The unread count reads a partial index.
- The cost of looking up labels is paid per request, once per object, process and workflow, from the metadata snapshot.
- A deleted process leaves old notices with an empty label; nothing breaks.

## Alternatives considered

- **Store the finished text:** simpler to read, but a change of template never reaches old notices, and a stored sentence is a place
  where a value could leak. Facts and a key are smaller and safer.
- **Create notices from the write path of records:** it would put the notification module into the hot path of every write; events and
  actions already say "something happened" after the commit.
- **Push, SMS, a digest:** out of scope (ADR-0166).

## References

- `platform-shared-kernel/.../sharedkernel/notice/` (`NoticeKind`, `NoticeRequest`, `Notices`, `NoticeText`)
- `platform-app/.../notification/internal/` (`DefaultNotices`, `NoticeStore`, `NoticeTexts`, `NotificationService`, `NotificationController`, `NoticeRetention`)
- `platform-app/src/main/resources/db/migration/V056__create_notifications_preferences_and_templates.sql`
