# ADR-0130: Saved list views

- **Status:** Accepted
- **Date:** 2026-10-15
- **Sprint:** S19
- **Related:** ADR-0100 (the query model a view is made of), ADR-0039/ADR-0040 (abilities), ADR-0115 (record-level security: a view grants
  nothing), ADR-0014 to ADR-0017 (tenant scoping and row level security), ADR-0129 (the screens that use views)

## Context

The plan's S19 line says "record list views (filters, sort, pagination, saved views)", and the mockup shows a rail of named views on a list.
Nothing stored a view. The prompt marked it "a product decision before building the storage" and the product owner decided on 2026-10-15:
build it now, **personal and shared**, with an ability to share.

## Decision

- **A view is data, not metadata.** One table, `list_view` (V051): tenant-scoped (`tenant_id`, forced row level security, the row guard, the
  tenant guard), the object, a label, a scope (`PERSONAL` or `SHARED`) and the definition as JSON. It is **not** published, not part of a change
  set or a release, and does not raise the metadata version: it is a member's or an organization's saved way to ask, and nothing else reads it.
  The maker is `created_by`, which the row guard already keeps unchangeable, so "whose view is this" can never be rewritten.
- **The definition is the closed query model** (`ListViewDefinition`: a filter tree, up to two sort keys, a list of field API names, at most
  20 000 bytes by a database check). It carries names and values as data; nothing is run when it is saved or read.
- **A view grants nothing.** Opening a view runs its definition as an ordinary `POST .../records/query` for whoever opens it, so the object
  permission, the field permissions and record-level security of the person looking decide what the list shows. A shared view cannot show a
  person a record or a field they could not reach another way. `ListViewIT` has the case (a view that filters on a field a member cannot read
  is refused for them by the query, not by the view).
- **At save time** the definition goes through the first three stages of the query engine (`QueryAnalyzer.analyze`: shape, fields, operators and
  values) against what the **saver** may read, so a view that could never run is refused with the places that are wrong, under `definition.`
  (`definition.filter.field`, `definition.columns[1]`). The engine's own limits (depth, number of conditions) apply. A value is never echoed.
- **Who may do what.** Anyone who may read the object may save a personal view and change or remove their own. A shared view is made, changed
  and removed only by a member who holds the new ability **`views.share`** ("Share list views"), whoever made it; an administrator profile holds
  it as it holds every ability. Only the maker of a shared view can make it personal again (it would vanish from everyone else). Another
  person's personal view, and a view of an object the member may not read, answer as a view that does not exist; the answer for an unreadable
  object is the one for an unknown object.
- **Names.** A shared name is unique in its object and a personal name is unique among the maker's own personal views of the object (partial
  unique indexes on `lower(label)`), so two people may each have a "Mine". A removed view is a soft delete, so its name is free again.
- **Bounds.** At most `platform.data.views.max-personal-per-object` (50) personal views per person per object and `max-shared-per-object` (50)
  shared views per object (`ListViewProperties`, validated at start).
- **Concurrency.** Changing and removing carry the version read (`CONCURRENT_MODIFICATION` otherwise), like every other definition.
- **Audit.** `data.view.created`, `.changed`, `.removed` carry the object, the view's identifier, the scope and the version, never a name or a
  definition (the label and the filter are text a person typed).

## Not decided here

- A default view per object or per person, ordering of the views by the person, sharing a view with one group rather than everyone, and a
  count per view (the engine gives no total, ADR-0105). Each is a later, separate decision; the table and the API leave room.

## Consequences

- One migration (V051), one ability, one table in the isolation harness (`TenantScopedTables.LIST_VIEW`).
- Tested: `ListViewIT` (16 cases: personal, shared, ability, maker-only personalization, uniqueness, limits, version, validation of places,
  cross-tenant, unauthenticated, audit), `ListViewPropertiesTest`, the isolation harness and the schema scanner.
