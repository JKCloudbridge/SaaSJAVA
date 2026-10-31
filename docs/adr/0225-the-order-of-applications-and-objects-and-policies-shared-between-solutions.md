# ADR-0225: The order of applications, and objects and access policies shared between solutions

- **Status:** Accepted. **Amended by [ADR-0226](0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md) (FA13B):** place 6 is Environment Management, which uses the department setup through this rule and has records of its own
- **Date:** 2026-10-30
- **Track:** Functional (FA13A). One migration (V086, five plan features), no endpoint, no new module edge, no new ability. The generic code
  holds no word of any one solution (`SolutionBoundaryTest`).
- **Related:** ADR-0181 (the solution framework; amended here), ADR-0203 (the grid, Home in every application, placeholders; amended here),
  ADR-0175 and ADR-0179 (the feature gate and its notes), ADR-0189 (shipped policies), ADR-0115 (who sees a record: nothing here adds a second
  rule), ADR-0012 (what is never logged)

## Context

The business wants five more tiles at the start of the grid: Document Management, CAPA, Audit Management, Supplier Management and Management
Core. Four of them are announced only. Document Management is real: it must show **the same documents** as the Quality Management System
(a template made in one is in the other), with the same screens, and be sold on a plan feature of its own, with or without the other.

Two things in the framework stood in the way. The grid shows the applications in the order the API gives them, and the loader read the
bundles by key, so a bundle could not say where it stands. And the validator let an object belong to exactly one bundle, required an object's
feature to equal that bundle's single feature, and let an entry or a right name only the bundle's own objects, so a second bundle could not
show the first one's records, and the first one's access policies existed only while the first one's feature was on.

## Decision

1. **Order is data.** A bundle may carry `order:`, a whole number from 1 to 999. The bundles are read in the order of that number (lowest
   first); two with the same number stand in the order of their keys; a bundle with no number stands after every bundle that has one, in the
   order of its key (so the existing bundles keep their present order). The server computes the order; the browser draws the list as it
   comes. Document Management to Management Core take 1 to 5; **6 is left free** for the next application (Environment Management, FA13B).
2. **An application may use objects another solution brings, and say so on both sides.**
   - The using bundle lists them under `uses:`; it may then name them in its entries (`object`, `readsObject`). It brings no object of its
     own for them and stores nothing twice.
   - The object's file lists the using bundle's feature under `alsoAvailableWith:`. The object then exists for an organization when its own
     feature **or any listed feature** is on (`ObjectDefinition.availableWith`, asked by `MetadataCatalogue` the way the single feature was:
     now, once per feature, never kept in the snapshot or the security cache). Without any of them it is "not found", in the same words as
     an unknown object, in every module that asks the catalogue.
   - The validator accepts the sharing only when both sides agree: a used object is brought by another bundle and lists the user's feature;
     a feature an object lists is the feature of a bundle that uses the object. Everything else stands (an unknown object, a placeholder that
     names an object, a unique application name, a feature that belongs to one bundle, sheet owners that are the bundle's own objects).
3. **An access policy may be shared the same way.** A policy lists `alsoAvailableWith:` features of other bundles. It is kept in an
   organization while its bundle's feature is on or any listed feature is on. The validator refuses a listed feature that is not another
   bundle's, and a policy that gives a right on an object the other bundle neither brings nor uses (a policy must never name an object an
   organization lacks when only that feature is on). Policies stay owned by their bundle (`managed_by` keeps the bundle key), are made once
   whichever switch is on, and are never removed. The fingerprint of a declaration is unchanged, so no existing policy is rewritten.
4. **Each tile is shown only when its own feature is on.** Showing an application is a separate question from keeping the policies and the
   objects it shares. `ShippedApplicationsService` asks each feature once per call.
5. **Five plan features, in no plan by default** (V086, copying V081): `document-management`, `capa`, `audit-management`,
   `supplier-management`, `management-core`. A platform administrator adds one to a plan or gives one organization an override. Each bundle's
   `removalNote` is what the console shows before a feature is taken away.
6. **A shipped application with only its Home is "coming soon".** `isComingSoon` in the web application is true when every function is
   announced, or when there is no function besides Home (a shipped application always has a Home; an application without a Home is the
   organization's own and is never coming soon). CAPA and Audit Management have a Home and nothing else.
7. **A link into the template pages names an application the organization has.** The template pages are the same in the Quality Management
   System and in Document Management, so a notice or an inbox row must not lead to a tile the organization lacks. The cluster's
   `QualityApplications` answers from the applications the organization has (first the Quality Management System, else Document Management).
   The web pages read the application from the address (`useApplication`) and build their addresses from it, so no page assumes
   `/apps/inspection`. The server's endpoints are unchanged and name no application: both applications read the same objects.

### The alternative rejected

Putting Document Management into the Quality Management System's own bundle as a second application with its own feature. It needs no
sharing rule, but a bundle has one folder and one set of policies, so Document Management could not have a folder of its own, the Quality
Management System's file would decide what Document Management is, and the object check "feature equals the bundle's feature" would have to
become "any feature of the bundle" in any case. The rule above is general (FA13B uses it so Environment Management can share the department
objects) and leaves each application a folder, a file and a switch of its own.

Also rejected: copying the four objects for Document Management (the business asked for the same data, and a copy would be a second set of
records and a second set of approval processes and numbers to keep in step).

## Consequences

- Because policies are shared, anyone whose access policies open the Quality Management System tabs also sees Document Management when its
  feature is on, and the reverse. Licence (seats), access policy and plan feature stay three separate mechanisms.
- Switching Document Management off while the Quality Management System stays on hides one tile and nothing else; the records stay.
  Switching both off makes the four objects "not found" like an unknown object.
- A policy shared with a feature is made the first time any member of such an organization asks for the menu, in a transaction of its own,
  as before; it is not made twice.
- The grid and the order cost nothing at run time beyond one feature question per feature per menu request.
- The Environment Management tile does not exist in this release (FA13B); its place, 6, is free.
