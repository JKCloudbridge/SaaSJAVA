# ADR-0155: The text of notices and mails

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0024](0024-mail-queue-and-notification-v0.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0068](0068-the-publish-ability-and-changes-made-at-once.md),
  [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md), [ADR-0154](0154-the-notification-model-in-app-notices.md)

## Context

The words of every mail were fixed in code (`MailTexts`). An organization wants its own sentence for "a record needs your attention" or "a
decision is waiting", and a notice in the application needs words too. Text written by an administrator is text of a person: it must not be
able to carry a value of a record, a link that leaves the platform, markup, or a secret.

## Decision

1. **Two layers.** The platform's own text for each kind (`NoticeText.defaultTitle`, `defaultBody`) and, for the kinds an administrator may
   write (`APPROVAL_TASK`, `APPROVAL_REMINDER`, `APPROVAL_DECIDED`, `WORKFLOW_NOTICE`), a **template** that replaces it. The trial notice is
   the platform's own and cannot be rewritten (account standing, ADR-0156).
2. **A template is published metadata**: one row per kind and organization in `notification_template` (V056), changed only through the
   lifecycle of Sprint 11 with three change kinds (`CREATE_`, `UPDATE_`, `DELETE_NOTIFICATION_TEMPLATE`, the last one meaning "go back to the
   platform's text"), so it can be drafted in a change set, checked, previewed, published, rolled back and audited. It needs
   `metadata.manage` and `metadata.publish`, like every definition. The module `notification` reads it live through the contract
   `NotificationTemplates` of `metadata` (a handful of rows, asked once per request or per mail, never older than the last publication);
   **new edge `notification -> metadata`** (no cycle: `metadata` knows nothing of `notification`).
3. **Placeholders come from a closed list, and they stand for labels, never for values.** Every kind may use `{organization}` and
   `{object}`; the others are those of its facts: `{process}`, `{step}`, `{workflow}`, `{outcome}`. **A field of the record is not a
   placeholder** and the title of the record is not either: for the same reason as the mail title of Sprint 23 and Sprint 24, the text of a
   notice lives longer than the request and a mail leaves the platform's control. Each inserted label passes `NoticeText.safeName`: cut to a
   short length and reduced to letters, digits, spaces and a few quiet marks, so it can only be a name.
4. **Rules of a text**, checked by the one class `NoticeText` that both `metadata` (at save) and `notification` (at read) use: a title of 1
   to 80 characters on one line; a body of 1 to 500 characters with line breaks allowed; no `<` or `>` (no markup); no link and no address
   (no `http`, `ftp`, `www.`, `://`, `@`: the platform adds its own link to a mail, and a notice has its own `href`); no control character;
   a placeholder not on the list of its kind is refused, naming the list; a brace that is not part of a placeholder is refused. Every
   problem is reported, with its place (`title`, `body`), and **none repeats the text**.
5. **A mail uses the body only.** The subject of every mail stays the platform's: it holds no text of a person. The mail is the
   administrator's body followed by the platform's own link and footer ("you need to sign in; if you did not expect this, ignore it"). The
   mail request still holds facts only; the relay reads the template in the organization's context when it sends.
6. **Check and try.** `POST /api/v1/metadata/notification-templates/check` answers with the problems and a preview that uses **sample
   labels** ("Example Organization", "Invoice", ...). Nothing is stored, audited or logged, and the answer repeats nothing of the request.
7. **Never logged or put in an event.** The audit says which kind changed (`metadata.notificationtemplate.created|updated|reset`), never
   what it says; the request records print `[redacted]`.

## Consequences

- An administrator can rewrite five sentences without a release, and cannot make the platform say something it should not.
- A template that is valid when saved is always renderable: the placeholder list is the same code at save and at read.
- Mail text cannot change the subject or add a link, so a rewritten mail is not a phishing vehicle.

## Alternatives considered

- **Fields of the record as placeholders:** the single most requested feature and the single most dangerous: the value would enter the
  queue row, the notice row and the mail. Left out on purpose; a person opens the record.
- **Templates in the notification module without change sets:** simpler, but no drafts and no rollback. The lifecycle exists; using it is
  cheaper than a second history.
- **A template engine:** a language is a surface; five names in braces are enough.

## References

- `platform-shared-kernel/.../notice/NoticeText.java`; `platform-app/.../metadata/internal/NotificationTemplate*`; `.../notification/internal/{NoticeTexts,MailComposer}.java`
