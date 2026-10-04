# ADR-0055: Audit retention and the one door through the append-only rule

- **Status:** Accepted
- **Date:** 2026-10-04
- **Sprint:** S9
- **Related:** [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md); delivery plan S9, S34

## Decision

1. **Audit records are kept 400 days** (a little over a year, so a yearly review still finds everything), one setting for the whole platform
   (`platform.audit.retention.keep`, never below 31 days). The user chose this default on 2026-10-04. A period per organization is not built;
   the legal side is listed in the Sprint 9 manual steps (what to ask a lawyer).
2. **The append-only rule stays** (`audit_record_append_only`, V009) and is refined (V029): every update is refused, and every delete is
   refused except one made by `platform_audit_purge(older_than, batch_size)`, the single `security definer` function. The trigger lets a
   delete through only when the statement runs under a role that differs from the session's login role *and* is the table's owner, which only
   a security-definer function can arrange. The application role deleting directly, and a person who logs in as the owner and deletes,
   are both still refused (tested).
3. **The function refuses** a cut-off younger than 30 days, refuses a batch size outside 1 to 10000, deletes at most one batch in a step
   (oldest first, skipping rows another step holds), and **writes its own record** (`audit.records.purged`, the count and the cut-off, source
   SCHEDULER, platform audience) in the same step. Nothing is removed silently.
4. **The job** (`AuditRetention`) runs daily on every instance that has it on, in the style of the identity clean-up (there is no scheduler
   before Sprint 22): it computes the cut-off from the clock and calls the function until a step removes less than a full batch. Two instances
   at once are harmless.
5. **V029 is the one deliberate exception to append-only:** to fill the new columns of the existing rows it switches the guard triggers off
   for its own statements and on again in the same transaction. Nothing else may do that.
6. **A manual purge by a person** (for example on legal request) goes through the same function, called with the application role's session
   (`set role` to it); the function is the audit trail of the purge.

## Consequences

- Retention of the tenant lifecycle and of everything else is uniform; there is no "keep forever" class yet. Sprint 34 (archiving) decides
  whether old records move to cheaper storage instead of disappearing.
- Tests: `AuditStorageIT` (append-only as both roles, the function's refusals, its own record, the job with the retention period).
