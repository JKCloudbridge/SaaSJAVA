# ADR-0056: The audit viewer

- **Status:** Accepted
- **Date:** 2026-10-04
- **Sprint:** S9
- **Related:** [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md); delivery plan S9

## Decision

1. **Two readers, one public interface (`AuditEvents`).** An organization's: `GET /api/v1/audit-events`, organization hosts only, for members
   who hold the new ability **`audit.view`** (the system administrator profile holds every ability, so it has it; a custom profile or access
   policy can be given it). The platform's: `GET /api/v1/platform/audit-events`, platform host only, platform administrators only
   (`@PlatformFunction`). A platform role gives no organization authority and the other way round: each is refused on the other's endpoint.
2. **The organization is never a parameter.** It is the host's, from the authenticated context; a forged tenant header changes nothing
   (tested). The database repeats the separation (ADR-0054).
3. **What an organization sees:** its access, membership and sign-in events, the lifecycle of the organization, retention of its invitations,
   and the platform's support-access requests and uses about it. Never platform internals (roles, plans, billing, system scopes), never typed
   reasons, never the internal reason of a sign-in.
4. **Filters:** time range (`from`, `to`), person (`actor`), kind (`kind`: an exact kind or a whole family such as `access`), target
   (`target`: a record, member or group identifier). Malformed filters are refused in words (400). **Paging** follows the API's cursor model
   (newest first, `limit`, `cursor`, no repeats or gaps; a cursor that is not ours starts again).
5. **Export is deferred** (the viewer reads; a file export needs its own decisions on size and personal data).
6. **The page** (`/audit`, organization side; `/console/audit`, platform console) shows what the API answers and says plainly when there is
   nothing ("No events yet"). It decides nothing. People are shown by a short identifier, not a name: the members list carries
   membership identifiers, not user identifiers, so a name would need a lookup across modules; names are deferred (Sprint 9 notes).

## Consequences

- `audit` depends on `identity` (the one question "may this member administer"), `security` (the ability), `tenant` (the context) and
  `sharedkernel`; nothing in that chain depends on `audit`, so there is no cycle. `platformadmin` already depended on `audit`.
- Tests: `AuditViewerIT` (isolation, abilities, platform administrator in both directions, forged header, hidden facts, filters, paging, every
  security change of Sprints 7 and 8 shown), frontend tests of the page.
