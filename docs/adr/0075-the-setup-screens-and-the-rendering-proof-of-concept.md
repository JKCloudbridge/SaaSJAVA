# ADR-0075: The setup screens for definitions and the rendering proof of concept

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0069](0069-layouts-record-pages-applications-and-where-they-live.md), [ADR-0073](0073-applications-navigation-and-the-members-menu.md), delivery plan S12 and S21

## Decision

1. **Forms, not a builder.** The object page gets three panels in the style of the record types panel: **Page layouts** (sections, the fields in them, how each field behaves), **Which layout is used**
   (the assignments, and "which layout would this person get?") and **Record pages** (components in order, their place, a closed visibility rule, the actions). Applications have their own page
   (`/setup/applications`). The record type form gets a choice of layout. The drag-and-drop builder is Sprint 21.
2. **Every change goes through the working set** (`WorkingSetBar`, [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md)): live and at once, or as a draft in the chosen change set that is checked,
   previewed and published with the others. The change kinds of the new things have words in the change set and history screens.
3. **The browser decides nothing.** It does not know which fields a layout must show, whether a related list follows a relationship, or whether an ability exists; the API refuses with its own words and
   the place that is wrong (`sections[1].fields[0].behaviour: ...`), and the page prints them. A rule that is a group of conditions (`all`/`any`) is shown in words and **kept as it is** when the page
   is saved; the form edits one condition or one ability, which is what a person can build without the expression engine.
4. **The rendering proof of concept** (exit of the frontend part): given a published layout and its object, `LayoutPreview` draws the sections and fields with made-up values by field type and marks
   read-only and required fields; given a record page, `RecordPagePreview` draws the components in order (header, the details of the layout, related lists as a table of columns with one sample row, tabs,
   activity, history), the side panel marked, each visibility rule in words and the actions. It shows **structure, not data** (no records exist) and says so. Real rendering with records is Sprints 19 and 20.
5. **What stays hardcoded and why:** the platform's own links in the shell, the setup menu, the list of component types, behaviours and rule operators in the forms (they are the closed lists of the
   schema, the same for every organization), the words for them. What an organization configured (applications, navigation, layouts) is never in the browser's code.

## Consequences

- The generated client has the types of the definitions from the OpenAPI document; the forms use them, so a change of the schema fails the type check where the form must follow.
- Tests: component tests for each panel and the preview with React strict mode where a panel reads its address; the browser script of the manual testing document drives the real screens.
