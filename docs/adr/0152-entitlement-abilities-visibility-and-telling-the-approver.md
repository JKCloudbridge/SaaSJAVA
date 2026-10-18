# ADR-0152: Entitlement, abilities, who sees what, and telling the approver

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0034](0034-feature-entitlements.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0024](0024-mail-queue-and-notification-v0.md), [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md),
  [ADR-0147](0147-completion-decisions-recall-reassign-and-concurrency.md)

## Decision

1. **Three separate mechanisms, never merged**: the **licence** (a member is assignable only while they hold the licence their profile needs),
   the **permission** (the abilities below and the object, field and record permissions that apply as always) and the **feature**
   (the plan feature `approvals`, asked with `Entitlements.enabled("approvals")`).
2. **The feature.** Without it a process cannot be made live, switched on or changed while on, and an instance cannot start (a submit is
   refused; a workflow step fails `feature_off`). Switching off, removing a process that has no pending instance, deciding, recalling and
   reassigning are never refused for the plan. **A pending instance of an organization that loses the feature continues to completion and
   its lock still works**, because a record must not be locked by something nothing can finish. The same holds for a process that is
   switched off.
3. **Removing a process or an object that has pending instances is refused** with a CONFLICT naming the count (the contract
   `ApprovalUsage`, implemented by `approval`, asked by the metadata service; the same shape as `ObjectUsage`).
4. **Abilities**: `approvals.view` (the definitions), `approvals.manage` (definitions, delegations of others, assigning a person to a step
   with nobody, reapplying an outcome), `approvals.view-all` (the instances and history of every record the caller can read) and
   `approvals.override-lock` (edit a locked record, recall for another, cancel). The administrator profile holds all four, the member
   profile none: **a member still submits what they may edit and decides what they are assigned**. Making a definition live also needs
   `metadata.publish` (ADR-0068).
5. **Who sees what.** Every endpoint is answered on an organization host only; the platform host and a platform person get "not found".
   Anything about a record is shown only when the caller can read the record (otherwise it is as if it does not exist); a field the
   caller may not read is absent from anything shown; the history shows no record value. An assignee's task for a record they cannot read
   is shown as such, with no title and no link target (ADR-0146 decision 6). A record's `approval` endpoint says what the caller may do
   **now**; the buttons of the screen come from it.
6. **Telling the approver**: the **inbox** and **one closed mail template** (`APPROVAL_REQUEST`) sent when a task is created, under the
   existing per-person mail limits and the per-organization switch. The mail carries **facts only**: the organization, the object name and
   label and the record identifier, plus the process label passed through `MailTexts.safeName`. **It carries no record title or value**
   (the Sprint 23 rule of ADR-0138: a queue row would keep a value for as long as it is retained). The link points at the organization's own
   host. A decision is **never** made from a link: the link opens the inbox.
7. **Settings** `platform.approval.*`: `limits.max-delegations-per-person` (20), `limits.max-delegation-days` (366),
   `limits.comment-length` (2000), `inbox.page-size` / `max-page-size`, `mail.enabled` (true).
8. **Scripts of custom pages and approvals** (found while testing the allow-list of ADR-0127 against the new endpoints). The token a custom
   page's script holds reaches the data interface of `/api/v1/objects/...` with the viewer's own permissions, which would include
   `POST /objects/{object}/records/{id}/approval`: a script could lock records and ask people to decide. Submitting is a person's own act,
   like a decision, so `EmbeddedAccessPolicy` refuses every method but a read on that one path (the panel may be read to be drawn; the
   decision endpoints under `/api/v1/approvals` and the definitions under `/api/v1/metadata/approvals` were never in reach).
   `EmbeddedAccessPolicyTest` holds both.
