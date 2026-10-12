# ADR-0089: What a record may write, and what it may never leak

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0011](0011-api-conventions.md), [ADR-0012](0012-observability.md), [ADR-0016](0016-the-transactional-outbox.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0084](0084-the-record-store.md)

## Context

Sprint 14 puts the first business data into the platform, without the write pipeline of Sprint 15 (types, required fields, relationships, validation rules, formulas, field security) and without record-level security (Sprint 17). The endpoint
is therefore deliberately narrow, and it must be honest about it. A record is typed text: it must never end up where typed text is not allowed.

## Decision: what a write checks now

1. **The gate (`RecordGate`)**, one place for every read and write, in this order: who asks (the member behind the request; none on the platform host: "not found"); whether the object exists in the organization and is one the store holds (an object
   a platform module owns is not: "not found"); whether the member may **read** the object (no: "not found", the same answer as for an object that does not exist); whether the member may do the action (no: `403`, they know the object exists because
   they can read it). The permission is `Decisions` of the security module (object permissions, ADR-0050), read inside the transaction. Sprint 15 adds field security here; Sprint 17 adds record-level security (the owner is stored for it).
2. **Every key is a field of the object that may be written**: not a system field, not a calculated field (formula, auto-number), not a retired field. The error names the key (the field name is an identifier, not a value) unless the key is not shaped
   like an API name, in which case it is not repeated (it is typed text) and the error is the generic "a key is not the name of a field".
3. **Every value has a shape the store can keep safely**: a text (no NUL character, which the database cannot store; at most 100,000 characters), a number (kept as a decimal of at most 38 digits, so `1E+999999` is refused and `0.1` stays `0.1`), true or false,
   or a list of at most 1,000 of those (no nested list, no object, no null in a list). The whole set of values is at most `platform.data.limits.max-record-bytes` (default 256 KiB, as UTF-8 JSON); the database refuses more than 512 KiB whatever the setting says. A
   request body larger than four times that limit is refused with `413` before it is read (a client that announces no length is bounded by the proxy of the deployment, a deployment requirement in the runbook).
4. **Decimals in a request are decimals**: the JSON reader is set to read a number with a decimal point as a decimal, not a floating point number (`spring.jackson.deserialization.use-big-decimal-for-floats`), so a stored number comes back as it went in.

**What is not checked, on purpose** (Sprint 15): whether a value fits its field's type (an e-mail address, a date, a picklist value, a length), whether a required field has a value, whether a lookup points at a record, a validation rule, a formula, and field security.
Sprint 14 data may therefore be wrongly typed; there is no real data yet. The OpenAPI descriptions say so in as many words, so that nobody mistakes this endpoint for the finished one.

**Who may read a record until Sprint 17:** the object permission only. A member who may read an object reads **every** record of it in the organization. That is wider than the finished product will be; it is safe only because there is no screen and no real
data yet. View-all and modify-all still do nothing.

## Decision: what must never leak

| Where | What it says | What it never says |
|-------|--------------|--------------------|
| Logs (SLF4J) | nothing about records (the request log carries method, path, status, request and tenant identifiers) | a value, a key typed by a person, a body |
| Audit record (`data.record.created`, `updated`, `deleted`, `restored`, `purged`; `retention.record.purged`) | the object, the record identifier, the kind of change, the version, the **names** of the fields named in the change and their number | a value; old or new values. A field history with old and new values would be a separate, opt-in feature per field in a later sprint |
| Outbox event (`record.created` ... `record.purged`) | the same facts as JSON: object, record identifier, version, field names | a value |
| Error answers | the stable code and message; a field name only when it is shaped like an API name | a value, a label, a typed key, the version of another person's change |
| Metrics | no tag from a record | |

A leak test of the whole flow (`RecordFlowsLogsAreCleanIT`) runs create, read, list, change, refusals of every kind, delete, restore and removal with the application, the security framework and the JDBC layer logging at debug level, and fails if a
value appears in the log, the audit table, an event, the audit viewer or an error body.

## Consequences

- Sprint 15 puts its pipeline between the gate and the storage in the order of the delivery plan; these checks stay as the first steps.
- The audit trail answers "who changed which fields of which record when", not "what did they change them to". That is deliberate: a record is typed text.
- A deployment must set a request size limit at its proxy (runbook).
