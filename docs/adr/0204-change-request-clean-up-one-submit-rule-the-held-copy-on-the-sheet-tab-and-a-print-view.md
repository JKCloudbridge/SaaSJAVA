# ADR-0204: Change request clean-up: one rule for Submit, places leave the screens, the revised copy is changed on the Sheet tab, and a print view of the register

- **Status:** Accepted
- **Date:** 2026-10-28
- **Track:** Functional (FA10, demo readiness). Additive only: two fields on the template lifecycle view, one field on the register
  view, one method on the generic `SheetPages` contract. No migration, no new module edge, no new ability, no new policy, no new
  endpoint.
- **Related:** ADR-0202 (the register, the revised copy, gapless Revisions), ADR-0191 (change requests and places), ADR-0199 (change
  requests reviewed by the approval engine), ADR-0201 (View and Download), ADR-0187 (approval hooks), ADR-0188 (the template hook)

## Context

After the controlled change of ADR-0202 was clicked through, five things were found to be awkward:

1. The asterisk on the Change requests tab was drawn outside the tab's box and made the row of tabs scroll sideways.
2. A request could still point at places on the sheet. With the register that is no longer wanted, and the grid carried a whole
   "annotate mode" only for it.
3. The request page had a link to the sheet, a long explanation, and a section that drew the sheet workspace of the revised copy
   inside the page.
4. The register could be read as a form and as a grid with grid lines, but not the way it looks on paper.
5. "Submit for approval" was offered for a published template and then refused when pressed, because the approval engine does not
   know the template's own rule.

## Decision

1. **The Submit rule lives in the server, once.** `TemplateSendRule` (cluster, internal) answers "can this template be sent now?"
   from what the sheet's versions add up to and whether the template is retired: not retired; never published (no version in use or
   retired before); no review pending; a draft exists. The lifecycle view carries the answer as `canSubmit` and a short fixed sentence
   `submitNote`; the template hook `TemplateApprovalHooks.onSubmit` asks the same method and refuses in the same words, so the button and
   the refusal cannot differ (a unit test and an integration test fail if they do). The hook stays the final judge. The screen greys
   Submit out when `canSubmit` is false and shows the sentence only as the hover text of the button; if the field is missing the old
   behaviour stays. Approve, Reject and the other buttons are never touched. The fill-in page and the change request page never draw
   this button (`submitButton={false}`), so they are unchanged.
   A side effect worth stating: a published template that has a held copy in draft used to be sendable through the template's own
   approval, which would have sent the held copy; the rule closes that.
2. **Places stay in the API and in old requests; they leave the screens.** The popup "Ask for a change" is the register alone, the
   request page lists no places, and the grid has no annotate mode (marks, column selection by label, Enter opening the popup). The
   request body still accepts `comments` with places and old requests keep theirs. Nothing was dropped from the server.
3. **The revised copy is changed on the Sheet tab.** The request page has two buttons in one row: *View Current Version* (the Sheet
   tab at the Revision in use) and *Create Revised Version*, which makes the copy with the rules and refusals of ADR-0202 and then
   opens the Sheet tab on it; once the copy exists and the person may change it the button reads *Open Revised Version*; for everybody
   else it is not drawn. The Sheet tab opens a chosen version from the address (`&version=<stored number>`). A person who may not see
   that copy gets "not found" in the words of an unknown number, because the sheet endpoints decide again. The Sheet tab already lets
   the people asked to decide the request see and change the copy, replace it from a spreadsheet and save.
   *Download as Excel* of the held copy: the people who change it may take it whatever "Allow download" says. `TemplateDownloads` already
   skipped the option for the held copy; the sheet export rule still asks the rule of the Sheet tab (`SheetOwners.mayReadHeld`), which
   shows the held copy only to the people asked to decide the request and the organization's administrator, so reading and changing are
   the same people and no second rule was added. A download is audited by template identifier, version number and size.
4. **The register has a print view, and the view is the server's drawing.** *View* on the request page opens a popup (the shared
   `Modal`, wide) with a white page the shape of the paper the Excel file is set for: its margins, merged boxes and borders, no grid
   lines and no headings, fitted to the width, read only, shown whole from top to bottom. It draws the `grid` already sent in the
   register view and the new `print` field: `SheetPages.print` returns a `PrintSettings` (paper, orientation, page size in
   millimetres, margins in inches) produced by `XlsxWriter.printSettings`, **the same method the file writer reads its page setup
   from**, so the view, the Excel page and the file have one source. The browser works out no layout of its own: margins are drawn as
   shares of the page width so the page scales down together on a narrow screen. A Print button was not built.
5. **The asterisk sits in the flow of its tab,** after the word, so the tab's own box contains it. The row of tabs keeps its scroll
   for the case where tabs truly do not fit.

## Consequences

- The two new fields on the lifecycle view and the one on the register view are additive. The OpenAPI document and the generated
  client are regenerated; nothing is edited by hand.
- A `SheetPages` implementation must provide `print`. The only implementation is `DefaultSheetPages`.
- Tests that stood on annotate mode, the places part of the popup, the "Where on the sheet" and "Revised sheet" sections, and the
  "Change requests" section under the sheet were removed or rewritten with the features.
- Not decided here and left as it was: places in the stored data (kept), the register's Excel page (still read only), the Revision
  shown on fill-in and report screens (a known gap of FA8).
