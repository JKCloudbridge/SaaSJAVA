# The process applications (key `processes`)

Decision: [ADR-0203](../adr/0203-the-application-grid-home-in-every-application-placeholders-and-the-quality-dashboard.md). The business's own
functions, announced before they are built, so a demo can show every application and tab. This page is for the **platform administrator**
(how to give or take them) and for the **contributor** (how to turn a placeholder into a real tab).

## For the administrator

- The plan feature is **Process applications** (key `process-applications`). Give it the way you give any feature: add it to a plan
  (Plans, open the plan, Change, tick it) or give one organization an override (Organizations, open it, Features). The local profile switches
  it on for `tenant-a` and leaves `tenant-b` without.
- An organization with the feature sees fifteen more applications in its grid. Every member sees them and their Home; they hold no data and
  have no access policy. Without the feature they are not there at all (the grid shows only the applications the person has).
- Each application opens on its **Home**: its name, a line saying it is being prepared, and a card for each tab. A tab opens a page that says
  "This function is being prepared" and links back to Home. Taking the feature away loses nothing.

## The applications

Marketing - New Business Development; New Product Development; Production; QA; Top Management; Purchasing; Stores; Packing - Dispatch &
Product Delivery; HR - Training; IT; Total Productive Maintenance; NPC; Tooling; Process Engineering; CVD. Their tabs are in
`platform-app/src/main/resources/solutions/processes/solution.yml`. The Quality Management System is the existing application of the
`inspection` solution and is not repeated here.

## For the contributor: turning a placeholder into a real tab

1. In `solutions/processes/solution.yml` (or the bundle of the solution that will own the function) change the entry: give it a real
   `pageKey` it already has (keep it: it is permanent) and, when the tab shows an object's records, an `object` (or `readsObject`); remove
   `placeholder: true`. If the function needs objects or access policies, the entry belongs in a bundle that brings them and has a plan
   feature of its own; do not add business objects to the placeholder bundle.
2. Register the page in the web registry of that solution (`platform-web/src/solutions/<key>/registry.ts`: the page key and its component).
3. Nothing else moves: the API, the strip and the grid already read the entries. The Home of the application keeps drawing the generic
   overview until a page is registered for the key `home`; the card of the tab loses its "Coming soon" tag by itself.
4. Add the page key to the test of the registry (`solutions.test.tsx` reads the bundle and fails until the two agree).
