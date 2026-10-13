# ADR-0091: The write pipeline and its order

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0049](0049-object-and-field-permissions.md), [ADR-0050](0050-the-permission-decision-api.md), [ADR-0064](0064-record-types.md), [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md), [ADR-0084](0084-the-record-store.md), [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md), [ADR-0092](0092-field-security-on-writes-and-reads.md) to [ADR-0097](0097-links-between-records-on-write.md)

## Context

Sprint 14 put records into the platform with a deliberately narrow write: the object, the keys, the shape of a value and the permission on the object (ADR-0089). Sprint 15 puts the rest of the pipeline of the
architecture notes between the gate and the storage (note 4, sections 20, 21 and 38; delivery plan S15), so that the backend, which is authoritative, decides everything about a value before it is kept.

## Decision

**The stages, in this order.** `RecordGate` (Sprint 14) is first and unchanged: who asks, which organization, which object, may they use it. Then `WritePipeline` runs, for a create and for an update:

| # | Stage | What it decides | Class |
|---|-------|-----------------|-------|
| 1 | shape | every key is shaped like a field name; every value is a text, a decimal, true or false, or a flat list of those; sizes | `RecordValues` |
| 2 | record type | the type named exists and is in use; a create without one gets the default type of the object | `RecordTypeCheck` |
| 3 | field permission | every field named is one the member may edit and one people can write; the record type offers it | `FieldSecurity`, `RecordTypeCheck` |
| (3b) | record permission | **Sprint 17** goes here: between the gate and the field permission, so the seam is "after the gate, before `FieldSecurity.checkWrite`" | — |
| 4 | type | each value belongs in its field's type and configuration; a picklist value is one the record type allows | `FieldValue`, `RecordTypeCheck` |
| 5 | required | a required field and a master-detail have a value after the write | `WritePipeline` |
| 6 | relationship | a linked record exists, is live and is of the target object, in this organization | `WritePipeline`, `StorageStrategy.liveIds` |
| 7 | unique | no other live record holds the value of a unique field, or links the same two parents of a junction (Sprint 16A, ADR-0111, ADR-0112) | `WritePipeline`, `RecordSlots`, `StorageStrategy.valueTaken` |
| 8 | rule | every active validation rule is judged | `Calculations` |
| 9 | formula | formulas are calculated in order and stored with the record | `Calculations` |
| – | persist | auto-numbers are taken, the seam for typed copies is called, the record is stored, audit and event are written | `AutoNumbers`, `RecordSlots`, `DefaultRecords` |

`WritePipeline.STAGES` lists the stages and a test holds the order (`WritePipelineStagesIT`).

**One answer per stage, and it stops at the first stage that finds a problem.** A stage reports every problem it finds (each with its place: a field's API name, or `fields` for the record as a whole) in one `VALIDATION_ERROR`, as the metadata
endpoints do; the pipeline does not go on to the next stage, because a later stage would judge values an earlier one has already called wrong. Nothing is changed by a refused write.

**Update judges the record after the change.** An update reads the current record first (a record that does not exist, is in the recycle bin or has another version is answered before any value is judged, as in Sprint 14), merges
the change into it and runs stages 4 to 9 on the result. The storage still merges in the database and still checks the version: a lost race is `CONCURRENT_MODIFICATION` and changes nothing.

**Defaults and empty text.** On create, a field default (ADR-0060) applies when the key is absent and the field is one the record type offers; it is validated like a value typed by a person and applies even to a field the member
may not edit (the default is the organization's choice, not the member's). An empty text is no value: it clears the field and never satisfies a required field.

**Messages.** A message says what is expected ("Enter a number.", "A value is required."); it never repeats a value, a label or a key that is not shaped like a field name.

**Dependencies.** `data -> metadata` (the object definition, `MetadataResolver`, record types), `data -> security` (`Decisions`), `data -> tenant`, `data -> sharedkernel` exist. This sprint adds **`data -> observability`** (to report a rule that
cannot be evaluated, ADR-0094). It does **not** add `data -> expression`: the delivery plan and `modules.md` allow it, but the compiled rules and formulas arrive through `MetadataResolver` as objects that take and give plain values
(`BigDecimal`, `String`, `Boolean`, `LocalDate`, `Instant`, `LocalTime`), so `data` never touches the engine and the sandbox rule of the engine (ADR-0076) cannot be bypassed from here.

**The seam for typed copies of indexed fields.** `RecordSlots.copyIndexed(object, values)` is called once per create and per update, after every stage has passed and before the record is stored, with the values the record will have.
It answers nothing in Sprint 15 (no slot is assigned). Sprint 16 plugs in here: for a field flagged "indexed" it answers which of the eleven slot columns holds a copy, and adds the eleven slot parameters to the two statements that write
a record (`INSERT` and `UPDATE` in `JsonbRecordStorage`) so that the copy is written in the same statement as the payload. `RecordSlotsIT` proves the seam is called on both paths.

## Consequences

- A record that passes the pipeline reads back exactly as it was written, with formulas and auto-numbers calculated, for a member who may read all its fields (`WritePipelineStagesIT`).
- The write costs more than in Sprint 14: one `accessTo` call (cached since Sprint 9), at most one existence query per target object of the links written, at most one counter statement per auto-number field, and the evaluation of the
  object's rules and formulas, which are compiled once per metadata version and bounded (ADR-0076). The benchmark's write scenarios are the yardstick; the full benchmark was not run for this sprint.
- Rules that read a formula field judge the value this save gives that field (ADR-0094).
- Built later: uniqueness of a field (needs the index policy; Sprint 16A, the stage "unique" above), links to objects a platform module owns (Sprint 15A, ADR-0099), what deleting a record that others point at does (Sprint 16, ADR-0106). Not built yet: record-level security (Sprint 17).

## Alternatives considered

- **Stop at the first problem.** Simpler, but a person fixes one thing per attempt. Rejected: the metadata endpoints already report all problems of a request.
- **Report problems of all stages at once.** Rejected: a type error would be followed by a rule judging a value of the wrong type, which is noise at best.
- **Run the stages in the storage's SQL (triggers).** Rejected: the rules are metadata of the organization and the expression engine is not a database function (ADR-0076).
