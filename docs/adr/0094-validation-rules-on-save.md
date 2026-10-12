# ADR-0094: Validation rules on save, and a rule that cannot be evaluated

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0078](0078-validation-rules.md), [ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Context

A validation rule is a condition that is true when the record must be **rejected**; a missing value does not reject; it may use `TODAY()` and `NOW()`. Sprint 13 built the rules and their runtime contract (`MetadataResolver`,
`CompiledRule.evaluate(values, now)`) and left to Sprint 15 the judging of a record when it is saved, and what to do with a rule that cannot be evaluated (a division by zero, a value of the wrong kind): the contract reports it and
never passes it.

## Decision

1. **Every active rule of the object is judged on the record as it will be after the write**, and every rejection is reported with the rule's message at the rule's error field (or at `fields` for the record as a whole), in one
   answer. The moment is the clock of the application (`now` is passed in; the engine reads no clock).
2. **The message is text of the organization.** It is shown to the person who is rejected and nowhere else: never logged, audited, put in an event or in a metric (`RecordFlowsLogsAreCleanIT`).
3. **A rule that cannot be evaluated refuses the save** with "The validation rule `<API name>` could not be checked, so the record was not saved. Tell an administrator." (the API name is an identifier, never the rule's text or message),
   and the failure is **reported to the error tracking** (`ErrorTracker`, a `RuleEvaluationFailure` that carries the object and rule API names only), so a broken rule is noticed by the people who run the platform and not only by the people
   it blocks. It never passes silently: letting bad data in because a rule is broken is worse than blocking a save.
4. **A rule that reads a formula field judges the value this save gives it.** The delivery plan runs rules before formulas. A rule that reads a formula field would then judge the stored result of the *previous* save (or nothing, on a
   create), which is wrong. When, and only when, some active rule reads a formula field, the formulas are calculated once in memory before the rules, and their results are what the rules see. The observable order of stages and of answers
   is unchanged (a rule problem is answered before a formula problem). A rule does not see the auto-number of a record that is being created (the number is taken after the last check that can refuse, to keep its row lock short).
5. **Dependency.** `data -> observability` (module edge added, `package-info.java` and `modules.md`). The engine itself is not used from `data` (ADR-0091).

## Consequences

- The cost is bounded by the engine's own bounds (50 ms and 10,000 steps per expression, 50 rules per object). The compiled rules are kept per metadata version.
- Rules of platform-owned objects stay out (ADR-0084: those records are not in the generic store).
