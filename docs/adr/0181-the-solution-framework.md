# ADR-0181: The solution framework: shipped bundles, shipped policies and the retained-object rule

- **Status:** Accepted (the product owner approved the FA1 plan and its recommendations on 2026-10-21)
- **Date:** 2026-10-21
- **Sprint:** FA1 (Functional track, the first sprint that writes product code)
- **Related:** [ADR-0175](0175-how-a-platform-shipped-application-exists.md), [ADR-0174](0174-template-versions-and-approval-together.md) (section 7), [ADR-0179](0179-packaging-the-whole-organization-by-plan-feature.md), [ADR-0180](0180-names-the-module-the-objects-and-the-words.md), [ADR-0059](0059-standard-metadata-in-definition-files.md), [ADR-0045](0045-system-profiles-seeding-backfill-and-licence-consequences.md), [ADR-0053](0053-the-security-version-and-the-cache.md), [ADR-0061](0061-the-metadata-version-and-the-cache.md)

## Context

FA0 decided *what* a platform-shipped solution is: a computed application, platform-defined objects behind a plan feature, access policies as rows, and a rule that records of some objects are never deleted. FA1 builds the generic framework that carries
any such solution. This ADR records what building it decided beyond FA0, and the places where the code made a choice the ADRs left open. The framework has no word of any one solution in it; the first solution (the cluster named in ADR-0180) is only data.

## Decisions

### 1. The module `solution` and the bundle

A new module `solution` (allowed dependencies: `sharedkernel`, `tenant`, `licensing`, `metadata`, `security`) loads `resources/solutions/<key>/solution.yml` at start: `key` (equal to its folder), `feature`, `label`, `removalNote`, the application (`apiName`, `label`, `iconKey`, `entries`), the objects it brings, and its access policies.
`SolutionLoader` checks the shape of one file and `SolutionValidator` checks it against the platform (the feature exists, every object is a platform object that requires exactly that feature, every shipped object belongs to one solution, no key, feature or application name is shared, an entry or a right names an object the solution brings, no delete right on a kept object).
A mistake stops the start with a message that names the solution and the part and **never repeats a text** (a unit test tries it). The feature check needs the database, so it runs in an application runner right after the beans are made.

### 2. Where the shipped object files live, and `PlatformObjects`

The object files stay in `resources/solutions/<key>/objects/` (next to the solution that needs them), but the **metadata** module reads them (the generic pattern `solutions/*/objects/*.yml`, the same format and the same strict loader as the standard objects), not `solution`:
`solution` must read `metadata` to check names, so `metadata` cannot depend on `solution` without a cycle. The cost is one generic glob in the metadata loader; the benefit is that no cluster word enters `metadata`. `solution` reaches the definitions through a new public contract of `metadata`, **`PlatformObjects`** (every platform object, no tenant, no plan feature, askable at start).
The object attributes `requiresFeature` and `retainRecords` are new keys of the object file (baseline-guarded: dropping either from a released object fails the build). A shipped object is `extensible: false` and `managedBy` is left out, so its records are ordinary records of the generic store; an object another module owns cannot carry either attribute.

### 3. The gate: asked when asked, never stored

`Metadata.object`, `Metadata.objects`, `ObjectCatalog` and the object manager's own lists leave out a shipped object whose feature the organization lacks. **`Entitlements.enabled(feature)` is asked on each call**, once per feature per call, and is not part of the metadata snapshot (keyed by the metadata version, which a change of plan does not move) nor of the security cache (keyed by the security version).
An object that requires no feature pays nothing. Every module therefore answers "not found" for an unentitled object exactly as for an unknown one, with the same words (a test compares them). The cost is two or three small reads per call for a shipped object; measure before caching, and if it is cached it must be keyed by something a plan change moves.

### 4. The menu merges a computed application

The shared-kernel contract `ShippedApplications` (neutral view types `ShippedApplicationView`, `ShippedEntryView`) is implemented by `solution`; `MyApplications` merges it through an `ObjectProvider` (`application` has no edge to `solution`; an architecture test says so).
The solution answers only "which applications exist for this organization now" (the feature gate); **the menu judges every entry by the member's read permission with the one rule it uses for the organization's own applications.** An `OBJECT` entry needs read on its object; the new entry type **`PLATFORM`** (API field `pageKey`) carries no permission of its own, but may name an object whose read right shows it (`readsObject`), so a member who holds no policy of the solution sees nothing of it (ADR-0179).
An application whose entries all fall away is left out, as for the organization's own. The application name never ends in `__c`, so it cannot clash with an organization's. No row of the organization is written for the application, its entries or its pages (a test counts them).

### 5. The shipped access policies are made lazily, per organization, in a transaction of their own

Rows of `access_policy` marked `managed_by = 'solution'`, `managed_key` (`<solution>.<policy>`), `managed_hash` (V059). They are **made at the first menu request after the organization has the feature** (and brought up to date when a release changes what they give), not at start and not on a plan change: that needs no way across organizations,
no start-up sweep, no new system scope and no privileged connection (the plan said to prefer this). The menu runs read-only, so the write happens in a short transaction of its own (`REQUIRES_NEW`) under the organization's own context, after one cheap read (are the policies as declared, by fingerprint?); it takes the organization's access lock first, so any number of simultaneous requests and instances
make each policy once. A failure never reaches the person (the menu is drawn; the failure is counted and logged by its type and tried again next request). This replaces "in the same transaction like the system profiles", which is not possible from a read; the effect is the same (they exist before anyone can assign them, because assigning needs the list, and the list needs a menu request first in practice, and a plan change is visible to the next page load).
- **Not editable, not removable**: the API answers `CONFLICT` with fixed words for a change, a removal and a change of its permissions on data; the screens hide the controls; **the database refuses an insert, change or removal of a marked row** unless the transaction sets `platform.shipped_policy_write` (the pattern of `platform.record_recalculation`), and refuses turning an ordinary policy into a shipped one. Assigning to people and groups is unchanged.
- **Not licence-bound** (ADR-0179). **A lost feature leaves them in place.** A name an administrator's policy already has gets a suffix ("(platform)") rather than failing forever. A policy is never removed by a release.
- This sprint ships Author, Approver, Reader and Filler **named, with only the read right on the first object**; the real rights are filled in by FA4 when the roles table of the business is answered. No security-version trigger is needed: the rows that feed an answer (`access_policy`, `object_permission`) already carry it.

### 6. The retained-object rule: four layers, no bypass

An object that declares `retainRecords` has every record kept. 1. **The decision API** never allows delete on it (`can`, `accessTo`, `accessToAll`), whatever the member holds, including the administrator profile's full access and the delete that modify-all implies; the screens (which read these answers) hide the control, and the shown matrix leaves the right out.
2. **The access-policy editor and API** refuse the delete right on it (`VALIDATION_ERROR`, fixed words) for a profile, a policy and a member; the data catalogue says `retained` so the screen draws no control. 3. **The record store**: `RecordGate` answers `CONFLICT` "The records of this object are kept." for every route that asks for the delete action (delete, bulk delete, restore, purge, the recycle bin),
after the member is known to be able to read the object (so it tells a member who may not nothing); a cascade that would remove kept details is refused; the recycle-bin retention job skips kept objects in its statement. 4. **The database**: the platform table `retained_object` (insert-only, filled idempotently at start from the declarations by `RetainedObjects`) and two triggers on `object_record`: one refuses a physical delete, one refuses an update that sets `deleted_at`
(the record store soft-deletes, so a delete trigger alone would miss it) - for the application role and for the owner, with a `when` clause so an ordinary update pays nothing. A revoked grant would not hold (manual migration M001 re-grants); a trigger does. A name in the table that a release no longer declares stays protected and is reported at start.
- **What a kept record can still do:** be changed (including the `retired` field), shared, have files attached, be read; a restore (bringing a record back) is allowed by the database but there is nothing in the bin, since nothing can be binned.
- **Offboarding.** Today suspending or deactivating an organization deletes nothing, and the platform has no erase-an-organization feature (Sprint 34 plans export and erase). The rule has **no bypass**: the promise is "the product never deletes these records", not "the database owner cannot". Sprint 34's erasure design must add its own explicit, audited path (an ADR, a session setting like the ones above, a test); until then the only way to remove one is a person with the database owner's rights who disables the trigger.

### 7. The console and the words of removal

`FeatureNotes` (shared kernel) lets a solution give the sentence that says what taking its feature away does; `solution` implements it from the bundle's `removalNote`; the console's `CatalogueItem` and `EntitlementInfo` carry it (`removalNote`) and the plan and organization screens show it **before** the feature is taken away.
The browser holds no such text and decides nothing from it. The plan and organization screens read the `feature` table, so the new feature appears without code.

### 8. The web side

`platform-web/src/solutions/<key>/registry.ts` lists the page keys a `PLATFORM` entry may name and the page for each; `src/solutions/index.ts` collects them. The generic route draws the registered page under the entry's title, or the plain "This page is not available." page for an unknown application or key (including inherited property names). A test reads the bundle file and compares its page keys with the registry's.

### 9. Names and the boundary test

`SolutionBoundaryTest` (ADR-0180) fails the build when the cluster's words appear in generic code, resources or screens outside the listed places (the bundle folder, the feature migration, the cluster's web folder, the cluster module that does not exist yet);
when the new generic module uses the word "template"; and when "audit" names anything but the audit trail. **The "audit" rule is checked against a frozen list** (the trail's users that exist today: `AccessAudit`, `MetadataAudit`, `AuthAudit`, `PlatformAudit`, `ApprovalAudit` and their kin, the table `audit_record`, the endpoints `/audit-events`, the ability `audit.view`), because the trail is written from many modules and cannot literally be confined to module `audit`; a new name with "audit" fails until a person adds it.

## Consequences

- Migrations V058 (the plan feature `inspection`, in no plan by default), V059 (the shipped-policy marker and guard), V060 (`retained_object` and the two triggers). All expand-only.
- A second cluster is a folder of files plus its own module; nothing in `solution`, `metadata`, `data` or `security` changes. A solution's pages and cluster rules are written in its own module and web folder.
- The entitlement is read on every lookup of a shipped object; if a measurement shows it matters, cache it keyed by something the plan change moves, never by the metadata or security version.
- Not tried here: the cascade refusal of kept details (no master-detail to a kept object exists yet) and attaching files to a kept record (the file tests need an object store); both follow in the sprints that add the objects that need them.
