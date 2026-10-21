# ADR-0180: Names: the module, the objects and the words on the screens

- **Status:** Proposed (the product owner agreed to the names on 2026-10-21 ("yes to rest"); this ADR records them for acceptance)
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0175](0175-how-a-platform-shipped-application-exists.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md) (the audit trail)

## Context

The word **"audit" already means the audit trail** in this platform: the module `audit`, the table `audit_record`, the endpoints `/audit-events`, the screen "Audit trail", the abilities `audit.view`. The business calls its function "audit maintenance" (templates for audits of equipment, branches and so on).
If code and screens used "audit" for both, a person looking for the trail would find templates and a developer would grep into the wrong module.

## Decision

| What | Name | Notes |
|---|---|---|
| **Cluster Java module** | `app.platform.inspection` | glue only: the hook implementation, the number series declaration, the policies, the endpoints that are the cluster's own (versions list, lifecycle summary, publish). It may depend on generic modules, **never the reverse** (`SolutionBoundaryTest`) |
| Generic modules | `app.platform.solution` (loader, gate, shipped bundles) and `app.platform.sheets` (the canonical grid, validation, version storage) | **no cluster word** inside; "sheet" and "grid" are the generic words |
| Objects (declared in the bundle) | **`InspectionTemplate`** (the header), **`InspectionFill`** (a fill-in; the following set), **`TemplateChangeRequest`** and **`TemplateChangeComment`** (FA5) | API names end `__c` only for an organization's own objects; these are the platform's, so they do not ([ADR-0058](0058-object-and-field-definitions.md)) |
| Tables | `sheet_version`, `sheet_fill` (generic, from `sheets`); the series table `number_series_counter` (generic, from `data`) | **no `audit_` prefix**: the track rule said a cluster's own tables start with `audit_`; this ADR **replaces that prefix by `inspection_`** for the cluster's own tables, if any |
| Plan feature | `inspection` | |
| Abilities | `templates.view`, `templates.publish`, `templates.retire` (the cluster's own; the generic ones are reused: `approvals.*`, `metadata.*`) | named after the thing, never "audit" |
| Web folders | `platform-web/src/solutions/inspection/` and `platform-web/src/components/sheets/` | |
| **The application's label** | **"Audit maintenance"** (the business's own word, kept as the product owner's name for the function) | the one place the word appears for people, as an application name; the audit trail screen stays "Audit trail" |
| Words on the screens inside it | the list is **"Templates"**; one is a **"template"**; its versions are **"versions"** with the tag **"Active"**; people who fill one in are **"fillers"** and what they make is a **"fill-in"** (screens: "Fill-ins"); a request to change a template is a **"change request"** with **"comments"** | never "audit" for a template, a fill-in or a version; "audit" in the cluster means only what the business means by auditing (the thing being audited), which the cluster's text does not need |
| The platform's trail, for contrast | "Audit trail" (screen), "audit record" (the entry) | unchanged |

## Consequences

- An architecture test (FA1) fails the build if a class, table, endpoint or ability outside module `audit` contains the word `audit` as a name part, with the application label and the business glossary file as the only allowed texts.
- The delivery plan's folder table says `audit-maintenance` for the bundle directory (`resources/solutions/audit-maintenance/`) and `app.platform.auditmaintenance` for the module; **both are renamed by this ADR to `inspection`** (the bundle key is `inspection`; the business-facing label is "Audit maintenance"). The delivery plan and the analysis are corrected in "Corrections after FA0".
- If the product owner prefers another word than "inspection" for the code, only this ADR and the folder names change, before FA1 starts.
