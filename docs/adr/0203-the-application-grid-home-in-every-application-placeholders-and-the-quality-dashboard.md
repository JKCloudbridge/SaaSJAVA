# ADR-0203: The application grid, Home in every application, the process applications as placeholders and the Quality Management System dashboard

- **Status:** Accepted. **Amended by [ADR-0226](0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md) (FA13B):** the dashboard endpoint takes the application and counts that application's own records. **Brought up to date in FA13A** (see "Changes after the first review" at the end); the order of applications and the sharing of objects between bundles are [ADR-0225](0225-the-order-of-applications-and-objects-and-policies-shared-between-solutions.md).
- **Date:** 2026-10-26
- **Track:** Functional (FA9, demo readiness). Two migrations (V081, V082), additive API fields (`group` on an application, `placeholder` on an
  entry), one additive endpoint of the cluster, two read methods of the generic sheets module. No new module edge, no new ability, no new
  policy, no chart library.
- **Related:** ADR-0181 (the solution framework), ADR-0167 (the shell), ADR-0171 (landing), ADR-0126 (the design system), ADR-0115 and
  ADR-0118 (who sees a record), ADR-0180 (names; its label for the application is replaced here), ADR-0012 (what is never logged),
  ADR-0192 and ADR-0200 to ADR-0202 (fill-ins, approvals, publishing, Revisions), decision D8 (the generic Dashboards feature stays parked)

## Context

The business wants to show every function of its quality system in a demo and run the real flow in one of them. Until now a member landed
directly in the first application, the launcher was a small popup, an application had no Home, and the Quality Management System (then called
Audit maintenance) had no page that said where things stand. The business's own list has sixteen applications with their tabs; fifteen of
them are not built.

## Decision

1. **Only labels change in the rename.** The application "Audit maintenance" is **Quality Management System** and its tab Templates is
   **Document Control**, Fill-ins is **Record Control**. The API name `inspection`, the feature key `inspection`, every page key and every
   address stay: permissions, saved views, bookmarks and notices point at them. V081 changes the name the console shows for the feature (a
   data change, the key stays). ADR-0180's label is replaced by this one; its other decisions stand.
2. **A bundle may list several applications.** `applications:` beside the one-application form `application:` (exactly one of the two). The
   validator checks that application names are unique across all bundles, that entry identities are unique inside an application, that a
   placeholder names no object and no read right, and that **the first entry of every shipped application is Home** (a platform entry with the
   page key `home`). A bundle with no objects and no access policies is valid: it holds no data, so there is nothing to protect.
3. **A placeholder is data.** `placeholder: true` on a platform entry travels through the shared-kernel view and the applications API
   (`NavigationEntryView.placeholder`); the browser draws "This function is being prepared" for it and hardcodes no list of applications or
   tabs. An application also carries an optional `group` (a plain word) that the grid uses for its chips and colours.
4. **The fifteen process applications are one more bundle, `processes`,** with its own plan feature `process-applications` (V081), so an
   organization can have them with or without the Quality Management System. They hold no data and every member of an organization with the
   feature sees them. Licence, access policy and plan feature stay three mechanisms. Turning a placeholder into a real tab changes the
   bundle's entry (a page key or an object, the marker removed) and registers a page in the web registry, and nothing else.
5. **Home.** An application with no page registered for the reserved key `home` gets a generic overview drawn from the entries the API lists
   (its tabs as cards, "Coming soon" where an entry is a placeholder). The Quality Management System registers its own Home: the dashboard.
   Applications an organization makes itself in Setup are not changed.
6. **The grid is the start page.** `/` on an organization address draws the grid in place (no redirect, so no loop and no flash), nothing
   is decided before the session and the list have arrived, and a person with no application still lands on General or on the plain "no
   application" page. The three lines in the strip open the same grid in a rounded window with a cross (changed after the first review; the grid at `/` stays)
   open); the popup is gone, there is one grid. A tile is a link, the arrow keys move by the columns that are drawn, `/` focuses the search,
   the last three applications opened are remembered in the browser only.
7. **The dashboard counts through the record store.** `POST /api/v1/quality-home/summary` (module `inspection`, no ability, the plan feature
   required) answers every tile and graph at once for a period (7d, 30d, 90d, 12m), a location and a department. **A number never includes
   a record the person may not read, and there is no second rule for who may see one:**
   - templates and fill-ins are the records the record store lists for this person (`Records.query`, fill-ins judged in batches of 100
     identifiers with the same stages as any list); change requests are counted by `ChangeRequestCounts`, the same rule as the tab's mark;
   - the sheets module gives the dates of versions and of fill-ins sent (`SheetLifecycle.dates`, `FillLifecycle.submittedBetween` and
     `awaitingReview`), never a grid or a cell;
   - the record store gives no total, so the headers are read page by page up to `platform.inspection.home.max-scanned` (50,000) and a
     number that reached it says "at least"; nothing is cached across people and no table of rolled-up numbers exists;
   - a section whose object the person cannot read at all is **absent**, never a zero; a section that failed is named in `problems`, the
     others are still answered (each section runs in a savepoint);
   - days are days in UTC (the platform keeps no time zone for an organization yet).
8. **Two small append-only records** (V082, forced row level security, a guard that lets only the owning act write and refuses any change
   or delete): `inspection_fill_department` (the departments of the filler, as codes, written once when a fill-in is started; fill-ins
   started earlier count as "Not recorded") and `inspection_template_event` (published, revised, taken back, retired; written by the acts;
   history starts with this release). Two partial indexes on `sheet_fill` serve the period and the waiting ones.
9. **No filter value, name, template number or department is logged, audited, put in an event or a metric.** The metric
   `platform.inspection.home{result}` records the duration and `ok`, `partial` or `refused`.
10. **The look.** Colours of series and sizes of the new screens are tokens (`--series-1` to `--series-6`, both themes, contrast checked by
    `tokens.test.ts`); every graph is plain SVG or HTML with a pattern as well as a colour where marks touch, labels at the marks, a
    tooltip by pointer and keyboard, a "View as table" switch built from the same numbers, and no motion for a person who asked for less.
    No chart library was added.

## Tile and graph rules

| Tile | Counts | Filters it follows | Opens |
|---|---|---|---|
| Waiting for approval | templates in review (the oldest waited N days) | none (unpublished) | templates, in review |
| My approval tasks | the header's own count | none | the approvals inbox |
| Open change requests | open or accepted, as the tab counts | none | change requests |
| Fill-ins waiting for review | sent with a review request (only if the organization has a process for fill-ins) | location, department | fill-ins that were sent |
| Active templates | templates in use | location, department (by where they are published) | templates, active |
| Fill-ins sent | last sent in the period; change against the period before | location, department (by the filler's recorded departments) | fill-ins that were sent |
| Templates published | published or revised in the period (each template once) | location, department | templates, active |
| Average days to decide | from sending to the decision, over versions decided in the period | none | none |
| Rejected | share of decided versions that were rejected | none | none |

Dropped: "never submitted" (a fill-in is counted only once it is sent, as the prompt said). A tile that does not follow a chosen filter says so.

## Consequences

- One more value in the applications API (`group`, `placeholder`), both optional; clients that ignore them keep working.
- The dashboard reads at most `max-scanned` headers and one batch of 100 identifiers per judged fill-in page; the statements on the sheet tables
  are index reads (`QualityHomeCostIT` records the numbers). The judgement of fill-ins by the record store is not measured on 50,000 headers
  (see the sprint document).
- The department graph is honest only from this release on; older fill-ins are one bar, "Not recorded", and are left out when a filter is on.
- "Published per month" counts from the release on (no event exists for earlier publishes).
- A future generic Dashboards feature (D8) would not reuse this endpoint: this is the dashboard of one application.

## Changes after the first review (checked in the code in FA13A)

What the first review changed, as the code now stands. Nothing here moves a decision above; the API, the data and the addresses are as decided.

- **The launcher is a window.** The three lines in the strip open the grid of applications in a rounded modal window with a cross
  (`AppLauncher`, the shared `Modal`); the page behind stays where it was; Escape, the cross and choosing an application close it. The
  grid at `/` is the same component. (Decision 6 first said the three lines were a link.)
- **A tile with nothing but its Home is "coming soon".** Since FA13A a shipped application whose only entry is Home carries the tag (ADR-0225).
- **Two looks of the dashboard.** A pair of buttons, Option 1 and Option 2, choose the look; the choice is remembered in the browser only
  (`home-look` in local storage, closed storage falls back to Option 1). Option 1 is the look described above. Option 2 is the cream look:
  a title (`Template approvals`) and a sentence of what waits and the slowest approval step above the filters, which become selects, its
  own series colours, and the tiles in the order of its design.
- **More tiles than the nine above.** Besides the nine, the page draws **Drafts in progress** and **Approved, not published**
  (counts of the status chart, drawn only when no location or department filter is on). Option 2 keeps its own list of eleven in its order.
- **More graphs.** A heat map of templates by level and by document type (a count in every cell, with a screen-reader sentence and a
  tooltip), the fill-ins graphs **Fill-ins by template** and **Fill-ins by department by template** in Option 1 and **Fill-ins by template
  and by department** in Option 2, and the days at each approval step; every graph keeps its table view.
- **The published number counts versions that were in use before the events were kept.** A version that went into use (active, replaced or
  retired) is counted as published on the day its approval ended when no event was written for it; a template that has both is counted once.
  This is why "Templates published" is not zero for a template published before V082.
- **The window and the background.** The grid page has a soft wash behind it (two blurred pools of the series colours, so the glass tiles
  have something to be glass over); the welcome and "no application" pages sit in a bordered rounded window (`.landing-window`). Colours
  come from the tokens only.
- **Wording.** The application, its tabs and its fixed words are the ones of the business: Quality Management System, Document Control,
  Record Control, Report, Management Review and Internal Audit (announced).
