# ADR-0168: The Home screens in the list and detail pattern

- **Status:** Accepted
- **Date:** 2026-10-20
- **Sprint:** UA2 (UI Alignment track, not a numbered sprint)
- **Related:** ADR-0126 (the visual design system), ADR-0132 (builders and state conventions), ADR-0167 (the shell, the strip and the side
  rail), ADR-0039 to ADR-0052 (abilities and permissions), ADR-0145 to ADR-0153 (approvals)

## Context

UA1 gave the application a new frame: one strip and one side rail. The pages inside it still looked the way Sprint 12A left them: each Home
screen was one long page of stacked panels (a form, a table, another form). The approved design shows something calmer and uniform: every
list is a title with a search box and **New**, then a table; a row opens a detail page with a breadcrumb back; **New** opens the same page as a
form. The user decided (2026-10-20) that the design is a reference for look and feel only and that every function, address and test of every
sprint stays. This sprint is frontend only: no API, migration or ability changed.

## Decision

### 1. One set of shared pieces, used by every Home screen (`components/ui/lists`)

- `PageFrame`: a quiet title band (breadcrumb, title, the tools at the right) and a body. A list, a detail page and a page that is neither (the
  counts of automation health, the audit timeline, a lookup) all sit in it, so they look and behave alike.
- `ListPage`: the title, a **search box** and **New** at the right, then a table with a quiet header row, the name in bold as the first column
  (it is the link or button that opens the row, and a click anywhere on the row does the same), the tags at the end, a hover on a row, and its
  own quiet sentences for "empty" and "no match". It takes columns, not markup, so no screen copies another's table.
- `DetailPage`, `DetailSection`, `DetailGrid`, `DetailItem`, `MissingDetail`: the breadcrumb back to the list, the title with its buttons at the
  right (Edit or Save and the screen's own actions), sections of label and value pairs in two columns (one on a narrow screen), and the plain
  page for a row that no longer exists.
- `Tag`: the status or type tag (quiet, on, ok, error) built from the existing badge classes.
- `useListRoute` and `useListScreen`: which row is open lives in the address of the page, `?open=<id>` or `?new=1`. Every address stays the
  same page; a row can be linked to; the back button returns to the list. `useListScreen` adds the one-call-at-a-time action runner and forgets
  the last notice when the person moves to another page of the same list. Whatever uses it sits under a Suspense boundary (the pages do).

The styles are in `globals.css` (section "List and detail pages", plus `.stat-band` for a band of figures), using tokens and existing classes
only.

### 2. The search box filters what the screen has loaded, and says so

"Showing 2 of 5" appears while a search narrows the list. A list that the API pages or filters itself keeps the API's filter bar and its paging
and has **no** search box, so the page never pretends to search everything: the audit trail, the workflow runs, the organizations of the
platform (the API's `search` parameter), and History (no box; a long history is a backend question). A server-side search of a long list would
be a backend addition and is not made here.

### 3. What is a list, a detail page, a form, and what stays as it is

| Screen | List | Row opens | New |
|--------|------|-----------|-----|
| Members, Profiles, Access policies, Roles, Groups, Tabs, Notification text, Change sets, History, console Plans, console Platform people | in the pane | a detail page in the same pane (`?open=`) | the same page as a form (`?new=1`; none for Notification text and History) |
| Applications, Workflows, Approval processes, console Organizations | in the pane | the builder or the organization's page, at its own address | the builder empty (or, for a workflow and an approval process, the object is chosen in the title band first) |
| Workflow runs, Audit trail | the API's filter bar and paging | the run's page (its own address) | none |
| Automation health, console Sessions | not lists: they keep their content in the frame | | |

The builders (applications, workflows, approval processes, and the layout and record page builders that share the shell) get the same title band
through `BuilderShell`; what is inside a builder is unchanged. Row actions that are not "open" (switch on or off, remove, runs, requests) stay
as quiet buttons at the end of the row: they are functions of the sprints that built those screens and are not moved into a builder.

### 4. Functions kept, and where they went

Nothing was removed. The things that lived only on a long page and could have been lost in the move, and where they are now:

- The S7 mark "waiting for a licence": a tag on the member's row and a sentence on the member's page.
- Licence totals (S6): a band of figures above the member list. Support access (S6) and leaving the organization: sections below it.
- Invitations (S5), with Send and Withdraw: a section below the member list.
- The permission matrices (S8, `DataAccessEditor`): a section of the profile's, the policy's and the member's page.
- The console's mandatory reason and the typed confirmation of closing an organization (S6): unchanged inside the organization's page
  (`ActionWithReason`); the sessions lookup keeps its required reason.
- The working-set selector stays where UA1 put it; drafting into a change set still works from every list that had it.

### 5. The server decides; the page offers

A button is drawn when the abilities the API lists for the member do not rule it out (`useOffers(ability)`): New, and the "start on this object"
control of workflows and approvals. This is a courtesy: it is false only when the list is known and lacks the ability; without a session it is true;
the API still decides and answers in its own words, which the page shows with the place that is wrong. The browser holds no list of types,
abilities or rules it was not given.

### 6. Things the API does not return are not shown

The design shows "Created by" and "Modified by". The API sends neither, so they are left out; they come with a small backend change when the
user asks. Likewise an application has no "status", and a release has no author, so the list shows what exists ("Seen by", "What it was").

## Consequences

- A new Home screen is a `ListPage`, a `DetailPage` and a form, not new markup and not new CSS. A new rail entry is one line in `lib/shell/areas.ts`.
- Tests of a screen click through to the detail page (`@/test/navigation` replaces `next/navigation` with an address that changes); the shared
  pieces have their own tests (search filters, empty and no match, a row opens, New opens, the API's refusal is shown).
- Old browser scripts that filled a form or pressed a button on the long page need one more click (open the row, or press New).
- Not decided here: Object settings (UA3), the record screens, the insides of the builders.
