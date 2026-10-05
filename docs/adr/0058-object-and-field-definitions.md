# ADR-0058: Object and field definitions: the model, API names, the system fields

- **Status:** Accepted
- **Date:** 2026-10-05
- **Sprint:** S10
- **Related:** [ADR-0049](0049-object-and-field-permissions.md) (point 1 is replaced in part: keys), [ADR-0059](0059-standard-metadata-in-definition-files.md),
  [ADR-0060](0060-field-types-and-their-configuration.md), [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md),
  [ADR-0062](0062-removing-objects-and-fields.md); architecture note 3 sections 3 to 9, 29 and 30; delivery plan S10; traceability R6, R7

## Context

The platform is metadata driven: what an organization's application is (its objects and fields) is data, not code. This sprint builds the first
half of that data: objects and fields. Records, relationships, layouts and the publish lifecycle come later (Sprints 11 to 14).

## Decision

1. **Two sources, one shape.** Standard objects and fields (defined by the platform, the same for everyone) and custom ones (defined by an
   organization) are the same Java types (`ObjectDefinition`, `FieldDefinition`) and are read together through one contract, `Metadata`
   (`metadata` module root package). Everything that consumes metadata later works on both.
2. **Every object and field has a label and an API name.** The label is what people read and can change. The API name is permanent and is what code,
   integrations and the record address use. Objects: a capital letter first (`Account`, `Employee__c`); fields: a lower case letter first
   (`accountId`, `salary__c`).
3. **An organization's own names always end in `__c`; the platform's never do.** The person types `Employee`, the platform stores `Employee__c`. A
   standard object or field added in a later release can therefore never clash with something an organization already made, and nobody has to
   rename anything to make room. The typed part is letters and digits with single underscores (no hyphen, because a formula in Sprint 13 would read
   `a-b` as a subtraction). Names are unique in an organization without regard to upper or lower case, both for objects and for the fields of one object.
4. **The API name is the key of permissions on data.** ADR-0049 named objects by lower case keys (`object-a`). That shape is widened by migration V032
   (permission keys and the audit object key accept upper case) so the key is exactly the API name; every value valid before is valid now (expand).
   Hyphens stay allowed in those three checks for old rows; the definition tables do not allow them.
5. **Tables (V031), all tenant-scoped (ADR-0015):** `object_definition`, `field_definition` and `metadata_version`. A field names its object by API name,
   not by identifier, because the object may be a standard one that has no row. A database guard keeps the API name, the object and the data type of a
   field from ever changing, makes a field of a custom object name a live custom object of the same organization, and keeps an object with live fields from
   being removed. Rows are never hard-deleted (soft delete, ADR-0010).
6. **System fields.** Every object, standard and custom, has `id`, `sequence`, `description`, `ownerId`, `createdAt`, `createdById`, `updatedAt`,
   `updatedById`. They are defined once (`_system-fields.yml`), are not stored per object, cannot be changed or removed by anybody but a platform release,
   and come first in the list of fields. `id` is the permanent identifier (a record address will be `/<object>/<id>`, Sprint 14 chooses its format);
   `sequence` is a running number per object that people can quote (counting arrives with the records); `ownerId` is where record sharing (Sprint 17) starts.
7. **Custom fields on standard objects are allowed** where the standard object says `extensible` (Account, Contact, Opportunity, Case): the
   organization's field lives in its own table and is added when the catalogue is assembled; the standard definition is untouched. A master-detail
   field can only be added to a custom object.
8. **Changes are immediate in this sprint.** Draft, validate, publish and versions are Sprint 11 (delivery plan); the architecture's `status` column is not
   added now and Sprint 11 can add it as an expand migration. Every change is audited and carries the version it was based on (optimistic concurrency).
9. **Module edge.** `metadata` depends on `sharedkernel`, `tenant`, `security` and `identity` (for the one question "may this member do this", the same
   helper the audit module uses). The `security` module still depends on nothing here: it asks its own `ObjectCatalog` contract, which `metadata`
   implements.
10. **Abilities** `metadata.view` (read) and `metadata.manage` (create, change, remove). Separate on purpose; the administrator profile holds both
    because it holds every ability. Platform people have no authority here (a platform role gives none inside an organization).

## Consequences

- The sample objects and `ConfiguredObjectCatalog` of Sprint 8 are gone; the tests make real objects (`TestObjects`) and the local profile creates a
  real `Employee` object.
- The audit viewer shows every definition change (`metadata.*`), written with the object and field name and never with a label.
- An object's API name cannot be changed, so a wrong name means removing the object and making it again (possible while it has no records).
