# Runbook: deploy (the normal release)

Sprint 33 (decisions in [ADR-0287](../adr/0287-images-the-build-stamp-and-the-tag-rule.md),
[ADR-0288](../adr/0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md),
[ADR-0289](../adr/0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)). Each command was run against the
rehearsal (`infra/rehearsal`) or is marked *(cloud)*: run by the owner in the cloud.

## When to use this

To put a merged release into staging or production with live traffic and no failed request.

## Before

1. CI is green on the commit. The three images exist in the registry with the commit as their tag (the owner pushes them:
   `Sprint 33 manual steps.md`, "A release"). **Never `latest`.**
2. Read the migrations of the release (`git diff <running tag>..<new tag> -- platform-app/src/main/resources/db/migration`). The
   long-lock test already refuses a heavy one; if a file carries `long-lock-reviewed`, read the reason and pick a quiet hour.
3. Take a manual database snapshot before **the first** run of a migration that changes data *(cloud)*.
4. Confirm the previous release's tags are written down: they are the rollback.

## The order

1. **Migration job**, with the NEW application image, as the owner: run the migration task definition once and wait for it
   *(cloud)* (`run-task` with the migration definition, the application subnets and the migration security group; the task prints
   two lines and exits 0). Rehearsal: `Invoke-MigrationJob` (inside `release.ps1`). Two at once are harmless (the second waits and
   finds nothing to do). **If it fails, stop: nothing else has changed, and the running release is still running on the old schema.**
2. **Application service:** update the service to the new application image (and the same release's edge image). The service
   starts new tasks beside the old, waits for them to be healthy (readiness through the sidecar), then stops an old one, which says
   "not ready", keeps serving for the shutdown delay, drains and exits. A release that does not become healthy is rolled back by
   the service's circuit breaker.
3. **Web service:** the same with the web image.
4. Smoke: the page loads, a sign-in on an organization host works, the stamp is the new one
   (`/actuator/info` on a task: `release.commit`).

## What to watch

The alarms of [alerts.md](alerts.md), above all `balancer-5xx` (a dropped request), `http-5xx` and `api-tasks-low`; the service's
events; the log line `The platform is running: version ..., commit ...` of each new task.

## The numbers (so a release drops nothing)

Shutdown delay 10 s >= health-check interval 5 s x unhealthy threshold 2; worker grace 15 s < phase timeout 30 s; application stop
timeout 90 s >= two phases + 15; deregistration delay 60 s >= delay + phase timeout; balancer idle timeout 130 s > the longest edge
time limit (120 s). `DrainNumbersTest` fails the build when the values files break the order; change them together or not at all.

## Never

Run two incompatible versions against one schema: the rule is that the **old image runs on the new schema** (expand/contract,
[rollback.md](rollback.md)); a migration that breaks it is held back to a later release.

## How I know it worked

Every task carries the new stamp; no `balancer-5xx` or `http-5xx` alarm fired during the release; the number of tasks is what it
was; the migration job's log said `the schema is at version N` and `platform_schema_version()` is N. Rehearsal: `./release.ps1`
prints `RESULT PASS` (zero failed requests, p95 within 2x).
