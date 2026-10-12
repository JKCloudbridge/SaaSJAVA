# ADR-0077: Formula fields are checked and are edges of the dependency graph

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0060](0060-field-types-and-their-configuration.md) point 8, [ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0076](0076-the-expression-engine-and-its-language.md)

## Context

Until Sprint 12 a formula field kept its text and its result type and nothing was parsed ([ADR-0060](0060-field-types-and-their-configuration.md) point 8). That
point also says "Sprint 13 must re-check every stored formula with its grammar". The deferral of Sprint 11 says the same of the dependency graph: a formula's
references are not edges yet.

## Decision

1. **Two checks, in the two places where each can be made.** *What can be told from the text alone* is checked when the field is saved, live or in a change set,
   by `FieldRules` (the one place for the rules of every field type): the text follows the grammar and does not ask for the clock; a problem is a
   `VALIDATION_ERROR` under `settings.expression` with the place (line and column). *What needs the other fields* (the fields exist, the types fit, the formula
   gives the type it is declared to give) is checked **after the change, on the result**, by a contributor of the dependency graph
   (`ExpressionDependencies.Formulas`), because a group of changes may fix a dependency again and the order of the changes of one set must not matter (the
   undo of a set that removed a field together with its formula recreates the field first). A problem is a `CONFLICT` that names the formula and the place.
2. **The fields a formula uses are edges** ("field Order__c.total__c uses field Order__c.quantity__c"). Removing a field a formula uses is refused with the
   formula named, live and in a change set; a retired field is present (it still exists). A formula that names a field that does not exist is reported as
   an unmet edge, in the same words as every other missing dependency.
3. **Circles.** A formula that depends on itself, directly or through any number of other formulas, is refused; the message names the fields in order
   ("Formulas of Order__c use each other in a circle: Order__c.a__c -> Order__c.b__c -> Order__c.a__c"). The cycle is found on the result of a whole change
   set, so two formulas that use each other in one set are refused together, and a circle closed by an update is refused too.
4. **Depth.** A formula may use another formula; the stack may be at most 5 formulas high (`platform.metadata.limits.max-formula-depth`). A deeper one is
   refused with the chain named. The runtime calculates formulas in an order where what a formula uses comes before it.
5. **The type a formula gives cannot change** once the field exists (like the type of every field): a formula that uses it, or a rule, may rely on it. To
   change it, add a new formula field. Its text can change.
6. **The clock is for rules only.** `TODAY()` and `NOW()` are refused in a formula, because a stored value would silently go stale; they are allowed in a
   validation rule (judged when the record is saved). This is a difference from note 6, which lists `TODAY()` among the formula functions; a "computed when
   read" mode would be a later, separate decision.
7. **A formula stored before the language existed** (a field of Sprints 10 to 12) has no `verified` mark in its stored configuration. Re-checking it with the
   grammar (the requirement of ADR-0060 point 8) has three outcomes, none of which deletes anything or blocks anybody:
   - It **passes**: it is trusted from the start, its fields are edges, and it is calculated by the runtime.
   - It **does not pass**: it keeps its text ("stored only"), it is **reported** (the object view shows the place that is wrong in `field.problem`, and
     `GET /api/v1/metadata/formula-report` lists all of them), it is neither trusted nor an obstacle (it adds no edges and no violation, so it does not stop an
     unrelated publication) and it is not calculated.
   - It is **saved again**: it becomes `verified`, and from then on it is checked like any other: a text that still does not pass is refused, and one that names
     a field that does not exist is refused by the graph.
   A formula saved under these checks is `verified` from the start; the standard definitions are read with the same rules. No real organization has a stored
   formula yet; the local seed and the tests have a few.
8. **Audit** is by names and counts as for every field change; the expression itself is never written to the trail or to a log.

## Consequences

- `FormulaAnalysis` is the one analysis of an object's formulas (which pass, which fields each uses, circles, depths, the order of calculation); the graph
  contributor, the compiled artefacts of the runtime, the report and the object view all read it, so they cannot disagree.
- A person who writes a formula that names a field they have not made yet, in a change set, can order the changes in any way: the check is made on the
  result.
- Cost: every publication builds the graph, which now compiles every formula and every rule of the organization (microseconds each; bounded by the limits of
  500 fields and 50 rules per object).
