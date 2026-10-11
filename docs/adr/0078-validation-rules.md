# ADR-0078: Validation rules

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S13
- **Related:** [ADR-0058](0058-object-and-field-definitions.md), [ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0068](0068-the-publish-ability-and-changes-made-at-once.md), [ADR-0076](0076-the-expression-engine-and-its-language.md)

## Context

Requirement R13 and architecture note 3 (section 16) say a rule is metadata: an expression, an error message, an active flag, belonging to one object; the
backend evaluates it and is authoritative; the frontend may use the same definition for immediate feedback. Records do not exist before Sprint 14 and the
write pipeline that judges rules on save is Sprint 15, so this sprint stores, checks and tries rules; it does not apply them.

## Decision

1. **What a rule is.** A tenant-scoped, published row of `validation_rule` (migration V035, row level security forced, the guards of ADR-0069, the metadata
   version trigger): the object, a permanent API name ending in `__c`, a label, a description, the **expression**, the **error message** (at most 255
   characters, typed by the organization), an optional **field** the message is shown on, and an **active** flag. At most 50 rules per object
   (`platform.metadata.limits.max-validation-rules-per-object`). Only objects whose records are not managed by the platform can have rules.
2. **The expression is true when the record must be rejected.** This is the reading of the requirement text "IF ... THEN reject save". Note 6 writes the
   opposite example (`salary > 0` as the condition that holds); the sprint follows the requirements, and the screens and the API say so in words: "the save
   is rejected when this is true". A condition whose answer is no value **does not reject** (a field nobody filled in does not make a record invalid by
   itself; `ISBLANK` is how a person asks for that). A rule must give true or false; anything else is refused with the type it gives.
3. **The clock is allowed** in a rule (`TODAY()`, `NOW()`): it is judged when the record is saved, and the moment is an argument of the evaluation.
4. **Checked in two places, as formulas are** ([ADR-0077](0077-formula-fields-are-checked-and-are-edges-of-the-graph.md)): the grammar and the bounds of the
   texts when the rule is saved (`VALIDATION_ERROR` with the place); that the fields exist, the types fit and the condition gives true or false after the
   change, on the result (`CONFLICT` naming the rule and the place). The field of the message is an edge too ("shows its message on"). A field a rule uses
   cannot be removed ("validation rule NoNegative__c of Order__c uses field Order__c.quantity__c, which does not exist after this change"); an object with
   rules cannot be removed until its rules are (as for layouts).
5. **Inside the lifecycle.** Three new kinds of change, `CREATE_VALIDATION_RULE`, `UPDATE_VALIDATION_RULE`, `DELETE_VALIDATION_RULE`, each with its undo built from the
   before-image, a release item `VALIDATION_RULE`, the dependency contributor, and the widened check of `metadata_change` (V035). A rule can be drafted in a
   change set, checked, previewed and published atomically, made live at once (a release of one change) and rolled back. The undo of a set that removed a
   field together with its rule recreates the field first.
6. **Endpoints** under `/api/v1/metadata/objects/{object}/validation-rules` (list, get, create, update, delete, and `.../{rule}/test`, [ADR-0079](0079-checking-and-trying-expressions-without-records.md)).
   **Abilities** are the ones of Sprints 11 and 12, no new one: reading needs `metadata.view`; drafting `metadata.manage`; a change made at once needs
   `metadata.manage` and `metadata.publish`; trying needs `metadata.view` and either of the other two.
7. **Audit** by names and counts: `metadata.validationrule.created|updated|deleted` (object and name as the key, `active`, the number of fields used, what
   changed as names such as `expression` or `errorMessage`), next to the release records. Never the expression, the message, the label or the description.
8. **Memory.** A rule is in the snapshot as text; its compiled form is made on demand and kept with the snapshot ([ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md)).

## Consequences

- Sprint 15 judges the active rules of an object through `MetadataResolver.validationRules(object)`; what a save does when a rule cannot be evaluated (a
  division by zero, a value of the wrong kind) is that sprint's decision: the contract reports it as a failure and never as a pass.
- The browser's immediate feedback with real records is Sprint 19; until then the try-it endpoints give it without a second engine in TypeScript.
