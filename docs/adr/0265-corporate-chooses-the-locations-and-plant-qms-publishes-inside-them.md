# ADR-0265: Corporate QMS chooses the locations, Plant QMS chooses the departments and publishes inside them

- **Status:** Accepted (the product owner wrote the rules and the recommended design in the FA15 prompt on 2026-11-18, and said "go" to the plan on
  2026-11-19; the two questions the plan left open were answered by the recommendations, see decisions 9 and 10)
- **Date:** 2026-11-19
- **Track:** Functional (Document Management publish; not a numbered sprint). **No migration** (the suggestion is already stored, V076). One ability, one
  shipped access policy, one pure rule, one scope on `shareWith`, three fields on the lifecycle view, one dialog. **No new module edge.**
- **Related:** ADR-0200 (a step that asks for a choice, the suggestion), ADR-0188 and ADR-0189 (publishing, its own memory, who may publish), ADR-0199 (who
  publishes), ADR-0202 (a change request is one controlled change), ADR-0260 (publish to the plants, which does not change), ADR-0115 (one place decides who
  sees a record), ADR-0012 (what is never logged), ADR-0039 and ADR-0040 (abilities, access policies)

## Context

A template is approved in steps. The step of the Corporate QMS group (level 4) asks for a choice and ticks the **locations** (a department is optional,
ADR-0200). The Plant QMS group (level 3) must then see the locations Corporate chose, choose the **departments** of each, and publish.

This worked with one location and had gaps with several:

1. The dialog preselected only the first suggested location when a location was suggested alone; the others were invisible.
2. Corporate's locations were not a limit: the dialog offered every location and `TemplatePublishing.publish` never compared the picks with the
   suggestion. Any holder of `sheets.publish` published anywhere.
3. A publish made the audience exactly the picks and took back every other group, so a later publish by Plant QMS removed what Corporate (or an
   administrator) had published elsewhere.
4. After a revision published by a change request the suggestion was no longer found (the review of the new version is the change request's), so even a
   rule built on the suggestion would have stopped applying.

### Who does what (the owner's table)

| Level | Who | What they do |
|---|---|---|
| 1 | Plant head and the users who can fill in | fill in |
| 2 | Management users: fill in, create a template, assign reviewers, send for approval. Department users: fill in, create a template, send for approval. Anyone can be a reviewer; a reviewer's only extra power is to approve the submitted fill-ins | create, send, review |
| 3 | Plant QMS: approve, and publish to departments of the locations Corporate QMS chose | approve, publish inside the chosen locations |
| 4 | Corporate QMS: approve, and publish to a location and (optionally) a department. Reviewers who approve submitted fill-ins | approve, choose locations, publish anywhere |
| 5 | CEO | final decisions where a process says so |

People of these levels are added to public groups; the groups are used in approval processes.

## Decisions

1. **There is ONE Plant QMS group for the whole organization, as the approval process names it. (Owner's decision, not to be reopened.)** There is no
   Plant QMS group per plant and no link from a Plant QMS group to a location. *What would have to be added if it ever changes:* see "How to change this
   later", first row.
2. **The locations Corporate chose are the limit for a publisher who lacks the new ability.** Only locations limit. Reason: the one group sees many
   locations, so the choice of Corporate is the only thing that says which of them this template is for.
3. **The departments Corporate ticked are pre-ticked hints, not a limit.** Within an allowed location the publisher may tick any department of the
   organization's list. Reason: Corporate said a department is optional, and Plant QMS is the one who knows the plant's departments.
4. **A new ability, `sheets.publish-any-location`, lifts the limit.** It adds to `sheets.publish` (the act still needs both); it is asked in one place,
   `TemplatePublishing.scopeOf` (and, for the view, `view`), through `TemplateAccess.has`, which asks `Permissions`. It is never read from a table.
5. **The ability is held through a new shipped access policy, "Publisher anywhere"** (key `publisher-anywhere`, `solutions/inspection/solution.yml`): the
   same rights on the object and on fields as Publisher, plus the ability. Publisher stays as it was and is the limited one; its description changed.
   The Organization administrator holds every ability and so publishes anywhere. Reason: an access policy is how an administrator already decides which
   group has the last word; a second mechanism would be a fourth thing to explain next to licence, access policy and plan feature.
6. **When the version has no suggestion there is no limit** (a process with no choice step, an older template, a step that was skipped). Reason: nothing
   was chosen, so nothing limits; this is how it was.
7. **The suggestion that binds a version is its own, else the template's most recent one** (`AudienceSuggestions.forVersion`). Reason: a revised version
   is reviewed by a change request, which makes no suggestion; without the fallback the limit would silently disappear after the first revision.
   This went beyond the prompt ("the version being published") and was decided here; it is the only reading under which the rule keeps meaning.
8. **If a suggestion exists and none of its places is offered any more, a limited publisher cannot publish** and is told so in fixed words
   (`409 CONFLICT`); somebody with the ability can.
9. **A limited publish changes only its own locations** (`shareWith` has a scope). A row of another location is never taken back, whoever published it
   and however long ago; inside the scope a group published before and not asked for again is still taken back. Reason: a limited publisher must never
   remove what Corporate QMS (or an administrator) published elsewhere. The rows outside the scope still *follow the version* (they now read the new
   revision, which is true: there is one version in use), but are not revoked or added to. The audit says how many rows were kept (`kept`).
10. **The last approval of a change request is not limited** (ADR-0202 stays as it is). Its popup is the publish itself, it requires a department for every
    location, and it runs inside the decision, so nothing is left to choose later. This means a Plant QMS person who is the last approver of a change
    request can still choose any location there. This is a known gap the owner can close by giving that step to a group that holds Publisher anywhere
    (the check there asks `sheets.publish` only). **Decided by the owner by default (the plan's recommendation was accepted); revisit if it matters.**
11. **Management Core's "Publish to plants" is a different act with its own memory and does not change.**
12. **The server decides, the dialog draws.** The view tells the screen `limitedToSuggested`, `noPlaceLeft` and `unpublishedPlaces`; the dialog shows
    one section per location with a status, the chosen ones first and marked. A refusal names the pick by its place (`audiences[i]`) in fixed words that
    repeat no code ("This location was not chosen for this document.").
13. **A location Corporate chose and nothing is published to is shown** in the dialog ("Not published yet: ...") and on the template page, so it is not
    forgotten. It can be published later; the next publish keeps what is there.
14. **Audit and logs:** the audit says how many places (`audiences`), whether the publish was limited (`limited`) and how many rows were left alone
    (`kept`). No location, department, group or suggestion content is ever logged, audited, put in an event or a notice
    (`PublishLocationLimitIT.noCodeOfAPlaceReachesTheAuditTrailAnEventOrANotice`).

## Alternatives rejected

- **A Plant QMS group per plant, and a link from a group to a location.** The owner decided there is one group. It would give the limit for free but
  needs a new setting, a screen and a migration, and every new plant would need a group.
- **The suggestion as a limit for everyone** (no ability). An administrator or Corporate could then not correct a mistake. Rejected.
- **The departments as a limit.** Corporate said a department is optional, and the person who knows the plant's departments is Plant QMS.
- **A second publish that replaces everything.** A limited publisher would take back what Corporate published elsewhere. Rejected by decision 9.
- **Dropping the limit after a revision** (the suggestion of the version only). Rejected by decision 7.

## Consequences

- An organization that already gave Publisher to a group that must publish anywhere has to give that group Publisher anywhere. The demo script does it
  (`scripts/demo-setup.mjs`). Until then such a group is limited **only when a suggestion exists**.
- A group is the unit of a publication row (one row per group). If an administrator gave one group to two pairs, the row remembers only the first pair,
  and a limited publish decides whether to leave the row by that stored location. Give each pair its own group (ADR-0190); the setup says so.
- The dialog changes shape: no dropdown and no Add button.
- The OpenAPI document and the web client gained three fields (`limitedToSuggested`, `noPlaceLeft`, `unpublishedPlaces`) and a schema
  (`UnpublishedPlace`).

## What stays as it is, and why

- The choice step and its words, who is told, numbering, approvals, the footer, fill-ins: not touched.
- The change-request publish (decision 10) and the publish to plants (decision 11).
- The `Environment Publisher` policy of the second application has no "anywhere" counterpart yet; add one with the same three lines if that application
  needs it.

## How to change this later

| Future change | Touch |
|---|---|
| A Plant QMS group per plant (a link from a group to a location) | A new setting or field that pairs a group with a location (a table or a field of `DepartmentGroup`, with a migration if a table); `TemplatePublishing.scopeOf` takes the locations of the caller's groups instead of the suggestion (through the `security` module's group lookup, which is already a dependency of `TemplatePublishing`); `PublishScope.of` gets a second source of locations; the view's `limitedToSuggested` stays; this ADR gets a superseding one. |
| The departments as a hard limit | `PublishScope`: add the pairs (not only the locations) and `outside` compares the pair; `AudienceSuggestions.Held` already carries the departments; the dialog stops offering other departments (`PublishDialog.tsx`, `statusOf`); update `PublishScopeTest` and the refusal words. |
| A second step of publishing (Corporate confirms what Plant QMS chose) | A new status on the publication row (a migration) and a second act in `TemplatePublishing`; the scope stays. |
| Another level | A new shipped policy in `solution.yml` with the abilities it needs; nothing in code if it uses the ability that exists. |
| A different default when there is no suggestion (limited instead of free) | `PublishScope.of`: the `suggested` line; `PublishScopeTest.aTemplateWithNoSuggestionIsNotLimited`; `PublishLocationLimitIT.aTemplateWhoseApprovalChoseNothingIsNotLimited`. |
| Close the change-request gap (decision 10) | `ChangeRequestApprovalHooks.publish`: ask `SHEETS_PUBLISH_ANY_LOCATION` or compare the choice with the template's suggestion through `AudienceSuggestions.forVersion`. |
| The name of the ability or the policy | `Ability.SHEETS_PUBLISH_ANY_LOCATION`, `solution.yml` (key and name), `SolutionLoaderTest`, `ShippedPoliciesIT`, `SolutionSupport`, `demo-setup.mjs`. A changed key of a shipped policy makes a second policy in organizations that have the first. |
