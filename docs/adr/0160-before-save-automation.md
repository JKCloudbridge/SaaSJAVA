# ADR-0160: Before-save automation

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0076](0076-the-expression-engine-and-its-language.md), [ADR-0081](0081-security-review-of-the-expression-engine.md),
  [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0136](0136-workflow-definitions-versions-and-lifecycle.md),
  [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md)

## Context

Architecture note 6 puts inside the transaction of a record write: authenticate, authorize, validate, **calculate the formulas the record
needs**, persist, audit and outbox. Workflow processing comes only after commit (section 14). Sections 13 and 14 name validation,
authorization and critical constraints as the synchronous work; they do not name "set a field from another field under a condition", which
is what organizations ask for (defaults that depend on other values, normalised fields). It is the same kind of work as a formula, with a
condition, and it must share the formula's discipline: inside the transaction, bounded, no side effect outside the database. The prompt
leaves open the shape of the definition, the stage position and the limits. This ADR records the extension of note 6 and its bounds.

## Decision

1. **A before-save automation is a workflow with a before-save trigger.** `WorkflowDefinition.Kind` gains `BEFORE_CREATE` and
   `BEFORE_UPDATE`. Such a workflow has **exactly one action, `SET_FIELDS`**, which sets fields of the record being saved to a literal (written
   as a field default is) or a copy of another field of the same record; and nothing else. `SET_FIELDS` is refused for any other trigger. A
   before-save workflow never makes an execution: the event handler matches none of its kinds, and the registry seat of `SET_FIELDS` fails
   permanently (`before_save_only`) if one ever did. It reuses everything workflows already have: immutable versions, drafts and rollback in
   change sets, the dependency graph (every field it names is an edge), the condition (purpose `WORKFLOW_CONDITION`, true or false, bounded by the engine),
   the builder, entitlement by the plan feature `workflows`, `workflows.manage` and `metadata.publish`. A workflow cannot change
   from one phase to the other (make a new one).
2. **A stage of the one pipeline, not a second path to the records.** `WritePipeline.STAGES` is now shape, record type, field permission,
   type, **before save**, required, relationship, unique, rule, formula. It sits after "type" and not before "field permission" as the
   prompt guessed, because it needs typed values to judge a condition and an assignment is typed with the very rules of a person's input
   (`FieldValue.literal`, `FieldValue.check`); everything it sets then passes required, relationship, unique, validation rules and formulas
   exactly as if the person had typed it. The fields it sets are set whatever the member may edit (like a default or a calculated value; the
   definition was published by someone who may change the object, ADR-0139 reasoning), and a field the record type does not offer is left
   alone, like a default.
3. **It runs in every save through the pipeline**: a person's create or update, a bulk row, an import row, a workflow's or an approval's
   write as the platform. One rule everywhere is the point of a defaulting rule.
4. **No re-entry, no cascade, a deterministic result.** Every condition is judged on the record as it is **before any automation ran**, each
   automation runs once, in the order of its API name, and a later one wins a field two of them set. A change made here starts no other
   before-save run. Its field names reach the audit record and the event of the save like any changed field; values never do.
5. **Bounds, inside the transaction, therefore a safety property.** At most 10 automations per object, enforced at save of the definition
   (a CONFLICT) and again when running (`platform.data.before-save.max-per-object`); at most `maxAssignmentsPerAction` fields each; every
   condition under the expression engine's own limits (steps, length, 50 ms); **a time budget of 500 ms for all of them together** per save
   (`platform.data.before-save.time-budget`); and a kill switch (`platform.data.before-save.enabled`). The time of a condition is the only
   unbounded-looking part and the engine bounds it.
6. **Failure is stable and saves nothing.** A value that does not fit its field (a field changed after publication, which the dependency
   graph should have caught) refuses the save with one stable sentence naming the field, never the value. An overrun of the budget refuses
   the save with a stable sentence naming nothing and is reported to the error tracking with identifiers. A condition that **cannot be
   evaluated** skips that automation, is counted (`platform.data.before.save{result=not_evaluable}`) and reported, and never blocks the
   save: the stages after this one guard integrity, and an unreadable convenience rule must not stop work.
7. **Metrics** `platform.data.before.save{result=ran|skipped|not_evaluable|refused|overran}` and a duration timer; no tag is an object, a
   workflow or a field.
8. **Never carried:** no expression as a value (the question of ADR-0144 stays: no), no other record, no mail or event, no call out, no
   loop. A definition that needs one cannot be expressed.

## Consequences

- Organizations get defaults and normalisation in the one place that cannot be bypassed, with the same checks as typed input.
- A busy object pays at most the conditions of its automations on each save, bounded by the budget.
- The differential test holds a person's write and the same write through `BackgroundWrites` to the same result.

## Alternatives considered

- **A new definition kind with its own tables:** a second lifecycle, builder, version history and set of change kinds for a thing that is a
  trigger and one action. Rejected.
- **Before "field permission":** would need untyped values and would write fields the member may not edit before they were judged.
- **After the commit, as a workflow:** the record would be saved first and fixed second; a unique or required rule could not see the fix.

## References

- `platform-app/.../data/internal/{BeforeSave,BeforeSaveProperties,WritePipeline}.java`; `metadata/internal/{WorkflowRules,WorkflowService,WorkflowActionChecks}.java`;
  `platform-api-contract/.../WorkflowActionTypes.java`
