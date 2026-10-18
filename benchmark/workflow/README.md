# Workflow event benchmark (decision D3, ADR-0133)

Measures whether the outbox and its polling relay carry the events of record changes through the workflow handler. The
thresholds T1 to T6 are in [ADR-0133](../../docs/adr/0133-event-processing-stays-on-the-outbox-decision-d3-final.md); they were
written before any measurement and are never changed after a number is seen. The script is **not** part of the Maven build and
**not** run by CI.

## Run it

Needs Docker and Windows PowerShell, the local services (`infra/local`: `./init-env.ps1; docker compose up -d;
./init-app-role.ps1`) and the application running with the `local` profile. For a clean measurement start it with
`PLATFORM_WORKFLOW_ENABLED=false`, so that the worker does not run the executions the handler makes while the relay drains.

```powershell
cd platform
.\benchmark\workflow\run-event-benchmark.ps1 -Size smoke        # a minute or two, tiny data: proves the harness works; NOT a measurement
.\benchmark\workflow\run-event-benchmark.ps1                    # one instance: T1, T2, T4, T5, T6 (about 45 minutes)
.\benchmark\workflow\run-event-benchmark.ps1 -BaselineRate 140  # with three instances running: also T3 (140 = the T1 rate of the one-instance run)
.\benchmark\workflow\run-event-benchmark.ps1 -CleanupOnly       # remove the benchmark organizations of earlier runs
```

The result goes to `benchmark/results/<time>-workflow-<size>/summary.md`; the exit code is 0 when every judged threshold was met,
2 otherwise. Record the judged result in ADR-0133 ("Result"), with the machine, the relay settings and the number of instances.

## Workflows that write (Sprint 23)

`-Actions update` asks the same question for executions that change a record (`UPDATE_RECORD`), with thresholds A1 to A6 written
in [ADR-0133](../../docs/adr/0133-event-processing-stays-on-the-outbox-decision-d3-final.md) ("Sprint 23: write actions") before the
mode ever ran. It measures A1 to A6 only, and needs the application with the dispatcher on (`PLATFORM_WORKFLOW_ENABLED=true`, the
default): the actions are what is measured.

```powershell
.\benchmark\workflow\run-event-benchmark.ps1 -Actions update -Size smoke       # proves the harness; NOT a measurement
.\benchmark\workflow\run-event-benchmark.ps1 -Actions update                   # one instance: A1, A2, A3, A5, A6
.\benchmark\workflow\run-event-benchmark.ps1 -Actions update -BaselineRate 60  # three instances: also A4 (60 = the A1 of one)
```

| Threshold | How |
|-----------|-----|
| A1 | A backlog of triggering events for real records; the relay, the handler and the dispatcher drain it; rate = completed executions / seconds |
| A2 | Triggers arrive at half of A1's rate for 2 minutes; p95 and p99 of the time from the trigger's `outbox_event.created_at` to the execution's `finished_at` |
| A3 | `record.updated` events that carry a `chain` divided by completed executions: exactly 1 |
| A4 | The A1 rate of a run with three instances divided by the `-BaselineRate` of a run with one |
| A5 | The T2 backlog of an organization with no workflow, drained while the other organization's actions run |
| A6 | Eight `pgbench` writers (a record changed and its event written in one transaction) while a backlog of triggers is being turned into writes, then while there is none; ratio of the p95 values |

## What it does

It makes two organizations (slugs start with `bench-`): one with 20 workflows on one object (10 on `RECORD_UPDATED`, 5 on
`FIELD_CHANGED` of a field that is in the events, 5 on a field that never is), one with none. Then, with SQL as the owner role:

| Threshold | How |
|-----------|-----|
| T1 | A backlog of events for the first organization; the real relay and handler drain it; rate = events / seconds; the number of executions must be 15 per event |
| T2 | The same for the organization with no workflow |
| T3 | The T1 rate of a run with three instances divided by the `-BaselineRate` of a run with one |
| T4 | Events arrive at half of T1's rate for 2 minutes; p95 and p99 of the time from `outbox_event.created_at` to `workflow_execution.created_at` |
| T5 | Eight `pgbench` writers (a record row changed and its event written in one transaction) while the relay drains a backlog with work, then while it drains one without; ratio of the p95 values |
| T6 | The p95 of `platform.workflow.event.handling{result="no_workflow"}` from the application's Prometheus endpoint (the upper bound of the histogram bucket, so it is a conservative figure) |

## Limits of the measurement (stated, not hidden)

Latency is measured at the database for T4 and T5 and inside the handler for T6: no network time and no request time. The
generated events are the shape the publisher writes, not events of real writes. A pass is a pass only for the machine, the
relay settings and the data size recorded with it (ADR-0082). The script was written in Sprint 22 and extended in Sprint 23; both
modes have been parsed but not run: the first run of each is its smoke run.
