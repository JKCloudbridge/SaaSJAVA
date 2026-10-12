# ADR-0083: Storage benchmark result (gate G1)

- **Status:** Accepted on 2026-10-12. Run 1 FAIL (5 of 24), run 2 PASS (24 of 24). The gate verdict is PASS on run 2 with the six conditions below. The owner delegated the decision to the assistant ("as per production grade standard"), who accepted it with condition 6.
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0082](0082-storage-benchmark-specification-gate-g1.md) (specification, thresholds, amendments 1 and 2)

## Run 1: 2026-10-12 07:48, result folder `benchmark/results/20261012-074820-default`

Judged exactly against the thresholds of ADR-0082. Full numbers are in the result folder (`summary.md`, `raw.csv`, `environment.txt`, `raw/diagnostic-plans.txt`). Container: 6 processors, 6 GB.

**19 of 24 thresholds passed; 5 failed, so by rule 1 of ADR-0082 run 1 is a FAIL.** Nothing was left unmeasured. The five failures: S4b (whole-object scan, p95 1222.78 ms, limit 1000), S4 pool (5 transactions failed or
timed out at 16 clients, limit 0), S7 (noisy neighbour 1.64 times, limit 1.25), S9a (bulk insert 4783 records/s, limit 5000), S9b (bulk upsert 2489 records/s, limit 5000). All the other numbers are in the table below.

**Possible cause, written before any re-run was started:** the container was given 6 of the host's 20 logical processors and 6 GB of memory, a limit the assistant chose and ADR-0082 does not fix. The loaded database is 5.4 GB,
essentially the whole container memory, and the five failures are the scenarios that read or write the most data at the same time. A too-small container is a plausible explanation. It was a hypothesis, not a reason to discard
the result.

## Run 2: 2026-10-12 08:41, result folder `benchmark/results/20261012-084126-default`

Same data, scenarios, thresholds and harness. The only change: the container limit raised to 14 processors and 7 GB (the Docker VM holds 7.6 GB, so memory could not go further).

| Scenario | Run 1 (6 CPU, 6 GB) | Run 2 (14 CPU, 7 GB) | Limit |
|----------|---------------------|----------------------|-------|
| S4b non-indexed full scan, 8 clients, p95 | 1222.78 ms **FAIL** | 644.44 ms | ≤ 1000 ms |
| S4 pool, failed or timed-out transactions at 16 clients | 5 **FAIL** | 0 | 0 |
| S4 probe, read beside the scans, p95 | 54.18 ms | 6.03 ms | ≤ 100 ms |
| S7 noisy neighbour, loaded ÷ alone | 1.64× **FAIL** | 1.06× | ≤ 1.25× |
| S9a bulk insert, one worker | 4783/s **FAIL** | 8565/s | ≥ 5000/s |
| S9b bulk upsert, one worker | 2489/s **FAIL** | 5108/s | ≥ 5000/s |
| S1 read by id, p95 | 1.39 ms | 1.35 ms | ≤ 20 ms |
| S2 update by id, p95 | 7.88 ms | 7.15 ms | ≤ 40 ms |
| S3 indexed filters (5), worst p95 | 2.58 ms | 1.87 ms | ≤ 150 ms |
| S5 sort and keyset page, p95 | 2.45 ms | 1.79 ms | ≤ 150 ms |
| S6 AND / OR, p95 | 31.74 / 20.95 ms | 7.60 / 4.02 ms | ≤ 150 ms |
| S8 lost updates | 0 | 0 | 0 |
| S10 joins forward / reverse, p95 | 1.49 / 1.58 ms | 1.44 / 1.55 ms | ≤ 150 ms |
| S11 row level security cost, median on÷off (s01 / s03a / s04b) | 1.040 / 1.059 / 0.965 | 1.032 / 1.028 / 1.050 | ≤ 1.10 |
| S12a growth of table and indexes after vacuum | 1.1% | 2.6% | ≤ 30% |
| S12b p95 of last third ÷ first third | 0.88× | 0.85× | ≤ 1.25× |

Run 2: **24 of 24 thresholds met, nothing unmeasured.**

## Reading the two runs together (plain words)

- What every ordinary screen depends on (read a record, filter, sort and page on an indexed field, update, join, the cost of security) passed in **both** runs, far inside the limits: a few milliseconds against limits of 20 to 150.
  It did not depend on the container size.
- What changed between the runs is only what is limited by raw capacity: scanning a whole object of 1.2 million records eight or sixteen at a time, loading records in bulk, and organizations competing for the same
  processors. Those results improved by a factor of two or more when the processors went from 6 to 14. That is the signature of a test container that was too small. A slow storage design would not have been indifferent to it.
- **Why this is not "re-running until it passes".** The hypothesis and the change were written down before the second run; no threshold, scenario or data size changed; the first run stays here with its failures. The honest limit:
  the first verdict is a FAIL, and the PASS exists only because the second container was bigger. Whether a production database server is at least that big is something this benchmark cannot tell. That is a decision for the owner.

## Conditions of the PASS (what it is and is not)

1. **Hardware.** The PASS is for PostgreSQL 18.6 with 14 processors and 7 GB of memory (database 5.4 GB) on a laptop SSD. A production database host smaller than that is not covered. Sizing rule: at least as many processors and as
   much memory as the second container, and memory for the data of the largest organizations.
2. **Two results close to their limit:** S9b (5108 against 5000) and S11 s04b (1.05 against 1.10). Sprint 16's regression run must treat a result within 10% of a limit as a warning.
3. **A flood is not covered.** With the large organization's sessions running flat out (reported, not judged) the small organizations' p95 was 1.74 times (run 2) and 22.6 times (run 1) their p95 alone. Ordinary busy use is
   covered; one organization opening a dozen non-stop sessions can hurt the others. This needs per-organization limits in the application (concurrent requests, statement timeout): a recommendation for Sprint 16 and the production plan.
4. **Not measured:** 10 million records in one object (2.2 million were measured; indexed reads grow slowly, whole-object scans grow with size, so the query engine must refuse or time-box filters on fields without an index in
   large objects); authorization predicates (Sprint 17); field security and the write pipeline (Sprint 15); the application itself and the network; a production-grade server.
5. **A measured fact that shapes the design:** with row level security forced, an index on the JSON field operator or on containment is unusable for a tenant user (diagnostic: 6.2 ms against 1.9 ms with and without security for an
   expression index; 11 ms against 389 ms for the containment index). Indexed fields therefore live in typed columns (ADR-0082, amendment 1), and the number of such columns per type is a hard limit of the design.

6. **Confirmation on managed hardware.** A local machine decides the design, not the production size. Before production, the same harness runs against a managed PostgreSQL 18 instance of the intended size, with the benchmark client in the same network zone, and the
   result is compared with run 2 in a new ADR. The harness needs small changes to target an outside database (no container, `pgbench` from a client host, the loader unchanged). A result worse than run 2 by more than 25% on any judged scenario blocks the go-live, not the design.

## Carried notes

| Item | p95 measured, one session (run 2 / run 1) | Verdict |
|------|-------------------------------------------|---------|
| The two statements every transaction starts with | 0.16 ms / 0.57 ms | No cache. |
| Lookup of the organization by host name (2000 organizations) | 0.17 ms / 0.29 ms | No cache. |
| Membership check and security version read | 2.4 ms / 1.2 ms | No cache. |
| Sign-in grant check | 19.8 ms / 23.4 ms | **Not valid: a defect of the benchmark.** The second statement of the scenario searched by token value without the condition of the partial index, so it scanned the whole grant table. The application reads by identifier. The statement is corrected in `benchmark/scenarios/c2-authentication.sql` and is re-measured in the next run. The statement as the application runs it measured 0.07 ms in the small trial. **No cache is recommended on this number.** |

- **Should the metadata publish event reach every instance? No.** Nothing measured says the first question after a publication is slow; a version read costs well under 1 ms.
- **Is a Redis tier justified anywhere? No.** No lookup that every request pays came out above the bar once the defective measurement is set aside.
- Also seen: a 12-minute pause between two scenarios of run 2 (09:18 to 09:31) that I could not explain (possibly the laptop sleeping). It fell between the authentication and membership scenarios; the membership p99 (10 ms) may carry it.

## Decision

The gate is **PASS on run 2 with the five conditions above**, run 1 being a recorded FAIL caused by the size of the test container. Part B proceeds. If the owner later rejects this reasoning, the
next step is not a different storage design but a third run on a machine the owner names as production-like. The typed-column hybrid (ADR-0082, amendment 1) is the design that passed; the fallbacks of ADR-0082 (partitioning
by organization, a relational table for a high-value object) stay unused until a measurement requires them.
