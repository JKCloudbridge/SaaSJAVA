# ADR-0035: Controlled support access: request, approval, time limit, audit, and no automatic access

- **Status:** Accepted (the record, its lifecycle and the enforcement point; no tenant business data exists yet to protect)
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0027](0027-which-organizations-does-a-person-belong-to.md),
  [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md),
  [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md); architecture note 2, section 31

## Context

Platform support staff must be able to help a customer, but they must not have standing access to the customer's data: enterprise
customers will ask for exactly this. ADR-0003 and ADR-0027 say that platform access to tenant data needs its own request, time
limit and audit and must not reuse the narrow `membership_lookup` scope or a broad system scope.

## Decision

1. **The flow.** A platform person (administrator or support) **asks** for one organization with a short reason and the minutes
   they need (15 to 240); an **administrator of that organization** approves or denies from its own screens (the Members page
   shows a "Support access" section); the approval opens a **window of at most four hours** (never longer than asked, never
   longer than 240 minutes) that **ends by itself**; the organization may **revoke at any time**; access past the window needs a
   new request. A request nobody answers ends after 24 hours; a person has one open request per organization (an older
   unanswered one is closed when they ask again).
2. **The record is the organization's own** (`support_access_grant`, V017, tenant-scoped, row level security forced). The
   platform person creates it by opening that one organization's context (ADR-0031), so it cannot be written for another
   organization; the organization's administrators see and decide it through `OrganizationAdministration` (so the check "may
   this member administer?" stays in one place). The database refuses an illegal move, a window longer than four hours (clock of
   the database), a closed grant that changes, and a request that is edited.
3. **Who sees what.** The organization's administrators see every request of their organization (the requester's **name**, never
   the address); platform administrators and support see the grants of an organization in the console. The reason a platform
   person types is bounded, kept in the audit trail and shown to the organization (it is what the organization decides on); it is
   never put in a mail.
4. **One enforcement point.** `SupportAccess.require(organization, platformUser)` is a contract in the shared kernel (like the
   audit and event contracts, so a module that reads tenant data can call it without depending on the module that keeps the
   grants) and is implemented in `platformadmin`. It refuses (`SupportAccessDeniedException`, answered `403` with no reason) unless
   **that person** has an approved, unexpired, unrevoked grant for **that organization** right now; a grant of one person or one
   organization never opens another. **Every later sprint must call it before any read or change of an organization's data on
   behalf of a platform person.** Each use and each refusal is audited (`platform.support_access.used` / `.refused`).
5. **No new mechanism that could bypass it:** no system scope, no privileged connection, and the console has no endpoint that
   returns tenant data at all.

## Trade-offs

- "Active" is evaluated by the database clock on every call (no cache), so a revocation or an expiry is effective at the next
  call; a read already in flight finishes.
- A grant is for a person, not for "support": two colleagues need two requests. That is the point.

## Verification

`SupportAccessIT` (no access until approved; the window, the audit, expiry by itself, less than asked but never more or beyond
four hours also in the database, revoke, deny, an unanswered request, another person and another organization refused, only
the organization's administrators decide, one open request, cancel, closed organizations), `PlatformRolesIT` (endpoint matrix),
`PlatformFlowsLogsAreCleanIT` (reasons are never logged or mailed), `TenantIsolationIT` (the harness on the table).
