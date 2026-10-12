# ADR-0071: Which layout a person gets (layout assignment and the order of the rules)

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0064](0064-record-types.md), [ADR-0069](0069-layouts-record-pages-applications-and-where-they-live.md)

## Decision (the order and the deleted profile were confirmed by the product owner on 2026-10-11)

1. **The order, first rule that applies wins:**
   1. the choice for the **profile together with the record type**;
   2. the **record type alone**, which is the `layout` of the record type itself (the placeholder `layout_ref` of Sprint 11 is now a real reference);
   3. the choice for the **profile alone**;
   4. the **default layout of the object**;
   5. otherwise no layout (`NONE`).
   The record type beats the profile because a record type is a business variant of the data; the profile only refines the form, within a record type, in rule 1.
2. **Where each rule is stored.** Rule 1 and 3 and 4 are rows of `layout_assignment` (profile and record type are optional columns; no profile is the default of the object; a record type
   needs a profile, because a layout for a record type alone is rule 2 and has one home only). Rule 2 is the record type's `layout`. One assignment per object, profile and record type (a unique
   index counts a missing profile or record type as part of the key).
3. **The profile is only an identifier**, with no foreign key. **Deleting a profile is never blocked** by an assignment (a rule of the product owner). Nobody holds a deleted profile, so its assignment
   simply never applies and the next rule decides; the setup screen shows it without a name ("a profile that was deleted") and offers to remove it. The profile of a member is read through
   `MemberAccess.profileOf` (one identifier, no abilities), never derived from anything else.
4. **What the rules name must exist.** The layout of an assignment must exist on the same object, and its record type must exist: the dependency graph refuses a publication that breaks
   this and names the assignment ([ADR-0072](0072-the-dependency-graph-learns-layouts-pages-and-applications.md)). Resolution still skips a rule whose layout is gone, as a second line of defence.
5. **Asking.** `Metadata.layoutFor(object, profile, recordType)` returns the layout and the rule that chose it; `GET .../layout-resolution` shows it to those who manage metadata ("which layout would
   this person get?"). What the member's own request returns (the layout for the person, with fields filtered by permission) is Sprint 19 and 15.
6. **Whether a layout may show a field the person may not see (question 3, accepted).** The layout lists it; hiding it is the work of the permission decision (`Decisions`, field permissions) when
   a record is drawn (Sprint 15 decides what the API returns). The rendering proof of concept of this sprint hides only the fields the layout itself marks `HIDDEN`.

## Consequences

- A profile and a record type are chosen together only on the assignment; the form never offers a record type without a profile.
- Keys: `default`, the profile's identifier, or the identifier, a tilde and the record type's API name; they name an assignment in paths and in change sets.
