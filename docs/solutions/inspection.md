# The Audit maintenance solution (key `inspection`)

The first platform-shipped solution (Functional track). This page is for two readers: the **platform administrator and organization administrator** who switch it on and use it (section 1), and the **contributor** who extends it in the next sprints (section 2). The framework behind it is in [ADR-0181](../adr/0181-the-solution-framework.md); the decisions about the cluster itself are ADR-0172 to ADR-0180. The word "audit" in code still means the audit trail: the cluster is called `inspection` in code and "Audit maintenance" for people (ADR-0180).

## 1. For administrators

### What exists in this release (FA4): the lifecycle of a template

A template now goes through its whole life.

1. **Draft.** A person who holds the **Author** policy makes a template (name, category, sub-category; for a control plan, a process flow chart or a failure mode analysis also the **part code**; for a business plan the **plan year**), starts its first version and builds the sheet. Only the author (and the people who approve) can see it: **a template is private**, and no setting changes that.
2. **Submit.** The author presses **Submit for approval** on the template's page. The approval process is the one the organization's administrator built for Template in the approval setup, with each step naming its approvers (by role, group or person). A draft that is still empty, a template without a category, a number that cannot be made yet, or a number somebody else already has, is refused in plain words before anything starts.
3. **Approve, and the number.** Each approver presses **Approve** on their task. **The first approval gives the template its number** (for example `WI/PRD/01`), once; a template rejected at the first step takes none, and a number is never given again. The approver sees "Approving this gives it its number, WI/PRD/…" while the approval is theirs.
4. **Approving is not publishing.** An approved version waits. Only the people who approve and the author can see it.
5. **Publish.** A person who holds the **Approver** policy presses **Publish**, chooses a **Location** and then a **Department** (the departments narrow to the location), adds as many as needed, and publishes. The template becomes visible to **the people of those departments** (the group the administrator set up for each) in the Templates list, and to nobody else. Publishing again shows the audience it has and keeps it unless you change it.
6. **Unpublish, a new version, retire.** An approver can take a template back from the people (**Unpublish**). The author starts a **new version** from the one in use; while it is in review the version in use stays readable and in use, and approving the new one does not publish it: publishing it replaces the old one at once. **Retire** ends a template for good: it is kept, its number is never given again, and the people who had it still read it. It cannot be retired while a version waits for approval.
7. **The master list.** The Templates tab lists the templates you may see with number, name, category, sub-category and lifecycle ("Active v3, v4 in review"), with search, filters (category, sub-category, status, retired) and an order. **New template** is shown to people who may create one.

While a version is in review, **only the people who approve it can change it** (they correct the sheet or the details; every correction is recorded in the audit trail by size and fingerprint, never by content). The author who submitted it cannot.

### What the organization's administrator sets up (FA4)

See `Sprint FA4 manual steps.md` for the steps in order. In short: give the policies **Author**, **Approver**, **Reader** and **Department setup** to the right people, roles or groups; build the approval process on Template; make one **public group** for each department and put its people in it; and add one **Department group** record for each Location and Department that names that group (Audit maintenance, Department groups). A department with no record, or with an empty group, cannot be published to and the dialog says so.

### What existed in FA3

Since FA3 a template has a **sheet**: open **Templates** in the Audit maintenance application, open a template (or choose **New template**), start its first version, build it in the Form view or the Grid view, save it and keep its versions. The full description for the administrator is [metadata-guide.md](../metadata-guide.md) section 27. In this release a version is only ever a **draft**: submitting for approval, the number, publishing and retiring come next (FA4A and FA4). The page lists the templates plainly (no filters, no lifecycle summary: the master list is FA4).

### What existed in FA2A

The framework (FA1) and, since FA2A, the first fields of the object and the numbering. The templates themselves, the grid, the approval and the publishing come in later sprints (FA3 to FA5).

**The object Template now has** a **Category**, a **Sub-category** (only the sub-categories that go with the category can be chosen) and a **Number** that nobody types. The lists of categories, sub-categories and which go together belong to the organization: the platform ships a starting list, the administrator changes it in the object manager (Object settings, Template, Fields, open the field, **Edit the values**), and a member who holds the ability **Add values to picklists** can add a new abbreviation from the form. The number is written in the format of the category (for example `WI/PRD/03`) and counts up for each category and sub-category, from 1; a newly added abbreviation starts at 1. The number is given by the platform when the template is approved, in a later sprint; in this release nothing gives a number yet. The starting list is a placeholder (the 16 categories of the product owner's scheme and a few generic sub-categories) and is replaced by the product owner's real list as a data-only change.

### What existed in FA1

Only the framework and its first object. An organization that has the feature sees an application called **Audit maintenance** in the application launcher, with one page, **Templates**, that says "nothing here yet". The object **Template** (`InspectionTemplate`, a name and a "retired" flag) exists and can be read through the usual record screens and API by a member who is allowed. The templates themselves, the grid, the approval, the numbers and the publishing come in later sprints (FA2A to FA5).

### How to give an organization the feature

1. Sign in on the platform address as a platform administrator and open the console.
2. Either add the feature **Audit maintenance** to a plan (Plans, open the plan, Change, tick the feature) or give one organization an override (Organizations, open it, Features, "Switch on"). There is no licence to buy and no quantity to set.
3. Taking the feature away shows the platform's own words first: the application and its objects are hidden at once, **nothing is deleted**, and everything comes back when the feature is given again.

### What the organization's administrator does

1. The five access policies **Author**, **Approver**, **Reader**, **Filler** and **Department setup** appear in Access policies on their own, marked "Comes with the platform", the first time anyone in the organization opens a page after the feature was gained. They can be given to members and groups; they cannot be changed or removed. They use no licence.
2. Until a person holds one of them they see nothing of the application. Since FA4 the policies hold the real rights (create and change a template, view every template, publish, retire, correct a version in review), described in section "What exists in this release (FA4)". Which people hold which is the administrator's decision: give **Author** to the roles that write templates and **Approver** to the roles that approve and publish.
3. The records of the Template object are **kept**: nobody can delete one (the delete right does not exist for this object), and a template that is no longer used is marked **Retired**.

### On a developer machine

The `local` profile switches the feature on for `tenant-a` and leaves `tenant-b` without it. Sign in at `http://tenant-a.localhost:3000` as `admin-a@example.test` and open the application launcher.

## 2. For contributors

| What | Where |
|------|-------|
| The bundle: the feature, the application, the entries, the policies | `platform-app/src/main/resources/solutions/inspection/solution.yml` |
| The shipped object (platform definition; read by the metadata module) | `platform-app/src/main/resources/solutions/inspection/objects/InspectionTemplate.yml` |
| The number series: the formats by category | `platform-app/src/main/resources/solutions/inspection/numbering.yml` ([metadata-guide.md](../metadata-guide.md) section 26.4) |
| The feature row | migration `V058__add_inspection_feature.sql` |
| The department mapping object (the group of a department of a location) | `platform-app/src/main/resources/solutions/inspection/objects/DepartmentGroup.yml` ([ADR-0190](../adr/0190-department-groups-the-object-group-and-the-mapping.md)) |
| The cluster's module: the hook, publishing, retiring, the master list | `platform-app/src/main/java/app/platform/inspection/` ([ADR-0188](../adr/0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md)); the table `inspection_publication` is migration `V066__create_inspection_publication.sql` |
| The page keys the web application knows | `platform-web/src/solutions/inspection/registry.ts` (the pages: `TemplatesPage.tsx` the master list, `TemplateDetail.tsx` one template with its lifecycle, `PublishDialog.tsx`, `lifecycle.ts`, `api.ts`) |
| The sheet: versions, validation, the grid | generic: module `sheets`, `platform-web/src/lib/sheets`, `platform-web/src/components/sheets` (no cluster word); the bundle names the owner in `sheetOwners` of `solution.yml` |
| The generic code | modules `solution`, `metadata`, `data`, `security`, `application` (no cluster word; `SolutionBoundaryTest`) |

**Add a page.** Add the page component under `platform-web/src/solutions/inspection/`, register its key in `registry.ts`, and add a `PLATFORM` entry with that `pageKey` (and the object whose read right shows it) to `solution.yml`. The frontend test compares the two lists.

**Add a field or an object.** Edit the object file; never delete or rename a released field (set `retired: true`); run the three commands of [metadata-guide.md](../metadata-guide.md) section 4. A new object also goes into the `objects` list of `solution.yml` with the same `requiresFeature`.

**Change a policy.** Edit it in `solution.yml`. Every organization that has the feature gets the new rights at its next page load (the stored fingerprint differs). Never give the delete right on an object whose records are kept; the loader refuses it.

**Rules to keep.** The cluster's words (`inspection`, "template", "audit maintenance") appear only in the places `SolutionBoundaryTest` lists; the generic modules say `sheet`, `version`, `fill`. Nothing in the bundle can be edited or removed by an organization. A page asks the API for everything: the browser decides nothing.
