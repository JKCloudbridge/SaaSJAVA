# ADR-0128: The `Tab` metadata kind, and navigation filtered by what the member may actually open

- **Status:** Accepted
- **Date:** 2026-10-15
- **Sprint:** S18
- **Related:** ADR-0069/ADR-0073 (applications and navigation, Sprint 12), ADR-0127 (the sandboxed custom page mechanism a `CUSTOM` tab
  renders with), ADR-0050 (the permission decision API, `Decisions`)

## Context

Sprint 12 built `ApplicationDefinition` with navigation items inlined directly into the application: an `OBJECT`, `RECORD_PAGE`, `LINK` or
`HEADING` entry belongs to exactly one application. ADR-0073 explicitly deferred "showing only what the member may read" to this sprint and
named it "Sprint 18, security-aware navigation" when it was written.

Two things are new in this sprint and change the shape:

1. A custom page needs a **place in the navigation that behaves identically wherever it is used** — the same custom page, the same sandbox
   mechanism, should work whether an application lists it directly or (from S20 on) a record page's tab shows it. Sprint 12's model, where
   every navigation item is private to one application, cannot express "this place in the navigation is reusable."
2. `GET /api/v1/applications` has never asked whether the member can actually read what an entry opens — it only asked whether the
   *application* names their profile. An object the member cannot read was listed next to one they could, with no way to tell which was
   which until the record screens of Sprint 19/20 tried to open it and failed.

## Decision

### 1. `Tab` is a new, independent, reusable metadata kind

`Tab` (table `tab`, published metadata like `application`, same lifecycle: a `Change.Kind` pair with undo, a dependency-graph contributor, a
JSON schema) has an API name, a label, a description and a `kind`. Today there is exactly one kind, `CUSTOM`, which names the `CustomPage` it
shows (ADR-0127). `ApplicationDefinition.NavigationItem` gets a fifth `ItemType`, `TAB`, which names a `Tab` by its API name
(`tabApiName`); the four kinds of Sprint 12 are unchanged and keep working exactly as before (an existing application's stored JSON needs no
migration — expand, not contract).

An object still needs no `Tab` row of its own: the existing `OBJECT` navigation item already opens an object's list, and that stays true.
Only a tab somebody explicitly made (today: a custom one) is stored. This mirrors the record type's "a record type with no assignment is
open to everyone" pattern (ADR-0098) in spirit: the common case needs nothing written down, and a row exists only where a choice was made.

The dependency graph gets two new edges: a `Tab` of kind `CUSTOM` needs its `CustomPage` to exist (removing the page first is refused,
naming the tab), and an application's `TAB` item needs its `Tab` to exist (removing the tab first is refused, naming the application). Both
are proven by `TabsAndCustomPagesIT` live and through the Sprint 11 change-set lifecycle (a set that creates the script, the custom page,
the tab and the application together publishes and rolls back as one unit, in any order the changes were added — ADR-0065's "only the
result matters" rule).

### 2. `GET /api/v1/applications` now asks `Decisions` and drops what the member cannot open

`MyApplications.mine()` calls `Decisions.accessToAll(membershipId)` once per request and checks every `OBJECT` and `RECORD_PAGE` entry's
object against it before including the entry. This is the same answer the data interface itself would give (`ObjectAction.READ`), so an
entry that is listed is guaranteed to be openable, and an entry that is not listed answers exactly like one that does not exist — no new
"forbidden, but shown anyway" state exists anywhere in the menu.

- **A heading with nothing under it, or an application with nothing left, is dropped**, not shown empty. `withoutEmptyHeadings` keeps a
  heading only when a non-heading entry immediately follows it (before the next heading); `mine()` keeps an application only when it had no
  items to begin with (so a genuinely empty application — nothing configured yet — still shows, as a cue to an administrator) or at least
  one entry survived the filter.
- **A `CUSTOM` entry is listed only when both its `Tab` and its `CustomPage` still exist**, and carries the `CustomPage`'s current `assetId`
  and `sha256` in the view (`NavigationEntryView.assetId`), so the browser's script cache (`src/lib/customPage/client.ts`) knows the moment
  the list says the asset changed, without a separate round trip to ask.
- **`NavigationEntryView.available`** is `false` only for an entry that exists in the list but cannot be opened *right now* for an
  operational reason (today: the object store is not configured, so no custom page script can be served). It is never used for "the member
  may not read this" — that case is omitted entirely, per the rule above. The frontend shows an `available: false` entry dimmed, with a
  reason, never as a dead link and never hidden (`ControlBar.tsx`, the `control-tab-unavailable` style) — this is the one place the
  shared design system's disabled look (S12A, "reduced strength, flat, not-allowed pointer") is reused for a navigation entry, not invented
  again.
- A **second, narrower gate** decides whether a *particular* member may fetch a *particular* custom page's script
  (`MyApplications.openable`, used by `GET /api/v1/applications/custom-pages/{apiName}`): the page must be shown by a `Tab` that some
  application the member can see actually lists. This is deliberately asked again at download time rather than trusted from the list,
  because the list can be a minute stale (`ApplicationsProvider`'s staleness window) while the download is not: the answer the member gets
  when they actually open a page is always the live one.

### 3. The frontend keeps a versioned-by-content copy of the list, never a permission decision of its own

`ApplicationsProvider` (`src/lib/applications/ApplicationsProvider.tsx`) is the one place the browser holds the member's applications. It
follows the same staleness discipline the backend's own caches follow (ADR-0053, ADR-0074): the copy is asked for when a member signs in on
an organization host, again when the tab becomes visible after being stale for more than a minute, again on demand (a page that could not
find an entry it expected asks once before saying "not available" — `ApplicationEntryView`), and a failed refresh keeps showing the last
good copy with a note, never a blank screen. The frontend decides nothing from this copy beyond "is there a link to draw": every page a link
opens still asks the API again, per the standing rule that frontend checks are UX only.

### 4. Routing: generic, metadata-driven paths, not one route per application

`/apps/[application]/[entry]` is the one pair of dynamic routes for every application and every entry of every tenant; `entrySlug`
(`src/lib/applications/entries.ts`) turns a resolved entry back into a short, typed address segment (`o-<object>`, `p-<object>.<page>`,
`c-<tab>`) so that an object entry, a record-page entry and a tab entry can never collide even if their labels do. `/apps/[application]`
with no entry opens on the application's first openable page (`firstPage`), so choosing an application in the launcher always lands
somewhere, in the order the API gave the entries — never a hardcoded choice.

## Consequences

- `ApplicationMenu.tsx` (Sprint 12's sidebar list) is replaced by `AppLauncher` (the grid, in the header's new control row) and
  `ControlBar` (the open application's name and its pages as tabs), per the mockup's control-row/app-launcher reading this sprint confirmed
  with the user before building. `ShellNav` keeps only the platform's own fixed pages (Home, Console, Members, Audit trail, Setup), exactly
  as ADR-0073 already said it would.
- `GET /api/v1/metadata/applications` (the setup screen's view of the raw definitions, for someone who manages metadata) is **unchanged**:
  it still shows the whole stored definition, including entries that would be filtered out of a member's own menu, because an administrator
  editing an application needs to see what is actually configured, not a personalized view of it.
- No new module edge: `application` already depends on `security` (ADR-0073's module table) and already reads `Decisions`-adjacent types;
  this sprint is the first time it actually calls `Decisions.accessToAll`.
- Tested: `NavigationSecurityIT` (an unreadable object dropped, an empty-after-filtering heading/application dropped, a `CUSTOM` entry's
  identity fields, the Sprint 12 kinds unaffected, the script endpoint's "not listed / not yours / unknown — same words" matrix, a tampered
  stored script refused), `TabsAndCustomPagesIT` (the two dependency edges, the lifecycle, the limits), `ApplicationsIT` (updated for the
  now-mandatory read permission on a listed object; it previously relied on every member implicitly seeing every object, which this sprint
  deliberately ends).
