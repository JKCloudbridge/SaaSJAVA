# ADR-0262: The Home dashboard that compares the plants month by month

- **Status:** Accepted (the product owner answered the questions of the MC3 plan with the recommendations on 2026-11-15)
- **Date:** 2026-11-15
- **Track:** Functional (Management Core, part 3). **No migration.** One endpoint, one option of the solution file, two more declared settings,
  one added method on a shared-kernel seam, one added filter of the fill-in list. **No new module edge.**
- **Related:** ADR-0261 (the score, the limit, the Action plan, top management last), ADR-0260 (plants, reviewers, the monthly fill-in), ADR-0259
  (the narrowed application), ADR-0203 (the dashboard of the Quality Management System: the counting rule this one keeps), ADR-0226 (applications
  of one kind on shared code), ADR-0115 (one place decides who sees a record), ADR-0012 (what is never logged)

## Context

Top management compares the plants: how each did this month, how that moved over the months, who is below the limit, which Action plans are open and
what waits for a decision. ADR-0261 made every number readable with the record store's own query (`plant`, `period`, `score`, `result`, `status` and
`parentFill` are indexed header fields). What was open: how the dashboard is chosen for one application without the browser naming it, which fill-ins
count for which number, who may be told how many plants there are, how "waiting for top management" is known, how the bands are worded, and how a
tile can open exactly the list it counts.

## Decisions

### 1. A second dashboard, chosen by data

The application's solution file says `options: dashboard: management` (it needs `monthlyFillIn` and `routedByNumber`; the start is stopped otherwise).
The API lists a new `options` map with each application (`MyApplicationView`, `ShippedApplicationView`: plain data, additive); the generic `home` page
(`HomePage`) draws the dashboard that compares the plants when the application's options say so and the Quality Management System one otherwise. The
first entry of every application stays the page key `home`, so no validator rule changed and **no browser code names an application**. The server
agrees: the endpoint answers "not found", in the words of any unknown page, for an application whose file does not ask for it.

### 2. One endpoint, its own views

`POST /api/v1/management-home/summary?application=` (`ManagementHome`, module `inspection`, nothing names an application or an object). The views carry
the prefix `Management` where a name already belongs to the other dashboard (`ManagementSummaryView`, `ManagementTile`, ...): the OpenAPI generator names a
schema by its simple class name, and two classes of one name silently become one schema. The question is `month` (`this`, `last` or `yyyy-MM`; months
are months in UTC), `span` (3, 6 or 12 months the comparing charts cover), an optional `plant` and an optional `form`. Anything the caller may not choose is
refused in fixed words that repeat nothing.

### 3. The counting rule is the Quality Management System page's

No rule about who may see a record is written here. Every number is made over header facts the record store returned to this caller through the set's own
narrowing (`RecordSet.narrow`, so a fill-in of Document Management or of the Quality Management System is never read), by one bounded scan of the headers a
page of 200 at a time up to `platform.inspection.home.max-scanned`; a number that hit the bound says "at least". Nothing is cached across people. A section
whose object the caller cannot read is null, never a zero; each section runs in a savepoint and a section that fails is named in `problems` while the others
answer. A score is a number on the header; it is never calculated again.

### 4. Which fill-ins count for which number

- **A plant's score for a month** is the average of the scores of its **approved** fill-ins of that month, rounded half up to two decimals. A score is written
  at every approval, so one that waits at the last step already has a score that can still change; it is counted when the fill-in is approved.
- **The score of the month** is the average of the plants' scores (not of the fill-ins: a plant with many forms does not weigh more). A plant with no approved
  fill-in has **no score, which is a gap in every chart and never a zero.**
- **Plants that sent**: plants with a fill-in of the month that is waiting or decided (status submitted or approved), Action plans left out. The whole of
  "n of m" is below.
- **Plants below the limit**: plants with such a fill-in whose stored `result` is `ACTION_PLAN`. This is the decision made at the review, so a list that
  filters on the same field says the same. The charts draw the limit in force now; a bar under the line whose decision was `PASS` (the limit was
  changed after the review) is marked "decided under an earlier limit". The extra number of the tile is how many of those fill-ins no Action plan answers.
- **Waiting** (every month): fill-ins with a pending request, at the last step that can still decide (**top management**) or before it (**review**). A
  fill-in sent with no approval process has no pending request and is in neither.
- **Action plans open** (every month): Action plans (`parentFill` set) that are not yet approved. The Action plan chart splits them into being written or
  returned, sent and approved.
- **Plants versus fill-ins.** A tile counts plants and the list it opens lists fill-ins; with one fill-in for each plant and month they are the same, with two
  forms in one plant and month the list is longer. The word "plants" is on the tile.

### 5. How many plants there are, and who is told

"2 of 3" would let a plant head infer that another plant did not report. So the whole is **the plants the caller may see**: all the plants the documents
they may read are published to for a person who may view every fill-in (the decision API's `VIEW_ALL` on the fill-in object, the same bypass the record
store uses), else the plants they are plant head of; and in every case the plants of the fill-ins the store gave them. The filter bar offers the same list; a
plant outside it is refused like any unknown plant.

### 6. "Waiting for top management" is the last step, and the engine says it

The approval module answers the new bulk read `ApprovalDecisions.pendingSteps(object, ids)`: for each record with a pending request, the step it waits at and
whether **no later step that is not skipped** is left to decide. One statement for a whole page of records. "Top management" is therefore data (the process's
last step, ADR-0261 decision 6), not a name in code, and a process that skips its last step when nobody can be asked is counted right.

### 7. A tile opens the list it counts

The fill-in list reads, from its address, a **slice** (`addressSlice.ts`): `period`, `plant`, `sent`, `needs`, `scored`, `plan=none|open` and
`waiting=review|top`, each a condition on a header field the record store judges, joined with AND to the list's own filter. Only `waiting` needs the server:
`POST /fill-ins/query` takes `waiting` and drops, from the pages the store judged, the rows whose pending request is at the other kind of step. **A page of
such a list may be short while a cursor remains** (it reads on up to ten pages to fill one); the screen already pages by cursor. A value of a shape the server
does not know is left out of the slice, never guessed.

The form filter narrows the tiles and charts of the month; the waiting tiles and the Action plan numbers are not narrowed by form (an Action plan belongs to
another document than the fill-in it answers, and a waiting fill-in is waiting in every month), and the page says so when a form is chosen.

### 8. The words of the bands are Setup's, and the limit is not the legend

Two more declared settings of the application, `excellentAbove` (85) and `goodAbove` (65), next to `scoreLimit`: a score **above** `excellentAbove` is
Excellent, above `goodAbove` up to that line is Good, anything else is Poor, so exactly 85 is Good and exactly 65 is Poor. The legend and the Action plan
rule are two numbers that happen to share 85. A setting may declare `below: <other key>` (a generic rule of the solution file): the service refuses, in fixed
words that name the other setting by its label and no value, a value that meets or passes it, in both directions and also when a choice is taken back; the
loader refuses a default that already breaks the order. No number of the organization is in the browser: the legend arrives in the answer.

### 9. What is never written down

A score, a limit, a band number, a plant, a month, a form or a filter value is the organization's data: never in a log, an audit record, an event, a metric
tag, a notice or an error. The metric `platform.inspection.management.home{result}` carries the result and the duration. The settings are audited by
application, key and the kind of change (ADR-0261).

## Consequences

- A big organization pays one bounded scan of the headers of the span (plus two small ones); the cost test measures 11,520 headers and states the numbers.
  Beyond `max-scanned` a number says "at least".
- A fill-in that waits at a step is not counted in a month's score until it is approved; the waiting tiles are where it shows meanwhile.
- A person who reads the fill-ins of one plant sees one plant everywhere (tiles, bars, lines, ranks, filters, the whole of "n of m").
- The Quality Management System, Document Management and Environment Management dashboards are untouched; the new `options` of an application is additive.
- A reviewer with no access policy reads no fill-in through the record store and so sees no section: being asked to review gives a task, not sight of the
  record (ADR-0261, ADR-0148).

## Not built, on purpose

An export or an e-mail of the dashboard, a drill-down page for one plant (a tile opens the list), targets other than the limit, per-person scores, a chart
library, the generic Dashboards feature (D8), the KPI management, Management Review and Plantwise Review pages, reminders to plant heads.
