# ADR-0093: Type validation and record types on records

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0060](0060-field-types-and-their-configuration.md), [ADR-0064](0064-record-types.md), [ADR-0071](0071-which-layout-a-person-gets.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Context

A field has a type and a configuration (ADR-0060); the record store keeps JSON (ADR-0084). Sprint 14 kept whatever had a safe shape. Sprint 15 makes a value belong to its field, and makes the `record_type` column that Sprint 14
stored and never checked mean something (ADR-0064).

## Decision: what a value must be

`FieldValue` holds the rules in one place; they are the rules of `FieldRules` in the metadata module, which also decide what a default may be, so a default accepted when the field was saved passes here.

| Type | Stored as | Refused when |
|------|-----------|--------------|
| text, long text | text | not text; longer than the field's `maxLength` |
| e-mail, phone, URL | text | not text; not shaped like one (an address with one `@` and a dot after it; digits and `+ ( ) - . /` and spaces; `http://` or `https://` and no space); longer than 254, 40, 2048 characters |
| number | whole decimal | not a number; has decimals; has more digits than `digits` |
| decimal, currency, percent | decimal | not a number; more decimals than `scale`; more digits before the point than `precision - scale` |
| checkbox | true or false | not true or false |
| date, date and time, time | `2030-01-31`, `2030-01-31T09:30:00Z` (UTC, normalised), `09:30` | not text, or not a real date or time |
| picklist | the value | not a value of the list, or a value that is switched off and the record does not hold it already |
| multi-picklist | list of values | not a list; empty; a value twice; a value not in the list or switched off (unless held already) |
| lookup, master-detail | the identifier as text | not an identifier (whether it points at a record is the relationship stage, ADR-0097) |
| formula, auto-number | — | cannot be written (ADR-0095, ADR-0096) |

A value that is switched off in a picklist is accepted where the record **holds it already** (so existing records keep reading and saving after a value is switched off). A stored value of the wrong kind that was written before the
pipeline (Sprint 14 data) reads as "no value" when a rule or formula looks at it; nothing is rewritten.

## Decision: record types on records

1. **The type of a record** is stored in `object_record.record_type`. An object with no record types has records with none; a write that names one is refused ("This object has no such record type.").
2. **Create:** the type named in the request; with none named, the object's default type if it has one that is in use, otherwise none. The type must exist and be in use. **Update:** `recordType` in the request moves the record to
   that type (the same rules); without it the record keeps its type. A record keeps a type that has since been switched off (not rewritten), but no other record can be given it.
3. **A type offers fields:** a write may name only fields the type offers (a type that offers every field restricts nothing). Clearing a field the type does not offer is allowed (clean-up). A restricted type already offers every required
   and master-detail field (ADR-0064), so a required field is never one the type hides.
4. **A type allows picklist values:** for a picklist field the type lists, a value must be in that subset, unless the record holds it already. A picklist the type does not list allows all its values.
5. **Which layout a person sees for a record type and profile** is unchanged (ADR-0071: profile with record type, record type, profile, object default). That is a different question from *which record types a profile may choose*; see below.

## Decision: record types and profiles (differs from the sprint prompt; built in Sprint 15A as [ADR-0098](0098-record-type-assignments-which-profiles-may-use-which-record-type.md))

The sprint prompt asks that a record type be checked against "the member's profile". Neither ADR-0064 nor the metadata has any link between a record type and a profile (only layout assignment names a profile), so there is nothing to
check against. The product owner asked, on 2026-10-12, that this behave like a production-grade CRM, where an administrator assigns the record types a profile may use. That is a new kind of definition (a table of record types per profile
that goes through the change-set lifecycle with its own change kinds, undo, dependency edges and schema) and it was **not part of Sprint 15's build**. It was built as the side sprint S15A with this design: *a record type with no profile
assignments is open to every profile; with at least one, only to those profiles; a profile may have a default among its types.* From Sprint 15A on the rule is enforced; in Sprint 15 every member who may create in an object could choose any
record type of it in use.

## Consequences

- The object manager's "available fields" and "picklist subsets" are now enforced, not stored only.
- A type cannot hide a required field, so a person can always complete a record of any type.
