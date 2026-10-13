# ADR-0109: The benchmark as a regression suite

- **Status:** Accepted (the gate result is recorded as **not passed**; see Results)
- **Date:** 2026-10-13
- **Sprint:** S16
- **Related:** [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0083](0083-storage-benchmark-result-gate-g1.md) (condition 2),
  [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0102](0102-the-slot-copy-in-the-write-path.md),
  [ADR-0105](0105-filters-and-orders-without-an-index-on-large-objects.md), [ADR-0108](0108-limits-per-organization.md)

## Context

Gate G1 (ADR-0083) was passed on a hand-written proof table. Sprint 16 puts a query engine, typed copies of indexed fields and record links on the real
record table. A benchmark that still measured a proof table would say nothing about them, and a threshold moved after a number is seen would say nothing at all.

## Decision

1. **The scenarios are generated from the production code.** `BenchmarkStatements` (test sources) runs the real analyzer, planner and statement builder
   and writes `benchmark/scenarios/generated/*.sql` on every run; they replace the hand-written scenario of the same name. The statements of an update and
   an insert are the ones the application sends, with all twenty-two typed copies. The harness uses the real record table (V036, V041, V042).
2. **Thresholds are unchanged** (ADR-0082): a threshold is never moved after a number is seen. A pass within 10 percent of its limit is reported as a
   warning (ADR-0083, condition 2).
3. **New measurements:** the cost of the typed copies (`s02p`, judged at 40 ms like S2; `s09p`, reported), the guard that counts up to 20,001 index
   entries before an unindexed scan (`s03g`, judged at 20 ms), and the noisy neighbour with and without the limits of ADR-0108 (reported).
4. **S9 (bulk) does not go through the application** before Sprint 17 has a bulk path. S9a and S9b are measured with the real INSERT and UPDATE statements
   including the slot parameters, which is the storage ceiling, not an end-to-end figure. `s09p` is the single-record insert of the application.
5. **Not in CI.** It needs an hour and 10 GB; it is run before a release that changes storage, indexes or queries, and the result file is kept.

## Results

Machine: 14 physical cores, container with 7 GB and 14 processors, PostgreSQL 18.6, 3.4 million record rows, 2.2 million in one object.
Two full runs were made. **Neither passed the gate.** They differ so much on scenarios that did not change (S1 0.59 ms in run A, 3.01 ms in run B) that
run B was evidently disturbed by the machine; the figures of both are kept and neither is picked.

| Scenario | Limit | Run A | Run B |
|----------|-------|-------|-------|
| S3 number (indexed range, sorted by id) | 150 ms | **169.7 ms** | **1088.6 ms** |
| S6 AND | 150 ms | 80.2 ms | **426 ms** |
| S4b (unindexed scan, 8 clients) | 1000 ms | 616.5 ms | **2830 ms** |
| S4 pool (16 scanners, failed transactions) | 0 | 0 | **11** |
| S9b bulk upsert | 5000 / s | **1412 / s** (one 13 s stall) | 8264 / s |
| S11 s01 row level security cost | 1.1 x | 1.035 x | **1.129 x** |
| S10 forward / reverse | 150 ms | **529 / 516 ms** (harness fault, below) | 43 / 41 ms |
| S2 production (all copies written) | 40 ms | not run (harness fault) | 6.27 ms |
| S3 guard | 20 ms | not run (harness fault) | 9.51 ms |
| S7 (small organizations beside a busy one) | 1.25 x | 1.08 x | 1.09 x |
| S8 lost updates | 0 | 0 | 0 |

- **S3 number is a real finding, not noise:** it fails in both runs. The plan is the right one (the number index, 22,235 rows in the range, then a sort by
  id), but the exact check against the stored value (`pg_input_is_valid` and a cast of the payload) is applied to every row of the range, not only to the
  rows whose double value lies close to a bound. Rows strictly inside the range need no check. **Not fixed in Sprint 16** (owner's decision on
  2026-10-13: a correctness-sensitive change to the planner with no test run is worse than a recorded failure). It is the first item of the next sprint that
  touches the query engine, with a unit test of the margin and a differential case.
- **S6 AND** probably has the same cause (it uses the number field); it passed in run A.
- **Harness faults found and fixed during the sprint:** the many-to-many table of the harness had lost its indexes (S10 in run A); the `s02p` statement
  overflowed 32-bit arithmetic at full size (the phase failed in run A); the function that reads a p95 was defined after its first use.
- **Cost of the typed copies:** the update with all copies written took 6.27 ms against 10.22 ms for the Sprint 14 statement in the same run, so the copies
  did not slow it down measurably (the difference is run noise, not a gain). The single-record insert ran at 287 per second at one session (p95 5.07 ms).
- **Noisy neighbour** (run B, small organizations' p95 divided by their p95 alone): **2.39 times** with the large organization on 12 non-stop sessions
  and no limits, **0.72 times** with the limits of ADR-0108 (smoke size: 15.4 and 0.58). In ADR-0083 run 1 it was 22 times, in run 2 1.7 times.
- **c2 (authentication, one client):** 0.20 ms in run B (0.26 ms at smoke size); run A's carried notes were disturbed and are not used. The cache
  recommendation of ADR-0083 is unchanged: nothing to add.
- **Not measured:** the application and the network; authorization predicates of record-level security (Sprint 17); ten million records in one object.

## Consequences

- Gate G1 is **not re-passed** by this sprint. Its pass in ADR-0083 stands for what it measured; the Sprint 16 additions are not yet shown to meet their
  thresholds on a quiet machine. Before production the benchmark is run again on the intended instance with nothing else running (condition 6 of
  ADR-0083), and S3 number must be fixed, or its threshold argued in a new ADR, first.
- The slot copy is not a measurable cost on update; the limits work as intended against a noisy neighbour.
- Anyone changing the planner, the statement builder or the write statements runs the benchmark: the scenarios follow the code by construction.
