# ADR-0194: List views, and search and order on the server, for the lists of a solution

- **Status:** Accepted
- **Date:** 2026-10-23
- **Track:** UI Alignment (UI3A), with one additive API change
- **Related:** ADR-0130 (saved list views), ADR-0168 (the Home screens), ADR-0188 (the master list of templates), ADR-0192
  (fill-ins), ADR-0174 section 7 (kept records), ADR-0115 (record-level security), ADR-0100 to ADR-0105 (the query engine)

## Context

The lists of the Audit maintenance application (Templates, Fill-ins) searched and ordered only the rows already read, and a
person had no way to choose columns, filter, or keep a way of looking. The product owner drew one row at the top of every
list: the name of a view with a menu, New, the search box and a settings icon, with the table right below.

## Decision

1. **The saved views of Sprint 19 serve these lists; no new table.** A view is keyed by the object API name and holds the
   closed query model (a filter tree, up to two sort keys, the columns). Templates and fill-ins are record-store objects
   (`InspectionTemplate`, `InspectionFill`), so `GET|POST|PUT|DELETE /api/v1/objects/{object}/views` already store, check
   (`QueryAnalyzer`) and share (`views.share`) them. A view grants nothing: opening one runs its question again as the
   person who opened it. Anyone who may read the object makes, replaces (this is also the rename) and removes their own;
   a shared view needs `views.share`. **Which view opens first** is remembered in the browser per list
   (`localStorage`, never the server); a server-side default per person is a later, separate decision.
2. **The two hand-written endpoints take the same question.** `POST /api/v1/templates/query` and
   `POST /api/v1/fill-ins/query` carry a search text, a filter (the `QueryFilterNode` of the query engine), up to two sort
   keys, a state or status and the cursor. They are a `POST` because a filter does not belong in an address. The plain
   `GET` lists stay for the callers that have them. Both go through `Records.query`, so the object permission, the field
   permissions and record-level security decide what is in the answer and the cursor stays a key, never an offset.
   `QueryFilterNodes` (data root, public) is the one conversion of the API's filter into the store's, used by the query
   endpoint, the view check and these lists.
3. **Search is one text matched against all the listed fields.** For templates: part of the name or of the number, or a
   whole category or sub-category code (the picklists hold short codes). For fill-ins: part of the name, or the whole word
   of a status. Status of a template (draft, active, ...) is **not** searched: it comes from the versions, not from a field,
   so it cannot be part of the record store's question. The state stays a filter applied after the page is read (as before).
4. **Order on the server is by a field.** A click on a header orders the whole result when the column is a field (name,
   number, category, sub-category, started, status). The columns that come from the versions of a template or the fill-in
   row (Versions, Status of a template, Version, Rows, Sent) are not clickable, because the record store cannot order by
   them and an order of the loaded page only would mislead. An order or a filter on a field with no index is bounded by
   `platform.data.query.unindexed-scan-limit` and refused above it, naming the field: no new rule.
5. **Select fields to display offers the fields the row carries.** The view stores field names only (the query engine
   checks them), so the columns that come from the versions are always shown. The picker (two boxes, buttons and the
   keyboard) changes the columns of the current view; Filter is a popup built from the server's own list of comparisons per
   field (`queryingOf`), several conditions joined by "and", no "or" and no saved filter apart from the view.
6. **No Delete.** Templates and fill-ins are kept records (`retainRecords`): the database refuses a delete, so the gear menu
   draws none. A template is retired from its own page (Retire is on the template, not on a list, because it asks which
   template). Delete is drawn only for a list whose records may really be deleted; no list of this application is one.
7. **The title row is `ListPage`'s.** `titleControl` stands in the place of the title (the view menu), `remote` hands the
   search text and the order to the screen; every other list keeps the client-side behaviour.

## Not done, on purpose

- **A count of fill-ins on the template** (item 6 of the prompt): the query engine gives no total (ADR-0105), so a count of
  what a person may see needs a bounded query for each template of a page, with a cost that has no measurement behind it.
  Cut, as the prompt allowed.
- **The generic record list (Department groups) keeps its old layout.** It is the list of every object of every
  organization, with views, bulk change and import; changing its title row is a change to all of them and is its own task.
  The Report page already has the title band and no description.
- **The unsaved-changes popup** (leaving the Details tab while editing, the browser Back button).
- **A view cannot hide or order by the derived columns**, see 4 and 5.

## Consequences

- Two endpoints (`queryTemplates`, `queryFillIns`), one public converter, no migration, no new module edge.
- A typed text (search, filter value, view name) never reaches a log, an audit record, an event or an error:
  `TemplateQueryIT` has the leak test.
- The OpenAPI document and the generated client changed and are committed together.
