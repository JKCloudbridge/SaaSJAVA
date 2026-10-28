# ADR-0206: Identification with Part A and Part B, the typed start of a series, and the numbering information

- **Status:** Accepted
- **Date:** 2026-10-28
- **Track:** Functional (FA11B, demo readiness). One shipped object, the number patterns, two read endpoints, the template form and documents.
  No migration, no new ability, no new access policy, no new module edge.
- **Related:** ADR-0176 and ADR-0184 (number series), ADR-0188 (formats without a counter), ADR-0196 (the identity pair at the first
  approval), ADR-0197 (organization formats and counter continuity), ADR-0198 (numbering at Save, reservations), ADR-0205 (the Details tab)

## Context

The identity of a document was four fields: the identity category, a sub-category, a part code and a plan year. The sub-category list
(PRD, QLT, MNT, STR) was a placeholder borrowed from the department list: the category spreadsheet gives no such list, only "process
name abbreviation" (and a customer name for one kind). Plant QMS could not choose where the serial of a new combination starts, the
form always showed Part code and Plan year, and ten kinds of document of the spreadsheet were missing.

## Decision

1. **The identity is Identification, Part A and Part B.** `subCategory`, `partCode` and `planYear` are removed from the object, the
   policies, the Save-time rule, the formats, the screens and the tests (no organization uses the application yet, so nothing is converted).
   `category` keeps its name and is labelled **Identification**. `partA` and `partB` are free text, typed by the person who sets the
   identity: capital letters, digits and a hyphen, at most 10. That is the only rule; the screen corrects typing as it goes and the
   server refuses anything else in fixed words that repeat no value (Save), and at the first approval as a backstop for values an
   import could hold. What Part A and Part B mean is the pattern's business: in the spreadsheet Part A is the `XXX` (process or
   customer) and Part B the `YY` that is a part code or a year.
2. **Patterns stay where they are** (`numbering.yml`, with the organization overrides of ADR-0197): `{field:partA}`, `{field:partB}`
   and `{seq:N}`. One source feeds the number, the form and the check. The default for an identification an administrator adds reads
   Part B as its optional part; Part A is never optional for a shipped pattern, so a blank Part A means no number yet.
3. **The typed start.** `NumberSeries.start(series, values, first)` makes the counter of a scope that has none, at the number chosen,
   and returns that number; the counter continues from it. It takes the same shared lock as `next`, writes with `on conflict do
   nothing`, and a scope that already has a counter answers `ALREADY_STARTED` (a race is therefore told in plain words and changes
   nothing). A number already issued answers `NUMBER_TAKEN`. `NumberSeries.next` stays the one allocator for everything else.
4. **Where the serial is typed.** A small stored field `serial` (digits only, at most 12, edit right only in the Approver policy, fixed
   with the identity at the first approval) carries the typed first serial into the Save, where the write rule
   (`TemplateIdentityWrites`) sees only the record's fields. It is used only when the combination has no counter; otherwise it is
   ignored, so a stale value can never skip numbers. A combination saved without a typed serial starts at 1, as before. Nobody types
   into Document number: it stays platform-written.
5. **The numbering information.** `POST /api/v1/templates/numbering-info` takes the identification and the parts typed so far and
   answers which of Part A and Part B the format reads, whether it ends in a serial (and its width), the number written so far,
   whether the combination has numbers, and, only for a caller who may set the identification, the next serial and the whole number
   of a counted format. It is a read that reserves nothing, needs the right to read templates (not found otherwise, as for any
   template endpoint), and logs and audits nothing. The browser holds no pattern: the form shows Part A or Part B only when the answer
   says the format reads it, in reading as well as editing.
6. **The layout** (reading and editing): Document name; Level beside Document type; Identification beside the Document number (which
   builds itself as the parts are typed and ends in the serial box); Part A beside Part B; Revision number beside Obsolete. A section
   of the generic screens may name its rows (`ArrangedSection.rows`); a row of one field spans both columns, a row of two keeps each
   field in its own column when its partner is not drawn. The form gained three small, generic options: fields to hide, fields a
   page draws itself in their place (slots), and a callback with the text of its fields.
7. **The combinations that have a counter** are listed read only on the Numbering screen
   (`GET /api/v1/numbering/{series}/counters`, ability `numbering.manage`, 50 to a page, a plain page offset so that nothing typed
   is put in an address): what was typed, the count the next number gets and the count last given.
8. **Ten identifications are added** from the spreadsheet: R, R/EHS, EHS/AIR, EHS/HIR, EHS/AID, EHS/HID, EHS/AHID, PL/EHS, EHS/EMP,
   EHS/OCP (codes `R`, `REHS`, `EHSAIR`, `EHSHIR`, `EHSAID`, `EHSHID`, `EHSAHID`, `PLEHS`, `EHSEMP`, `EHSOCP`), with the formats the
   sheet shows; EHS/EMP and EHS/OCP have only the serial.

## Consequences

- The released-definition guard is updated on purpose: three fields were never used by an organization and are gone. The baseline and
  the generated list of standard objects changed in the same change.
- Part A and Part B are free text, so a typo (`PDR` for `PRD`) starts a new counter. The Numbering screen lists the combinations so an
  administrator can see it. A list the organization maintains is left out on purpose.
- The administrator keeps the Identification list in Setup (the existing organization-owned list) and gives each added value a
  pattern in Setup, Numbering.
- Not built: renumbering after the first approval (a separate act with its own ability and an ADR), lowering or deleting a counter,
  numbers of fill-ins.

## Tests

No database: `NumberingLoaderTest` (the patterns), `SolutionLoaderTest`, `TemplateApprovalHooksTest`, frontend `columns.test.ts`,
`numbering.test.ts`, `templateDetails.test.ts`, `TemplateNumberFields.test.tsx`, `TemplatesPage.test.tsx`,
`NumberingPanel.test.tsx`. Real PostgreSQL: `NumberSeriesIT` (plan, typed start, a second start, two real instances),
`TemplateSaveNumberingIT`, `TemplateNumberingIT` (every identification), `TemplateNumberingInfoIT`, `NumberingSettingsIT` (the
counters list), `ShippedPoliciesIT`, `OrganizationValuesIT` (ported to Level and Document type).
