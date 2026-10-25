# ADR-0200: An approval step that asks for a choice, and the audience it suggests for a template

- **Status:** Accepted
- **Date:** 2026-10-25
- **Track:** Functional (FA6G, demo readiness), with one additive migration (V076), one additive endpoint of the cluster, one
  flag on the step of an approval process and one optional field on a decision. No new module edge, no new ability, no new policy.
- **Related:** ADR-0187 (approval hooks, which this amends), ADR-0188 and ADR-0190 (publishing a template to the people of a
  department), ADR-0196 (the identity pair at the first approval), ADR-0199 (who publishes), ADR-0115 (who may see a record),
  ADR-0012 (what is never logged)

## Context

The template review has three steps in the organization that asked for it: **Plant QMS** checks and numbers, **Corporate QMS**
approves, and a last step of Plant QMS is an ordinary approval after which Plant QMS publishes. Corporate QMS knows where the
template is meant to be used, and Plant QMS publishes. The business wants Corporate QMS to **suggest** that audience when it
approves, and Plant QMS to be shown the suggestion ready-filled when it publishes, free to change it. The suggestion must not
make the template visible to anybody, and an approval at that step must not be possible without it.

## Decision

1. **A step of a process may be marked "asks for a choice"** (`asksForChoice`, a flag next to `skipWhenNobody`, saved with the
   process version, false for every process made before). The process builder has the box. A name match on "Corporate QMS" would
   work for one organization only; a flag works for any organization, any step and any category. The engine knows nothing of
   what is chosen: it carries the flag and the choice, nothing more.
2. **The choice travels with the approval.** `ApprovalDecisionRequest` gets an optional list `choices` of at most 50 pairs
   `{key, value}` of short codes. The engine hands it to the hook of the object's module in `DecisionContext` (with
   `asksForChoice`) and reads it for nothing else. **This amends ADR-0187's "facts only":** a hook is still handed no comment and
   no value of the record, but it is now handed the codes the approver chose, only at a step the process marked, redacted when
   printed and never logged by the engine. The hook alone judges the choice and may refuse the approval with its fixed words (a
   `409`). A refusal undoes the whole decision: nothing is written and the task stays open. Rejecting, recalling and cancelling
   take no choice.
3. **The template's hook requires a location and accepts a department** (`AudienceChoices`, a pure rule). The choice is a list of
   `location` pairs and `department` pairs (`LOCATION/DEPARTMENT`). At least one location is required; a department is optional; a
   department of a location that was not chosen, a repeated pair, a malformed code, an unknown key or more than 20 places is
   refused. Each place must still be offered by the organization's lists (`DepartmentAudiences.offered`: the active values and
   the dependent pairs of the department list). The refusal words never repeat a code.
4. **The suggestion is kept apart from publishing.** Table `inspection_audience_suggestion` (V076): the request, the template, the
   step, a location and an optional department, base columns, `tenant_id`, forced row level security, tenant guard. The database
   guard lets a row in **only** when the transaction setting `platform.audience_suggestion_write` is on, which only the store
   raises in the statement that inserts; it refuses any update, soft delete or delete afterwards, for the application role and for
   the owner. No share is made, no group is read, no access changes. The first approval at the step that asks writes it; a later
   approval of the same request (a step that needs everybody) is judged the same way and leaves it untouched. The approver cannot
   change it afterwards; Plant QMS changes the audience when it publishes. A rejection or a recall at any later step leaves the
   rows with their own request, and a new request starts with nothing.
5. **The Publish popup starts from the suggestion.** `TemplateLifecycleView` gains `suggestedAudience` and `suggestionDropped`.
   They are computed only for a caller who may publish, for the version Publish would make current (the approved one, else the
   one in use), from the request that reviewed that version (`versionReviews`). A place the organization no longer offers is left
   out and counted, and the screen says so in plain words. A step that was skipped (an empty group) leaves no suggestion, so the
   popup starts as it always did. Publish itself is unchanged: the Publisher policy, a location **and at least one department**,
   a group set up for each, and nothing is visible to a department before it.
6. **A person at a step that asks needs the lists, not the ability to publish.** `GET /api/v1/templates/{id}/suggestion-options`
   gives the same lists as the publish dialog to any caller who can see the template (not found otherwise). The lists are the
   organization's own words; nothing about groups or the template's audience is added.
7. **The screens.** The approve confirmation (`ApprovalActionForm`) asks `solutions/choices.ts` for a form by the object's API
   name, shows it at a step the API says asks (`currentStepAsksForChoice` on the summary, `asksForChoice` on a step), keeps the
   button disabled until the form gives a choice, and sends it. An object with no form shows a plain sentence and no way to
   approve from that screen. The server insists regardless of any screen.
8. **What is never written down:** a location, a department or a pair. The audit record `inspection.audience.suggested` says that
   a suggestion was made and how many places it holds; the metric has a result tag; no event or notice carries it
   (`AudienceSuggestionIT`).

## Alternatives considered

- **Write the suggestion first with a separate call, then approve.** Two acts could be separated by a failure, the approve could
  be sent without the first, and the row would not be written by the approval. Carrying the choice in the decision makes it one
  transaction.
- **Match the step by its name.** Rejected, as above.
- **Publish inside the last approval (one click).** Possible, but it would put the shares in the approver's transaction, with a new
  write path and a new lock-order proof. Left as two acts as asked; the cost of adding it later is a hook that publishes in
  `beforeApprove` of the last step.
- **Let the approver change the suggestion.** No: it would need an update path the database could not hold to "written once".

## Consequences

- `SolutionBoundaryTest` still passes: the engine, the contract and the shared kernel carry only generic words (`choice`,
  `asksForChoice`); the cluster's words live in the cluster's places, and V076 is added to that list.
- An old process version reads `asksForChoice` as false; rewriting it states false outright.
- The approvals inbox shows the same form, because it uses the same confirmation component.
- Nothing checks that the right step is marked: the administrator marks it in Setup and tests it before handover.
