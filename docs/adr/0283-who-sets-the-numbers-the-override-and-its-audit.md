# ADR-0283: Who sets the numbers: the override of an organization and its audit (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md), [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0280](0280-rate-limits-and-quotas-the-algorithm-keys-cost-and-paths.md)

## Decision

1. **Defaults are settings; an override is a row.** The defaults are `platform.limits.public-api.*` with the ceilings
   (`ceilings.per-minute`, `ceilings.per-day`) that an override can never pass. An override is one live row of
   `rate_limit_override` (V096) per organization: tenant-scoped, forced row level security, at most one live row, every number
   inside its range and at least one number given (a number left out keeps its default). It holds numbers only: the reason the
   platform person gives is **not** stored in it.
2. **Only the platform console sets it**, through `@PlatformFunction` endpoints under
   `/api/v1/platform/organizations/{id}/limits` (read: platform administrators and support; set and reset: platform
   administrators only). The platform person reaches the organization the way every platform action does, by opening that
   organization's tenant context before a transaction (no privileged connection, no new system scope), and reads and changes
   **numbers only**: no record, member or setting of the organization. Nothing an organization does reaches the setter, and
   a platform role gives no webhook ability and no use of the public API: it changes how many requests an organization may make,
   never what any of them may do. The contract between the module that counts (`identity`) and the console (`platformadmin`) is
   `RateLimitSettings` in the shared kernel, so neither depends on the other's internals.
3. **The reason is bounded, audited and never logged,** as in Sprint 6: one to 200 characters, kept in the audit trail only (a
   record `limits.override_set` or `limits.override_removed`, by the identifiers of the platform person and the organization), never
   mailed, echoed, shown to the organization or put in a log line (the request types print as redacted). The audit audience of
   `limits.*` is the platform only (`AuditAudience`), so the organization's own trail does not carry the reason.
4. **The organization sees the numbers, not the reason,** on its usage page (`GET /api/v1/usage`, ability `usage.view`): its own
   limits and what is used, per application, with whether a number was set for the organization.
5. **A per-application number** is not built: every application and every member's token has the one number of the organization.
   It is cheap to add (a column and a key) when a customer needs it.

## Consequences

- A customer who needs more writes to the platform; a person with the right role changes one number with a reason, and it is on
  the record.
- Another organization is never touched: the counters, the cache and the row are all per organization.
