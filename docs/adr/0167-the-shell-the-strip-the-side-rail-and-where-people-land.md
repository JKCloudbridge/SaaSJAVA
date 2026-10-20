# ADR-0167: The shell: the strip, the side rail with groups, and where people land

- **Status:** Accepted
- **Date:** 2026-10-20
- **Sprint:** UA1 (UI Alignment track, not a numbered sprint)
- **Related:** ADR-0126 (the visual design system), ADR-0127 and ADR-0128 (the strip, the launcher and security-aware navigation,
  Sprint 18), ADR-0132 (builders and state conventions), ADR-0039 to ADR-0045 (abilities)

## Context

Sprint 12A restyled the screens and kept the structure: a permanent left list (`ShellNav`: Home, Members, Approvals, Audit trail, Setup)
and a 14-link rail in every setup page (`SetupFrame`). The approved design has neither. It has one strip under the header with the
launcher and two tabs, **Home** and **Object settings**, and one rail for Home. The user decided (2026-10-20) that the design is a
reference for look and feel only and that every screen, address and function of every sprint stays. This sprint builds the frame; the
pages inside it are not changed (UA2 and UA3 do that). It is frontend only: no API, migration or ability changed.

## Decision

### 1. One place decides where the person is: `lib/shell/areas.ts`

A pure module holds the rail definitions, the abilities each entry needs, the five areas (`application`, `home`, `objects`, `console`,
`neutral`) and the landing rule. Components draw what it returns; the strip, the rail and the landing never disagree because they ask
the same functions. It decides nothing about permission: the abilities are the list the API gives for the signed-in member, and every
page still answers in the API's own words when it refuses.

### 2. The strip follows the area

- **Settings** (Home pages, object pages): the launcher and two tabs, Home and Object settings; no gear. A tab is drawn only when the
  abilities give the person a page behind it. Home leads to the first page of the rail they may open; Object settings to the list of
  objects.
- **Application:** the launcher, the application's name, its pages as tabs, and the gear.
- **Neutral pages** (the approvals inbox, notifications, a record screen outside an application, switching organization): the
  launcher, the name of the area, and the gear.
- **The gear and both tabs** exist only for a person who holds at least one ability a setup page is for (`canSetUp`). The gear leads to
  the first page of settings they may open, never to the "General" page.
- **The platform console:** the strip names the area, with no launcher (the platform has no applications) and no setup tabs.
- The tabs are pills, as the design shows (the Sprint 18 underline is gone).

### 3. One side rail with groups, one level deep

`SideRail` is the single rail component (Home now, the console now, the object's rail in UA3). Entries are links; some are groups:
a quiet main item with a small arrow, which is a **button** (`aria-expanded`, `aria-controls`) that opens its links one below the
other, with a thin guide line. It is an accordion: opening one closes the others. Arriving on a page opens the group that holds it
and closes the others; arriving on a page that stands alone leaves the groups as they are. A closed group is `inert` and
`aria-hidden`, so its links cannot be reached by keyboard. A group never holds a group. The open and close movement is a CSS grid-row
transition (no measured height) and the global reduced-motion rule switches it off.

On a narrow screen the rail becomes a button ("Home · Members") that opens the same list, groups included, below it; it closes when a
page is chosen. A row of tabs could not hold groups.

The rail sits in the shell next to the content (`ShellRail`), so the page title is beside it as in the design and no page needed a
frame of its own. The 21 pages that wrapped their content in `SetupFrame` lost the wrapper. The change-set selector is placed by the
address (`WorkingSetSlot`, above the title) on the same seven groups of pages as before.

### 4. Which entries a person is offered

An entry needs the ability the API asks for the first call its page makes (`homeRail`). Profiles, Access policies, Roles and Groups
use `access.manage` (the list of profiles and roles also accepts `members.view` and `members.invite`, but these are pages for managing
access). A group with no entry the person may open is not drawn; "General" alone is no reason to draw Home. This is new in the browser
(the old setup rail drew every link) and is a convenience only.

### 5. Where people land (`/`)

- A signed-in member of an organization: the **first application the API lists**, whoever they are.
- With none: a person who may set things up lands on **General** (`/setup/general`); anybody else stays on a plain page that says no
  application is available yet, with the launcher in the strip.
- Nothing is decided before the session and the list of applications have arrived (no flash, no redirect that is taken back); a failed
  list shows its message and "Try again" and never lands on a guess.
- Nobody signed in, and anybody on the platform's address: the welcome page. A person with a platform role finds a "Platform console"
  link there. There is no automatic jump to the console, because the same page holds "Create an organization".
- The brand in the header leads to `/`, since the "Home" link of the left list is gone.

### 6. "General" is a page that says it is not built

"General" used to be a marker with no link. The user asked to land on it, so it is a page (`/setup/general`) with no control that says
nothing is to be set yet. Branding and general settings stay a later backend feature.

### 7. The launcher grows with the list

The grid has two columns for up to four tiles, three up to nine, four beyond that; the panel is as wide as its columns and scrolls when
taller than the screen. A person who may set things up gets a tile "New application" (`/setup/applications/new`) beside "Manage
applications".

### 8. The approvals entry is in the header

`ApprovalsLink` sits beside the bell with its waiting count, with the same rules as before (the API says whether to show it; a failure
shows nothing).

## Consequences

- Every address is kept. The one new address is `/setup/general`.
- A member with no setup ability no longer sees Members: the page needs `members.view` and the API refused it anyway.
- A person with only `workflows.view`, `approvals.view`, `automation.view` or `audit.view` now has the gear and the Home tab (before,
  the Setup link needed `access.manage` or `metadata.view`).
- `ShellNav`, `SetupFrame` and `lib/applications/setupLink.ts` are deleted; `ConsoleFrame` keeps only its sign-in and role checks.
- UA3 renders the same `SideRail` for an object's own rail by extending `areas.ts`.
- `:has()` is used for the shell grid (rail present or not); every supported browser has it.
