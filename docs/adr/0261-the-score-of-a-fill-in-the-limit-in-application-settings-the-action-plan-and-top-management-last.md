# ADR-0261: The score of a fill-in, the limit in Application settings, the Action plan and top management last

- **Status:** Accepted (the product owner answered the questions of the MC2B prompt with the recommendations on 2026-11-14, and set the
  limit as a setting in Setup on the same day)
- **Date:** 2026-11-14
- **Track:** Functional (Management Core, part 2B). Migration V094 (expand only). Two tables, three fields on the fill-in object, one sheet
  property, one ability, one notice kind with a mail template, two shared-kernel seams, one rule on change requests. **No new module edge.**
  Notes on ADR-0260 where it changes (see the end).
- **Related:** ADR-0260 (plants, reviewers per plant, the monthly fill-in), ADR-0259 (the field `application`, options), ADR-0258 (fill format 3,
  calculated answers are computed, never stored), ADR-0252 (formulas), ADR-0251 (format version 2), ADR-0187 (approval hooks, one write through the
  narrow writer), ADR-0145 to ADR-0153 (the approval engine), ADR-0154 (notices are facts, never words), ADR-0115 (one place decides who sees a
  record), ADR-0191 and ADR-0199 (change requests), ADR-0012 (what is never logged)

## Context

A plant head fills the Plant activity form every month. The sheet calculates a percent with its own formulas. Below a limit the plant head must
fill an Action plan, which the same reviewers of that plant review, and top management approves last. The numbers a dashboard will need (the
monthly rating of a plant, the comparison, the Action plans) must be readable with the record store's own queries. The earlier ADRs leave open where
the score comes from, where the limit lives, how an Action plan is tied to the fill-in it answers, and when the platform may stop a fill-in at its
last step.

## Decisions

### 1. The score is read from one cell the template marks

A sheet in format version 2 may carry one property, `score`: the key of a cell that holds a formula. The validator refuses any other cell
(`score_cell`, words that name no value). The marker is the sheet's own property: it is part of the canonical form and of the hash, it travels with
every Revision and is in the schema file. The platform never guesses it: an upload cannot name it.

**How an author sets it today** (until the author editor, SH6): in the grid of a draft, select the formula cell and tick "Use as the score" beside
the formula bar. The mark is kept only while its cell still holds a formula (the browser drops it otherwise, as it drops every part of a removed row
or column). It is a manual step by an author on every new form and, **when a Revision is made by uploading a file again, on that Revision too**: a
Revision with no mark has no score, so fill-ins started on it are judged as before, with no score and no Action plan. That is the one way this can go
quiet; the manual steps say so and a later session (the author editor) should warn when a Revision loses a mark its predecessor had.

### 2. Calculated, at every approval, in percent points, never a pass by accident

`FillLifecycle.scoreOf` calculates the marked cell from the pinned version and the cells as they are now, with the calculator that already answers a
fill-in (`SheetCalculator`), and gives a `Score`: `NOT_MARKED`, `NOT_CALCULATED`, or `CALCULATED` with the value. The value is in **percent points**
rounded half up to two decimals (90.00): a cell whose number format is a percent format holds a fraction and is multiplied by a hundred; any other
cell is taken as the points it holds. An error answer, text, a yes or no, no answer and a number outside 0 to 100 are `NOT_CALCULATED`: a score that
cannot be relied on never decides that no Action plan is needed.

The score is calculated and written **at every approval** of the review, in the approval hook's `beforeApprove`, through the narrow writer
(ADR-0187): not only at the last one, because the Action plan has to exist before the last step. A reviewer's edit of the reviewer's cells is
therefore always seen by the approval that follows it. A score that cannot be calculated refuses the approval, in fixed words
(`score_not_calculated`), and writes nothing; the task stays open. A sheet that marks no score is not touched at all.

After a correction of the cells of a fill-in that is no longer under review (an approver's counted correction), `FillCorrections`, a shared-kernel
seam implemented by the cluster, brings the score and the result up to date in the same transaction (an unreadable score clears both). While a review
is open the next approval calculates again, so nothing needs to happen then. A fill-in a person edits as a draft keeps the score of its last review
until it is reviewed again; a list that counts results should count the approved ones.

### 3. The score and the result are on the header; the limit is a setting

The fill-in object gains three fields, all written only by the platform and indexed: `score` (a decimal, 5 digits of which 2 after the point, never a
text), `result` (`PASS`, or `ACTION_PLAN` when the score is **strictly below** the limit; exactly the limit passes) and `parentFill` (see 5). They
are on the fill-in object of Environment Management too (the parity test), always empty there. Every shipped policy that lists fill-in fields lists
the new ones (`FillInFieldsInPoliciesTest`). They are what a dashboard reads with `Records.query`.

The limit is **not in the sheet** (the legend of a form is a drawing for the reader). It is a setting of the application, per organization:

- the solution file declares it (`settings:` with a key, words, a kind and a default; kind `PERCENT` is a whole number from 1 to 100); the default,
  85 for Management Core, is what applies until an organization chooses, so the application works with no setup;
- an organization's own choice is a row of the new table `application_setting` (tenant-scoped, forced row level security, a guard that keeps the
  application and key, one live row for each, soft delete), read and written only by the `solution` module, which implements the shared-kernel
  contract `ApplicationSettings` that the cluster asks (`value(application, key)`). The cluster holds no limit of its own: an application that
  declares none gets the score and no result;
- the screen is Setup, Application settings (`/api/v1/application-settings`, list, save with the version read, reset), generic: it draws what the API
  lists and holds no list. The ability is the new `application-settings.manage`, **not** `numbering.manage`: that one is about document numbers, and
  what an application lets an organization choose will grow beyond numbering. A setting of an application the organization does not have, and one
  nobody declared, are "not found" in the same words;
- a change applies from the next question: because the score is judged at every approval, a review that ends afterwards uses the new limit. A notice
  already sent stays sent;
- the audit says the application and the setting and the kind of change (`chosen`, `changed`, `reset`), never the value.

### 4. The Action plan is a fill-in of another document, linked and unique

The Action plan is a fill-in of the **Action plan form**, which is another document of the same application. Which one, is said on the Plant
activity document: the person who may publish it to the plants (its creator, or `sheets.publish-plants`) names it in the plants dialog, from the
documents of the application that person can read (the record store judges; no rule of ours). The link is a row of `inspection_action_plan_link`
(one live row for each document), kept apart from the document's record because that record is locked while an approval runs, and it stays through
every Revision. A form named as an Action plan form cannot be started from its own document ("started from the fill-in that needs it"), so a
free-standing Action plan that carries no link cannot exist.

The Action plan fill-in is told from any other by `parentFill`: the platform-written, **unique** field on its header holds the identifier of the
fill-in it answers, written once when it is started. Unique through the record store's own uniqueness (no claim table), so one Action plan exists for
each low-scoring fill-in and a second start is refused in words. The approval engine tells the two kinds of fill-in apart by entry criteria on this
field (`ISBLANK(parentFill)` and `NOT(ISBLANK(parentFill))`), which are data of the demo setup: no class names an application or an object.

**Who starts it.** `POST /api/v1/fill-ins/{id}/action-plan`: the caller must read the fill-in (the record store), hold the ability to fill in, be a plant
head **of the plant of that fill-in** (the same department data the plants are made of), and the fill-in must have the result `ACTION_PLAN`. Any plant
head of that plant may start it, so an absent person never blocks the plant; the form need not be published to anybody, because it is read here as
the platform. The Action plan carries the same plant and month, is pinned to the Revision of the form in use, belongs to the person who started it
and is shared read only with the plant's group of plant heads, as a monthly fill-in is. It claims no month of its own (the link is its uniqueness).

**Its reviewers** are those of the fill-in it answers: `PlantReviewers` follows the link by one hop and reads the document and plant of that
fill-in, never the choice made for the Action plan form. A reviewer of another plant is asked nothing and reads nothing.

### 5. A low score never reaches final approval without an Action plan

When the result is `ACTION_PLAN`, the hook tells the plant head who filled it with the new notice kind `ACTION_PLAN_NEEDED` (and the mail of the same
facts, switchable as every notice of the category Documents). The notice names no score, no limit and no plant: the words say only "below the limit";
its key is the fill-in, so it is made once. And the approval that ends the request (`DecisionContext.endsRequest`, ADR-0202) is refused, in fixed
words, until the Action plan has been **sent** (its status is submitted or approved): a draft or a returned one is not sent. Earlier steps are never
blocked. A fill-in with a good score, or a sheet with no score, has no such rule. If the score later passes (after a return and an edit) the Action
plan that exists stays linked and nothing else changes.

### 6. Top management is the last step of both processes

The last step of the process of the Plant activity and of the Action plan is a group (kind `GROUP`, set in Setup like any process; the demo setup uses
the Level 5 group). Approving it ends the fill-in as approved; rejecting returns it to its filler. Nothing in the engine changed for this. The header
numbers are never changed by a person.

### 7. Change requests of a Management Core document

A change request on a document of an application that publishes to plants follows Document Management's chain with the same people. The one thing
that did not hold: "whoever reads the document may ask" lets a plant head ask, because a plant head reads the document once it is published to the
plant. **A plant head of one of the document's plants who cannot change the document is told that management users ask** (`403`, fixed words). It is a
check in the raise itself, on the department data the plants are made of: no ability, no table and no second place that decides who sees a document.
A person who is both a plant head and may change the document is not held back. The request carries the application as before; the Revision it ends in
keeps the plants and their reviewers (the publication rows are the document's, not the Revision's).

### 8. What is never written down

A score, a result, a limit, a plant, a month, a document chosen as an Action plan form and a reviewer are the organization's data: never in a log, an
audit record, an event, a metric tag, a notice, a mail or an error message. The audit says identifiers, the application and the setting, counts and the
kind of change; refusals have fixed words. The limit is not in the notice, and the notice is not told the score.

## Consequences

- The score comes only from the sheet's own formulas: there is no second place a score could be typed or calculated. A formula the platform and the
  spreadsheet program calculate differently is answered by the platform's own formula engine, which is held to a file of cases (ADR-0252).
- The marker is a manual step by an author until SH6, and a Revision with no marker has no score (decision 1).
- The settings table and the link table are additive; a fill-in of an application with no score marker behaves exactly as before.
- Two fill-in processes now apply to Management Core, told apart by `parentFill`; a deployment that made the one process of MC2A keeps working until
  the demo setup (or an administrator) gives it the criteria, because the engine refuses two processes that both fit.
- The text slots of the fill-in object are 7 of 8 used.

## Notes on earlier decisions

- **ADR-0260.** The reviewers of a fill-in are the ones chosen for its document and plant; for an **Action plan** they are those of the fill-in it
  answers, through its link, never the choice made for the Action plan form. The demo process of Management Core fill-ins now has the entry criteria
  `application = 'management-core' AND ISBLANK(parentFill)`, and a second process for Action plans has `... AND NOT(ISBLANK(parentFill))`. "What
  top management decides in the score is MC2B" is answered by decisions 5 and 6.
- **ADR-0258.** The calculated answers are still computed and never stored; the score on the header is the one value the platform writes down, with
  the result, and it is written from the same calculation at the moments of decision 2.
- **ADR-0191.** A request is raised by whoever reads the document, except as decision 7 says for the plant heads of an application that publishes to
  plants.

## Note added after testing (2026-11-15)

- The standard metadata loader only allowed a text or yes/no field to be written by the platform alone. It now also allows a **decimal** (still never required, no default), so the score stays a number a list can sort and filter. Every fill-in object (`InspectionFill`, `EnvironmentFill` and the third-application fixture) carries `score`, `result` and `parentFill`, because the one approval hook of the fill-in serves them all.

## Note added 2026-11-17: one step, Submit, and no wait for the Action plan

The product owner decided that the monthly review ends at the reviewer: both processes are one step, the plant's reviewers, and the reviewer's
decision is shown as **Submit** (Return instead of Reject) on a fill-in made for a plant. Decision 6 changes:

- The refusal `action_plan_missing` is gone. The reviewer's Submit writes the score and the result and finishes the fill-in whatever the score; a fill-in
  below the limit tells its filler that an Action plan is needed (unchanged), and the Action plan is reviewed by the same reviewers in one step.
- Nothing in the engine or the hook names a step: a process with a later step still works, its last step simply no longer waits for the Action plan.
- The dashboard (ADR-0262 note of the same day) shows the Action plans owed, so a low score with no Action plan is seen rather than blocked.
- Level 5 (top management) takes no part in the review; it reads through the Observer policy.
