# ADR-0060: Field types and their configuration

- **Status:** Accepted
- **Date:** 2026-10-05
- **Sprint:** S10
- **Related:** [ADR-0058](0058-object-and-field-definitions.md), [ADR-0059](0059-standard-metadata-in-definition-files.md); architecture note 3 sections 6, 7, 18, 19;
  delivery plan S10, S13, S15; traceability R7

## Context

A field has a type, and each type needs different settings (a picklist has values, a lookup has a target). The architecture asks for specialized
configuration, not one table with fifty optional columns.

## Decision

1. **Nineteen types** (the V1 list of the requirements): text, long text, number, decimal, currency, percent, checkbox, date, date and time, time, e-mail,
   phone, URL, picklist, multi-select picklist, lookup, master-detail, formula, auto-number. The enum `FieldType` says, for each, which settings it takes and
   whether it can be required, unique or have a default. The object manager builds its form from `GET /api/v1/metadata/field-types`, so no list of types
   is repeated in the browser.
2. **One configuration shape per type family, a closed set** (`FieldConfiguration`, a sealed interface of eight records). Code that handles a field
   switches over them and the compiler reports a forgotten one. The API shows them as one flat `FieldSettings` record (every setting optional) and
   `FieldRules` checks that only the settings of the type are given.
3. **Stored as a small JSON object** in `field_definition.config` (keys are the setting names), written and read by `ConfigCodec` only. A missing key takes the
   type's default and an unknown key is ignored, so an old row stays readable by a newer release. Values of a picklist are part of that JSON (a picklist is
   edited as a whole and moves with its field's version), not a table of their own.
4. **The rules** (all in `FieldRules`, unit-tested table by table):
   text 1 to 255 characters (default 255); long text up to 100000 (default 32000); e-mail, phone, URL have fixed lengths (254, 40, 2048); number up to 18
   digits; decimal up to 18 digits, 0 to 10 after the point; currency 0 to 6 after the point (default 18 and 2); percent (default 8 and 2);
   auto-number prefix up to 10 characters, first number 0 to 999999999999, width 1 to 12; formula up to 4000 characters with a result type.
5. **Constraints.** *Required* is not offered for checkbox, formula and auto-number; a master-detail is always required. *Unique* is offered for text,
   number, decimal, e-mail, phone, URL; an auto-number is unique by nature. A default value is offered for every typed type, is checked against the type
   (a picklist default must be an active value), and a unique field cannot have one (every record would get the same value). Enforcement of required, unique
   and default on records is the write pipeline of Sprint 15.
6. **A picklist value is stored, labelled and either active or not.** Values keep their order. A value can be switched off or added; it can never be removed
   (records may hold it, and record types in Sprint 11 point to it by its value).
7. **Lookup and master-detail** store the API name of the target object, which must exist (standard or custom). The target never changes afterwards. A
   master-detail only goes on a custom object (or a standard definition of the platform), at most two per object, never to its own object. Their behaviour
   (what happens to the detail when the master goes, relationships in both directions) is Sprint 11.
8. **Formula and auto-number are stored only.** A formula keeps its text and the type of its result; nothing is parsed, calculated or run before the
   expression engine (Sprint 13) and the write pipeline (Sprint 15), and Sprint 13 must re-check every stored formula with its grammar. An auto-number keeps
   its prefix, first number and width; counting arrives with the records. No executable code is accepted from an organization (standing rule).
9. **The type of a field never changes** (database guard and service). Changing the type of a field that holds data is a migration of the data, which is a
   deliberate, separate feature.
10. **Limits per organization** (settings `platform.metadata.limits.*`): 200 custom objects, 500 custom fields per object, 1000 picklist values. A plan or
    licence can decide these later.

## Consequences

- Adding a type later is: a constant in `FieldType`, a configuration record, a rule in `FieldRules`, a line in the check of V031 (an expand migration), a
  case in `ConfigCodec` and the baseline; the guide lists the steps.
