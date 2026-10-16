# ADR-0132: Admin screens, the drag-and-drop builders and the state conventions

- **Status:** Accepted
- **Date:** 2026-10-16
- **Sprint:** S21
- **Related:** ADR-0075 (the setup screens), ADR-0069 to ADR-0075 (layouts, record pages, applications), ADR-0064 to ADR-0067 (change
  sets and releases), ADR-0098 (record types per profile), ADR-0115 to ADR-0118 (sharing), ADR-0127 and ADR-0128 (custom pages and
  tabs), ADR-0129 and ADR-0131 (what the screens ask the server)

## Context

By Sprint 20 every kind of definition could be made through the API and most through a form, but the forms were long stacks of
selects (a layout was a list of "choose a field" boxes, a record page's tabs were text inputs, an application's navigation was a list
of numbered fieldsets). Four things had no screen at all: the tabs and custom pages of Sprint 18, the choice of a record page per
profile and record type (Sprint 20), the grid of which profile may use which record type (Sprint 15A) and everything about sharing
(Sprint 17). The product owner's mockup shows the intended experience: palettes of chips that are dragged into sections, tabs and
navigation lists. The exit criterion is that an administrator sets up a new application end to end using only the screens.

## Decision

### One builder pattern, three builders

- A **builder** is a page with a palette of what can be added (categories, a search, chips) and a canvas it is added to (cards,
  rows). It exists for the **layout** (sections of fields), the **record page** (tabs of parts) and the **application** (an ordered
  navigation). They share `src/components/builder` (`Palette`, `DropZone`, `MoveButtons`, `BuilderShell`) and one look in
  `globals.css`; each has its own model in `src/lib/builder` (`layoutModel`, `pageTabs`, `navigationModel`).
- **Drag and drop is never the only way.** A chip is a focusable button that adds with Enter or Space to the card last chosen; every
  placed item has move earlier/later and take-out buttons named after the item. The drag is the browser's own (no library: a new
  dependency needs a reason, and the browser's events are enough). A drag carries a small typed payload (`dragPayload`); a drop that
  did not start in a builder is ignored, and the innermost drop zone takes the drop.
- The builders keep what exists: the forms that remain (rules, header, side panel, actions) are the old editors moved into
  `RecordPageEditors`, so nothing a page could say before is lost. A rule the form cannot edit (a combined one) is kept whole.

### Draft, preview and publish: no second mechanism

- While a person drags, the state is **interface state** in the page (React state). On **Save** the draft is turned into the API's
  own shape (`toDefinition`, `recordPageDefinitionOf`, `itemsFromEntries`) and sent as the same call as the old forms made: live (one
  release of one change) or, when a change set is chosen in the bar above (`WorkingSetBar`), as a change of that set. The Sprint 11
  change set **is** the draft/preview/publish mechanism; the builder adds none. **Preview** draws the unsaved draft with the existing
  `LayoutPreview` and `RecordPagePreview` (sample values, never records).
- A save is refused or accepted by the API alone. The builder shows hints (a required field not on the layout, an entry not finished)
  but never blocks on a rule of its own except what cannot be sent at all (a record page without a layout). Its words for a refusal are
  the API's, with the place that is wrong.
- **Unsaved work is guarded**: a badge, a two-step Cancel, and the browser's warning on closing the tab.

### The palette offers what the metadata API offers

- The layout palette lists the object's own fields, standard and custom (not the system fields, not retired ones) as
  `metadata.view` returns them. It does **not** filter by the administrator's own field permissions: a layout is structure, not data,
  and an administrator who cannot read a field must still be able to place it for the people who can. Permissions on data stay
  decided by the server for whoever opens the record (ADR-0129).
- The layout palette has **Fields only**. The mockup also shows a Buttons category; custom buttons have no backend and no decided scope
  (a parked sprint), so there is none, and the object page shows "Custom buttons" as a marker with no link, like "General" in the rail.

### Tabs, custom pages and where a page is shown

- `/setup/tabs` lists one row per object (implicit, nothing stored: any object can be a tab) and the custom tabs, and makes, changes and
  removes custom tabs and custom pages. A new tab can make its custom page in the same step: the script is uploaded first (a content-
  addressed asset, the same text twice is one asset, ADR-0127), then the page, then the tab. The "reference for server code" is
  accepted, stored and never read (decision D6 stays deferred). The platform never runs the script on its servers.
- A custom page is shown **through a tab**. On a record page, dropping a page chip uses the tab that already shows that page or marks
  one to be made; the tab (`name` taken from the page's name) is created **in the same save, before the page**, live or as the first
  change of the chosen change set. A chip taken out again makes no tab.
- The application builder palette lists **Objects**, **Custom tabs** and **Other** (heading, link, record page, finished in place). The
  built-in platform tabs of the mockup (dashboards, approvals, connections) are not offered: nothing exists to open yet.

### The object page is a row of sections

- The long stack of panels is one **section per subject** (fields, relationships, record types, page layouts, record pages, validation
  rules, sharing, details), chosen with `?section=` so a section can be linked to and the back button works. Builders open as pages of
  their own (`/setup/objects/<object>/layouts/<name>`, `.../record-pages/<name>`, `/setup/applications/<name>`; `new` starts one).
- New panels: **record page assignments** (cloned from `LayoutAssignmentsPanel`, with the record type alone as a choice because a
  record type holds no page), the **record type grid** (profiles by record types, with default and the deleted-profile row), and
  **Sharing** (default access, the hierarchy switch, rules by owner, group and condition).

### Sharing is not part of a change set

- Sharing has no change kind: it sits behind the security version (ADR-0053) and takes effect at the next question of every member.
  The Sharing section says so and the working set bar is not shown there, instead of letting the bar promise a draft that cannot exist.
  A rule by condition is edited as conditions on indexed fields (all or any, one level); anything deeper that the API holds is kept
  whole and shown in words.

## State conventions (for every later screen)

1. **Server state** (metadata, records, lists, saved views, the member's applications) is **fetched by the screen that shows it**, by
   the generated client, and **refetched after every change that succeeded** (`useAction`'s reload). A screen never edits its copy and
   calls it saved. A change carries the **version it read**; a lost race is the API's `CONCURRENT_MODIFICATION`, shown in its words.
2. **Interface state** (a drag in progress, which card adds go to, which panel is open, a draft not yet saved, a search box) is **local
   React state** of the component that owns it. It is never sent, never shared between screens and never stored. Where a draft must be
   compared with what was read (the unsaved badge), the screen keeps a snapshot of the saved shape, not a flag.
3. **Where something lives for a short time across pages** (the change set chosen) is the browser tab's `sessionStorage` behind one hook
   (`useWorkingSet`), never a context that holds metadata. Everything works without it.
4. **Models are pure.** What a drag means (place, move, reorder, remove) is a plain function over plain data in `src/lib/builder`, tested
   without a browser; components only translate events into those calls.
5. **The page decides nothing.** A button is offered when it makes sense to show it; whether it may work is the API's answer, shown as
   it comes.

## Consequences

- Nothing in the API changed: no migration, no new endpoint, no new ability. The OpenAPI document and the generated client are as they were.
- The old form panels for layouts, record pages and applications are gone; their tests moved to the builders. A page that was valid
  before opens in its builder unchanged and saves without differences (a test saves an untouched page and compares).
- Drag and drop is proven by events in component tests and by the pure models; the feel of it in each browser is checked by hand
  (`Sprint 21 manual testing.md`), as the sandbox of Sprint 18 and the record screens of Sprint 19 were checked in a real browser.

## Not decided here

- Custom buttons, built-in platform tabs and dashboards in the navigation: later sprints, when something exists to place.
- Editing a combined visibility rule or a nested sharing condition in a form.
