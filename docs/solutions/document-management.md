# Document Management (key `document-management`)

Decision: [ADR-0225](../adr/0225-the-order-of-applications-and-objects-and-policies-shared-between-solutions.md) (FA13A). The shared parts are
described in [inspection.md](inspection.md); this page says only what differs. The first tile of the grid.

## What it is

The same screens and the **same records** as the Quality Management System: Home (the dashboard), Document Control, Change requests, Record
Control, Report, Management Review and Internal Audit (both announced) and the Department group entry. A template made in one is in the other,
because both show the same four objects (`InspectionTemplate`, `InspectionFill`, `InspectionChangeRequest`, `DepartmentGroup`). There is no copy
and no second set of approval processes, numbers or access policies.

## For the administrator

- The plan feature is **Document Management** (key `document-management`), in no plan by default. Give it like any feature: add it to a plan
  or give one organization an override in the console. Taking it away hides this tile only; nothing is deleted.
- It can be sold **without** the Quality Management System and the reverse. The four objects and the seven shipped access policies exist while
  **either** feature is on, and are made once.
- **Because the access policies are shared,** anyone who can open the Quality Management System tabs also sees Document Management, and the
  reverse. Give or take a tile with the plan feature, not with the access policies.
- Notices and inbox rows lead to the Quality Management System tab when the organization has it, and to Document Management when it has only
  that one.

## For the contributor

- The file is `platform-app/src/main/resources/solutions/document-management/solution.yml`: `order: 1`, `uses:` the four objects, one
  application whose entries are the Quality Management System's. It brings no object, no access policy and no code.
- The four object files and the seven policies in `solutions/inspection/` list `document-management` under `alsoAvailableWith`.
- The web registers the same pages under the second name (`documentManagement` in `platform-web/src/solutions/inspection/registry.ts`). A page
  asks which application the address names (`useApplication`) and builds its addresses from it; never write `/apps/inspection` in a page.
- A new page key for the Quality Management System must be added to the Document Management entries too, or it is missing there.
