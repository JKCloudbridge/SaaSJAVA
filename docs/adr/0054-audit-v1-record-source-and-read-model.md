# ADR-0054: Audit v1: the record, where it comes from, and who may read it

- **Status:** Accepted
- **Date:** 2026-10-04
- **Sprint:** S9
- **Related:** [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md), [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0015](0015-row-level-security-implementation.md); delivery plan S9

## Decision

1. **One entry point stays: `AuditRecorder`** (shared kernel). `AuditRecord` gains `objectKey`, `recordId`, `oldValue`, `newValue` and
   `source` (API, EVENT, SCHEDULER, SYSTEM). They are optional, bounded (old and new value cut to 500 characters, key and identifier
   checked for shape) and meant for short values and keys, never typed text. The data engine fills object, record and old and new
   value from Milestone 3 on; today a few security changes use them. A record that carries no source gets API when it was made while a
   request was served and SYSTEM otherwise. The text form of a record prints only its kind and outcome, so debug logging cannot leak a value.
2. **The table stays platform-level** (`audit_record`): a sign-in on the platform host has no organization, so it cannot be tenant-scoped.
   `context_tenant_id` is the organization a record is about. Records are not copied into tenant tables.
3. **The read model is enforced by the database.** Two flags on each row, `audience_organization` and `audience_platform`, say who may read
   it, decided when the record is written by `AuditAudience` (and, for the old rows, by the backfill of V029): platform and system records are
   for the platform only; access, membership and organization records are for the organization only; support access, the lifecycle of an
   organization and retention are for both; a platform record about support access is for both when it is about an organization (the
   organization sees that support asked for and used access); sign-ins are for the organization when about one and for the platform
   otherwise. A kind no rule names is visible only to the side it happened on. A row level security policy (read side only, enabled and not
   forced so the owner that migrates and purges is not bound) shows a reader **with** a tenant the rows whose `context_tenant_id` is that
   tenant and whose organization flag is set, and a reader **without** a tenant the rows whose platform flag is set. Inserting stays open (a
   record can be about any organization). The reader's queries repeat the condition, so a mistake in a query shows nothing extra.
4. **What the viewer never shows:** the typed reason of a platform person (`reason_text`), the internal reason of sign-in and platform
   records (the true reason of a refused sign-in is kept from the person it refused, ADR-0005), the request and trace identifiers, and
   anything about another organization.
5. **Events are audited once.** The tenant lifecycle events of the outbox are consumed by an event handler of the audit module
   (`audit.tenant-lifecycle`) and become `tenant.lifecycle.*` records with the state before and after. The platform administrator's *action* is
   another fact (another actor, a typed reason) and keeps its own `platform.organization.*` record; the tenant service writes no direct audit
   record for a state change, so the same fact is never written twice. The unique `source_event_id` makes a second delivery of the same event
   a no-op even if the relay's own "handled" mark were lost. Events older than the outbox retention are not replayed.
6. **The use of system scopes is audited.** The `membership_lookup` scope writes `system.scope.used` each time (with the person and the host's
   organization when there is one; platform audience). The outbox relay's scope is not audited per use (it is entered every second on every
   instance); its effects are audited where they happen, in the handlers.
7. **Hash chaining is not built.** The append-only database rule already stops the application and the owner's ordinary statements. A chain
   would protect against someone with owner-level access rewriting history, and would force every audit write of an organization through one
   ordered point. It is deferred (a candidate for Sprint 34 with archiving), recorded in the delivery plan.

## Consequences

- Nothing outside the audit module reads `audit_record`. Tests read it as the owner.
- A new audit kind needs a decision about its audience; without one it is visible only to the side it happened on.
- The viewer's "target" filter matches the record identifier and the identifiers in the record's facts (a scan of one organization's rows
  in a time window, not an index lookup): fine for the volumes of an organization, to be revisited with the data engine.
- Tests: `AuditStorageIT`, `AuditViewerIT`, `TenantLifecycleAuditIT`, `AuditRecordTest`.
