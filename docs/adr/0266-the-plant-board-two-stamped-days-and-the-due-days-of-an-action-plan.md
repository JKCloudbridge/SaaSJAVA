# ADR-0266: The plant board (Option 1), two stamped days and the due days of an Action plan

- **Status:** Accepted (the product owner gave the go on 2026-11-20)
- **Date:** 2026-11-20
- **Track:** Functional (Management Core). **No migration.** One endpoint added, two platform-written fields on the fill-in objects, one setting kind, two
  folders in the frontend. **No new module edge.**
- **Related:** ADR-0262 (the dashboard that compares the plants: the counting rule this keeps; it is now Option 2), ADR-0261 (score, limit, Action plan),
  ADR-0260 (plants, monthly fill-in), ADR-0115 (one place decides who sees a record), ADR-0012 (what is never logged)

## Context

Business will choose between two Home layouts for Management Core. The new one (Option 1) shows a tile for each plant, the score by month and an Action plan
tracker with the day each plan was sent, the day it is due and the day the plant head sent it. The existing dashboard stays as Option 2. The tracker needs
days that the platform did not keep in a form it controls: the send and decision times of a fill-in are written with the database's `now()`.

## Decisions

### 1. Two days, written once by the platform from the injected clock

`scoredOn` (the day the reviewer's Submit first stamped the score) and `planSentOn` (the day the plant head first sent an Action plan) are text fields
`yyyy-MM-dd` (UTC), platform-written, on `InspectionFill` and `EnvironmentFill`. `scoredOn` is written by the approval hook (`FillApprovalHooks.beforeApprove`,
declared in `writes()`) only while empty; `planSentOn` by `FillApprovalHooks.onSubmit` through `FillHeaders.planSent` on the first send of a fill-in that has
`parentFill`. The day comes from `FillPeriods.today()`, which uses the injected `Clock`. A test (`PlantBoardStampsUseTheClockTest`) fails if a class that
writes, reads or judges a stamp contains `now()` or asks the machine for the time. A correction after approval goes through `FillScores` and never moves
`scoredOn`; the status follows the new score. Both fields are in the read list of every shipped policy that lists `score`.

Rejected: reading the dates already stored (`submitted_at`, `decided_at`, approval history): they come from the database clock, so they cannot be controlled
in a test. A fill-in older than the stamps has no day: it is `NO_DATE`, shown with a dash and left out of the sent, on-time and overdue counts. A day is never
guessed.

### 2. `actionPlanDueDays`

A declared setting of the Management Core file (kind `DAYS`, 1 to 365, default 10), edited in Setup, Application settings, next to `scoreLimit`. Due is
`scoredOn` plus the days; a plan sent on the due day is on time; overdue days are whole days after the due day, counted in UTC. The server sends the due day
and the browser repeats no number. It applies at once (nothing is stored per fill-in).

### 3. The status rule

All on the server (`PlantBoardNumbers`, pure). Whether a fill-in needs a plan is the stored `result` (`FillScores` is the one place that compares a score with
the limit). Statuses: `NO_PLAN`, `NO_SCORE`, `AWAITING`, `SUBMITTED`, `LATE`, `OVERDUE`, `NO_DATE`, and for the month in progress `IN_REVIEW` and `NOT_SENT`.
A plan started but not sent is judged like one not started. A plan sent before the stamps existed is `NO_DATE`. **Overdue and late are shown, not acted on: no
reminder, notice, mail, escalation or block is built, on purpose.**

### 4. One more endpoint, the same counting rule

`POST /api/v1/management-home/plant-board` takes the same question as the summary. It reuses the scans of `ManagementHome` (narrowed to the application, bounded,
"at least", a failed section named in `problems`, sections null for a caller who may not read fill-ins). Views start with `Management` (the generator merges
classes of one simple name). The scans of Option 2 do not ask for the two days, so a profile without them is not refused there. The chart and the grid show months
that have ended, without leading months in which nothing was recorded; the month in progress is the last grid column only. Each plant keeps its colour place
whatever the filter.

### 5. The two options

A two-choice control `Option 1 | Option 2` at the top right: the address (`?view=1|2`) wins, then `localStorage` (every access in try/catch), then Option 1. It is a
presentation choice only. Each option is a folder (`management/option1`, `management/option2`). Option 1's default span is 12 months. Colours are series tokens
and the new `--color-warning` pair; no patterns; the dotted joining piece across a gap is one function (`bridgePath`) and one class (`pb-bridge`). The explaining
sentences of both options were removed. The page asks again on open and when the tab becomes visible (at most every 5 seconds), with no timer and no cache.

## Not built, on purpose

Reminders, notices, mails, escalation or any block from an overdue or late plan; a limit line on Option 1's chart; the November status strip and the score table
of the reference.

## How to change this later

- A different joining piece across a gap: edit `bridgePath` and `.pb-bridge` (alternatives: a thin solid line in a lighter tone, or no piece at all).
- Another due rule: change `PlantBoardNumbers.judge`; the setting stays.
- To act on overdue plans: a new sprint, reading the same stamps.

## How to remove an option

Delete the folder `platform-web/src/solutions/inspection/management/option1` (or `option2`), its import and its entry in `OPTIONS` in `ManagementHome.tsx`, and for
Option 1 the block at the end of `globals.css` and the endpoint `plant-board` with `ManagementBoardViews` and `PlantBoardNumbers`.

## Amendments (2026-11-20, after the owner's review)

- **Option 2 was removed from the screens** (decision 5 above no longer applies): there is no switch and no `localStorage` choice; the plant board is the only Management Core
  Home. The summary endpoint of ADR-0262 remains in the backend, unused by any screen.
- **Added to the board:** a "Pending with reviewer" table (sent and waiting; no reviewer named; the sent day is the record's last-changed time, display only) and four KPI tiles
  (average days to submit and on-time share over plans with both stamped days only, plants reported this month, longest run of months passed by the stored result).
- **Changed:** a score at or above the limit reads "Passed"; the Y axis of Score by month is in steps of 10 up to 100; the end-of-line labels and the highlight were removed and a
  hovered dot shows a tip (above, or below near the top). The reduced top padding applies to the board only.
