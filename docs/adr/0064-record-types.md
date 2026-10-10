# ADR-0064: Record types

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0058](0058-object-and-field-definitions.md), [ADR-0059](0059-standard-metadata-in-definition-files.md), [ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md); architecture note 3 section 10

## Context

A record type is a variant of one object (New business, Renewal) that offers some of the fields and allows some of the picklist values, and later uses a page layout
and influences validation and workflow. The plan puts the record type in Sprint 11 and says it is also a standard object.

## Decision

1. **A record type is published metadata of an organization** (table `record_type`, V033: tenant-scoped, forced row level security, tenant guard, metadata-version
   trigger). Columns: the object (by API name, as fields do), its own API name (ends in `__c`, starts with a capital letter), label, description, `active`,
   `is_default`, a layout reference (empty placeholder until Sprint 12), `available_fields` (a JSON list, or none for "every field") and `picklist_values`
   (a JSON object from a picklist field to the values it allows). The lists are JSON, not child tables: the dependency graph reads whole snapshots, and no query
   needs to find a record type by a field.
2. **Which objects:** every object whose records are ordinary organization data (`managedBy` empty): the custom objects and Account, Contact, Opportunity, Case.
   An object whose records belong to the platform (User, Profile, Role ...) has none (CONFLICT in words).
3. **Rules** (`RecordTypeRules`, one place, problems reported together, nothing typed repeated): offered fields exist on the object; system fields are always
   there so are not kept in the list; a picklist subset names a picklist that is available, only active values of it, at least one, once; the default record
   type is active and **at most one per object** (the database says the same; making another the default takes the mark off the old one); up to 50 per object
   (`platform.metadata.limits.max-record-types-per-object`).
4. **A restricted record type must offer every required field and every master-detail field**, because a record of that type could never be valid otherwise. This is
   a dependency rule ([ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md)) checked after a change, so adding a required field to an object with a restricted record type is
   refused with the record type named, unless the same change set also updates the record type.
5. **Removing an object removes its record types** with it (counted in the audit record), like its fields; the database refuses to remove an object that still has
   live record types (`platform_object_definition_guard`, replaced by `create or replace function` in V033).
6. **Record type as a standard object:** `RecordType.yml` (`managedBy: metadata`, closed) describes the records of this object for the data engine: name, API name, object, active, default, layout. The loader now accepts the owner
   `metadata`. The **per-record `recordTypeId` field is not added to every object** now: how a record stores its type is the data engine's decision (Sprint 14); adding a
   lookup to every object today would only add a row to every permission matrix.
7. **Not built:** records of a type cannot exist yet, so a record type in use cannot be refused removal; the seam is `ObjectUsage` (a method for record types is added when the data module exists).

## Consequences

- Audit events `metadata.recordtype.created|updated|deleted` (by `Object.RecordType__c`, never by label or description).
- The catalogue snapshot carries record types, so the existing version-keyed cache serves them ([ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md)); `Metadata.recordTypes(object)` is the read contract.