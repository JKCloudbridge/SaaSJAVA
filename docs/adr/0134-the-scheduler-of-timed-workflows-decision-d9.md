# ADR-0134: The scheduler of timed workflows is our own, on the claim-and-lease idiom (decision D9)

- **Status:** Accepted (the product owner confirmed the choice of an own scheduler on 2026-10-16, as the plan required before
  choosing it)
- **Date:** 2026-10-16
- **Sprint:** S22
- **Decision IDs:** D9
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md) (claim and lease), ADR-0009 (migrations and
  database roles), [ADR-0015](0015-row-level-security-implementation.md) and [ADR-0135](0135-the-workflow-dispatch-system-scope.md),
  [ADR-0136](0136-workflow-definitions-versions-and-lifecycle.md); the evidence is in
  [../spikes/d9-scheduler-comparison.md](../spikes/d9-scheduler-comparison.md)

## Context

The plan said "a Postgres-backed scheduler library". Nothing yet fires something at a tenant-defined time: the periodic jobs of
the platform (`RecycleBinRetention`, `IndexBackfill`, the mail relay) are fixed jobs of the platform, one timer each. A workflow
can be scheduled by an organization (every day at 09:00 in its time zone, every Monday, once at a moment, a number of days after
a date field), and the number of such schedules grows with the number of organizations.

A short, time-boxed comparison of the kinds of candidates was made against the criteria the plan set. Names of libraries appear
only in the evidence note, not here.

| Criterion | A job-store library with its own tables | A cron library with a JDBC job store | A job-queue library with a dashboard |
|-----------|------------------------------------------|---------------------------------------|---------------------------------------|
| Works under forced row level security and per-tenant isolation | **No.** Its tables are not tenant tables and its statements set no tenant; they would have to be platform-level tables with no policy, so tenant isolation of schedules would rest on application code alone (ADR-0003 forbids it) | **No**, same reason | **No**, same reason |
| Java 25 and Spring Boot 4.1 | Starters exist for Spring Boot 4; Java 25 not confirmed from the sources found | Supported by Spring Boot; version freshness not confirmed | Starter exists; Java 25 not confirmed |
| Several instances never run a job twice, a dead instance's job is taken over | Yes | Yes (clustered mode) | Yes |
| No vendor lock-in or hosted component | Yes | Yes | Dual licence (a commercial one exists), a dashboard component |
| Dependency and vulnerability status | One more dependency to scan and patch | A large dependency tree | The largest tree |
| Its own tables fit the migration rules (ADR-0009) | They would be created by its own script, or copied into a migration and then drift from the library's version | Same, many tables | Same, many tables |

None passes the first criterion, which is the one that cannot be bought back with configuration. Beyond it, the schedule here is
**data of the organization**: a zone, a daylight-saving change, a window of dates that is not lost when the platform was down,
a firing that creates one execution per record in bounded pages and resumes where it stopped. A generic scheduler would only
call a method at a time; everything that matters is ours either way.

## Decision

1. **A small scheduler of our own, built on the idiom of the outbox** (ADR-0016): the schedule is a row (`workflow_schedule`,
   tenant-scoped, V053); a poll claims due rows with `FOR UPDATE SKIP LOCKED` and a lease; the version after the claim is the
   claim's token; a dead instance's lease expires and any instance takes the row over, with the firing resuming from the position
   it had reached. No new dependency.
2. **Rows are derived, not authoritative.** The published workflows are the truth. The handler of `metadata.published`
   (`ScheduleReconciler`) makes the rows say what the workflows say: a row for every active scheduled workflow, started over when
   the definition changed, removed when the workflow was switched off, removed or is no longer scheduled. Handling the event
   twice, late or out of order changes nothing. Switching a workflow off and on starts it from the moment it is switched on;
   nothing is fired for the time it was off.
3. **Kinds and time rules.** `EVERY_DAY`, `EVERY_WEEK` at a time of day, `DATE_TIME` (once) and `DAYS_AFTER_DATE`, each with an
   explicit IANA time zone in the definition (default UTC; there is no organization-wide zone setting yet). A time of day is a
   wall-clock time once per local date; when the clock skips it the occurrence moves forward by the gap, when the clock repeats it
   the earlier one is used. A date therefore has exactly one occurrence, whatever the clock does (`ScheduleMath`, a pure function
   with unit tests for the spring and autumn changes and for a changed zone).
4. **A missed time is not made up in a burst.** After downtime the firing that runs is the latest due occurrence; the earlier ones
   are skipped. `DAYS_AFTER_DATE` loses no record by that, because its window starts where the covered period ended
   (`covered_through`), not at the previous firing.
5. **Once per firing.** The key of an execution made by a firing is the workflow version, the instant of the firing and the
   record; a firing that is retried, resumed after a crash or claimed twice makes no second execution. The firing writes how far
   through the records it got after every page (`firing_after`).
6. **Bounded.** Records are read in pages with the query engine's own stages (`BackgroundRecords.scan`, as the platform, never as
   a member); a page is one transaction; a firing makes at most `platform.workflow.scheduler.max-records-per-firing` executions
   (100,000), then stops, is recorded as stopped and the next firing starts afresh. A firing that fails is tried again with a
   growing pause, and given up after `max-attempts` (5) so that one broken definition cannot hold a schedule forever; the failure
   type is kept on the row and reported to the error tracking.
7. **The condition is judged at the firing too**, so a daily workflow over a large object does not write a skipped row per record
   per day: a record with a false condition gets no execution; one whose condition cannot be evaluated does get one, so the
   reason is recorded. The condition is judged again, on the record as it is, when the execution runs.

## Consequences

- No new dependency to scan; the failure modes (lease expiry, takeover, token) are the ones already proven for the outbox.
- A schedule row exists only after the event of the release was handled (seconds). A dead-lettered event leaves a workflow
  without a row until the next release or a redrive; the dead letter is visible (ADR-0016). There is no timed sweep of every
  organization: it would cost a catalogue build per organization per pass.
- The scheduler polls every 15 seconds by default; a schedule can start up to that late. This is stated in the guide.
- Scale: a claim is one indexed statement over due rows of all organizations; a firing is bounded by its limits. A load test of
  many organizations with many schedules is part of the hardening sprint (Sprint 25).

## Alternatives considered

- **A library** (all three kinds): rejected on the first criterion and for the reasons above; reconsider if the platform ever runs
  its schedules in one shared, non-tenant job store on purpose, which would need its own ADR against ADR-0003.
- **One timer per schedule in memory:** thousands of sleeping timers, lost on restart, a second instance runs it again.
- **A leader instance that fires everything:** a bottleneck and a failure mode; claims need no election.
- **Walking every organization on every tick (the way the recycle-bin clean-up does):** cost grows with the number of
  organizations, not with the number of due schedules, and cannot use an index on "next due" ([ADR-0135](0135-the-workflow-dispatch-system-scope.md)).

## References

- `platform-app/src/main/java/app/platform/workflow/internal/` (`ScheduleMath`, `ScheduleStore`, `ScheduleRunner`,
  `ScheduleReconciler`, `Dispatcher`, `DispatchStore`)
- `platform-app/src/main/resources/db/migration/V053__create_workflows_and_executions.sql`
