# Runbook: rollback

Sprint 33 (decisions in [ADR-0288](../adr/0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md)). Rehearsed with
`./build-previous-image.ps1` and `./rollback.ps1`.

## When to use this

A release is wrong: errors, a failed feature, a regression you cannot fix in minutes. Roll back first, find the cause after.

## The rule

**The way back is the previous image, never an undo of the database.** While a release can still be rolled back, its migrations
only add things, so the release before it runs on the new schema (the migration library ignores migrations newer than the release,
and the application's schema check accepts a schema that is ahead). There is no down-migration and none is ever written.

## Steps

1. Say so: note the time and the release that is being left.
2. Set the application, web and edge services to the previous tags (the ones written down in [deploy.md](deploy.md), "Before").
   **Do not run the migration job** (there is nothing to undo, and the old release's job would find nothing to do anyway).
   The rolling release is the same procedure as going forward: no dropped request.
3. Check: every task carries the previous stamp, readiness is true, the alarms are quiet.
4. If the new release made data that the old one cannot read (it should not: an expand step adds, the old release ignores), keep
   the new data and tell the people who own that feature; do not delete it.

## When a rollback is not possible

- A **contract step** (a column or table removed) has already run. That step belongs to a release later than the one that stopped
  using the thing, and its runbook entry says the rollback window has closed. Fix forward.
- The previous release is from **before the migration job existed** (the first rollback target after Sprint 33 is a release that
  has it). Such a release needs its own old environment shape: the owner's database credentials and its signing key files given as
  files. The rehearsal does exactly that for the test, as that release's own deployment would have.
- A change of the **secrets or keys** was part of the release: roll the keys back by their own runbooks
  ([signing-key-rotation.md](signing-key-rotation.md), [kek-rotation.md](kek-rotation.md)), never by the image alone.

## How I know it worked

`platform_schema_version()` is unchanged (the rollback did not touch the schema); every task has the previous stamp and is
ready; `balancer-5xx` and `http-5xx` are quiet; the rehearsal's `./rollback.ps1` prints `RESULT PASS`.
