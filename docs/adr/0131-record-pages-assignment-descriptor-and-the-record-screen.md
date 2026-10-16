# ADR-0131: Record pages: assignment, the member's descriptor and the record screen

- **Status:** Accepted
- **Date:** 2026-10-16
- **Sprint:** S20
- **Related:** ADR-0069 and ADR-0070 (the record page definition), ADR-0071 (which layout a person gets: the order this copies),
  ADR-0098 (record types per profile), ADR-0117 (who may share), ADR-0127 and ADR-0128 (custom pages and tabs of the organization),
  ADR-0129 (the member's description of an object and the form the page reuses), ADR-0115 to ADR-0118 and ADR-0125 (record security)

## Context

Sprint 12 stored record pages (a header, components, tabs, actions, closed visibility rules) but nothing chose between them or drew
one. A page is "the screen of one record": a card with the record's name and a few values, a row of tabs (the layout's details, related
records, tabs of the organization), and the actions a person may take. The product owner's mockup shows exactly that: a list, then a card
with an **Edit** button and tabs **Content** and **Related Records** below it, the Content tab reading the page layout. Sprint 17 built
sharing and ownership with no screen.

## Decision

### Which page a person gets: `RecordPageAssignment`

- A new metadata kind through the Sprint 11 lifecycle (table `record_page_assignment`, V052; three change kinds with their undo; a
  dependency contributor; a release item), built **exactly like `LayoutAssignment` (ADR-0071)**: **profile and record type only, no
  application dimension**, so a record page is the same whichever application opened it (as a tab's list is, ADR-0128).
- The order: profile with record type, record type, profile, the object's default (`default`). Unlike a layout, a record type carries
  **no page of its own**, so the record type alone is a rule here (an assignment with a record type and no profile, key `type~<type>`).
- The profile is an identifier only: a deleted profile is never blocked and its assignment is ignored. The page and the record type must
  exist and are kept while an assignment names them. At most 200 assignments per object (`platform.metadata.limits.max-record-page-assignments-per-object`).
- Abilities as for every definition: reading needs `metadata.view`, a change made live needs `metadata.manage` and `metadata.publish`.

### What a member's screen is told: `GET /api/v1/objects/{object}/record-page`

- One member read, like the form of ADR-0129 and with no administrative ability: `RecordPageViews` resolves the page for the member's
  profile and the record's own type (or `?recordType=`), applies the closed visibility rules to the record's values (a field the member
  may not read is absent, so it reads as empty and never as equal), and leaves out what they may not reach: a related list of an object
  they cannot read, of columns or a link field they cannot read, a tab of the organization whose page is gone, an action the object
  permission does not allow. A tab left with nothing goes. Nothing is ever shown as "refused".
- **The shape follows the page's own order.** `above` are the blocks written before the tab strip, `tabs` the tabs, `below` those after
  it, `sidebar` the blocks of the side region. A page whose every block was left out for this member still shows the record's details.
  `ACTIVITY` and `HISTORY` are **not drawn** (nothing can answer them before the audit viewer reaches records); they are skipped.
- **A page with nothing configured gets the platform's own page**: a `Content` tab with the details and, when other objects have a link to
  this one that the member may read, a `Related Records` tab with one list for each such link (up to 10; columns: what names the record,
  then up to three fields that fit a cell; five rows each), and a header of the title and up to four fields. So every object has a record
  screen with no setup.
- **Which layout the details show.** A configured page names one (`layout` is required by its schema) and the screen asks
  `GET .../form?layout=<name>`; a name that is not a layout of the object is ignored and the member's own resolution (ADR-0071) decides,
  so a page whose layout was removed still opens. The platform's own page names none, so ADR-0071 decides. The form still reduces the
  layout to the fields the member may read and edit.

### The screen

- **A record is read first and changed on purpose.** The details are text until the person chooses **Edit**, which turns the same layout
  into the form of Sprint 19 (preview, conflict recovery, the server's words). **Save** and **Cancel** sit in the header's group of
  buttons; the form's own button row is hidden then. The group holds what the server says the member may use: Edit, Delete (confirmed),
  Clone and Share. Custom buttons are **not built** (their own parked sprint); the group is where they would join.
- **Clone** opens the create form with `?cloneFrom=<id>`: the original's typed values, **never** the owner, a unique field, an identifier
  from another system, a calculated or a system field (`cloneableInputs`); the record type is the original's. It is an ordinary new
  record: the server judges it like any other. An unreadable original gives an empty form.
- **Share** opens the record's own sharing (`/records/{id}/shares`, `/owner`: ADR-0117); people are named, never shown as identifiers.
- **Related lists** read through `POST .../records/query` filtered on the link with `EQ`, the page's `limit` (5 unless set) rows; when
  there are more, **View all** opens the related object's own list narrowed to this record (`?where=<field>:<id>`, names and an
  identifier only, never typed text; the list names the record in its filter chip). The first column leads each row as the link: a
  configured list has columns an administrator chose, and the platform's own list starts with what names a record, so no second rule for
  "what names a row" is needed.
- A tab that was opened stays drawn (hidden) so a form half filled in is not lost by looking at another tab. A record opened inside an
  application keeps the application open (Clone and a saved copy return there).

### Custom tabs and the access token

A `TAB` component (a tab of the organization, Sprint 18) draws the same sandboxed frame. The token a script holds already reaches the data
interface under `/objects/...`, which includes this descriptor; it describes only what the viewer may see, so nothing needs to change.

## Not decided here

- **Form factor** (the mockup labels some pages "Mobile only"): deferred; the assignment has no such dimension.
- A group name in the sharing list (a group reads "A group" until a group name can be asked), a count of related rows, activity and history
  components, an application dimension for record pages (a real fork the owner declined for now), custom buttons.

## Consequences

- One migration (V052), two settings, one table in the isolation harness (`TenantScopedTables`), one new endpoint pair family
  (`record-page-assignments`, `record-page-resolution`) and one member endpoint (`record-page`); no new module edge.
- Tested: `RecordPageAssignmentIT`, `RecordPageViewsIT` (the exit criterion: the page differs by record type and profile as configured),
  `RecordPageVisibilityTest`, the endpoint matrix in `DefinitionsAccessIT`; frontend `RecordPageView`, `RecordDetails`, `SharePanel`,
  `RecordFiles`, `RecordPagesPanel` and the clone and address helpers.
