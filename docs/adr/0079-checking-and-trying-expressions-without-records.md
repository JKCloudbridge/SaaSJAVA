# ADR-0079: Checking and trying expressions without records

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0078](0078-validation-rules.md)

## Context

A person who writes a formula or a rule needs to know at once whether it is valid and what it would do. There are no records until Sprint 14. A second engine in
TypeScript would be a second grammar to keep equal to the first, and "the backend is authoritative" would turn into "the backend and the browser agree, we hope".

## Decision

1. **Ask the real engine.** `POST /api/v1/metadata/expressions/check` takes the object, the text, the purpose (`FORMULA` or `VALIDATION_RULE`) and for a formula
   the result type, and answers valid or not, where it is wrong (kind, line, column, words), the type it gives, the fields it uses and whether it reads the clock.
   It always answers 200 for a well-formed request: a mistake in the expression is the answer, not a failure.
2. **Try it on sample values.** `POST /api/v1/metadata/expressions/test` (an expression that is not stored yet), `POST .../validation-rules/{rule}/test` and `POST
   .../fields/{field}/test` (a stored formula) take sample values by field API name, **each written as text for the type of its field** (a number in plain digits,
   true or false, a date like 2030-01-31, a date and time like 2030-01-31T09:30:00Z, a time like 09:30); a field left out or blank has no value. A value that is
   not for a field of the object or does not fit its type is refused under `values.<field>`. The answer is `REJECTED` (with the rule's message), `PASSED`, `VALUE`
   (the value as text, or none) or `ERROR` (the evaluation itself failed, with the place: a division by zero, a number that is too large).
3. **The list of functions** comes from the engine (`GET /api/v1/metadata/expressions/functions`: name, signature, description, rules-only), so no screen
   holds a list of its own. **The report of stored formulas** that do not pass the language is `GET /api/v1/metadata/formula-report` ([ADR-0077](0077-formula-fields-are-checked-and-are-edges-of-the-graph.md)).
4. **Nothing is stored and nothing is logged.** The expression, the sample values and the answer are text of the organization: they are never logged and never
   written to the audit trail (a leak test, `ExpressionFlowsLogsAreCleanIT`, proves it for every flow). Checking and trying are not audited.
5. **Abilities.** The functions and the report need `metadata.view`. Checking and trying need `metadata.view` and either `metadata.manage` or `metadata.publish`, as
   the check of a change set does (a person who may draft or publish may ask what a draft would do). Every endpoint answers on an organization host only; a forged tenant
   header changes nothing; a platform administrator is refused.
6. **Cost.** A check or a try is one parse and one evaluation under the bounds of the engine, in one short transaction that reads the catalogue (served from the
   cache). The bounds apply: a text longer than 4000 characters is refused by the request itself.

## Consequences

- The browser shows the API's own words (`Line 1, column 14: ...`), the type and the fields; it evaluates nothing.
- Sprint 19 reuses the same engine for the immediate feedback with real records, by calling the backend, until a decision is taken to do otherwise.
