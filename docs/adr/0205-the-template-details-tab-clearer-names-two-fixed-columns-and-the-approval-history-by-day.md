# ADR-0205: The template Details tab: clearer names, two fixed columns, and the approval history by day

- **Status:** Accepted
- **Date:** 2026-10-28
- **Track:** Functional (FA11A, demo readiness). Frontend, one object file and documents. No migration, no API change, no new ability,
  no new access policy, no new module edge.
- **Related:** ADR-0199 (change requests reviewed by the approval engine; the history of approvals), ADR-0201 (Revision wording),
  ADR-0202 (gapless Revisions, the saved Revision), ADR-0126 (the visual design system)

## Context

The Details tab of a template used the generic record layout. People read it as a document-control card, and the words did not match
the paper they know: "Template name", "Document", "Number", "Retired". The Revision sat apart from the document number, the date of
the Revision was shown as stored text, and the approval history drew one card for each request, so a day with several approvals
looked like several unrelated boxes.

## Decision

1. **Labels only.** The labels of five fields change: Document name (`name`), Document type (`documentType`), Document number
   (`number`), Obsolete (`retired`), Revision number (`revision`). Stored names, the API, the policies and the baseline are not touched
   (the baseline records types and flags, not labels). Existing records need nothing.
2. **The cluster owns the arrangement of the Details tab.** `templateDetails.ts` builds the sections from the form the API sent for the
   member: the left column is Document name, Level, Document type, Identity category, Identity sub-category, then Part code and Plan
   year; the right column is Document number, Revision number, Obsolete (last). Reading leaves out Part code and Plan year when the
   template has no value, and the Revision number before a first Revision is in use; editing offers every field the member can see, in
   the same order. A second section, System information, holds the date of the Revision in use and the system fields. The form never
   gains a field the member may not read: a field missing from the form is not drawn (FA6A).
3. **The generic screens learn one small thing.** A section may name its left column (`ArrangedSection.left`); `RecordForm` and
   `RecordDetails` then place each field by column and row (`record-grid-placed`, one column on a narrow screen). A section without
   `left` is drawn exactly as before. They also take `display`: words to show for a field the person cannot type, instead of the
   stored text.
4. **The Revision number is the one the Sheet tab writes.** The saved value when there is one; otherwise, for a template published
   before the Revision was saved, the Revision in use read from the sheet by the same rule (stored number minus one, two digits from
   00). Nothing before a first Revision is in use.
5. **The date of the Revision is formatted when it is shown.** It stays stored as text (no migration); the page shows it as the
   reader's date and time in their own time zone.
6. **The approval history is grouped by day.** `ApprovalHistoryList` makes one accordion item for each calendar day in the reader's
   time zone, newest first, the newest open, with the existing button and `aria-expanded`/`aria-controls` pattern. A day is one card:
   its approvals, newest first, in a plain list, each with a title, the time of day, a note and the flow the approval card already
   draws. The API already carries every date it needs (`startedAt`), so nothing was added. The component had one user, the template's
   approvals, so changing it benefits every user of it.

## Consequences

- A person who sees the tab sees the fields; one who must not, does not. A backend test (`SolutionLoaderTest`) holds the read lists of
  the shipped policies to the eleven fields of the tab.
- A later change of the number or category fields (FA11B) changes the form the API sends, not this arrangement.
- The test that looked for a label by its old text is updated; the baseline and the Java code are unchanged.
