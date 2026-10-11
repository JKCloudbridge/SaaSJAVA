# ADR-0082: Storage benchmark specification and pass/fail thresholds (gate G1)

- **Status:** Draft (written and committed before any measurement is run; the thresholds below are not changed after a number has been seen)
- **Date:** 2026-10-11
- **Sprint:** S14
- **Related:** [ADR-0002](0002-hybrid-data-store.md), [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0010](0010-base-schema-conventions.md), [ADR-0015](0015-row-level-security-implementation.md); the result is recorded in ADR-0083

## Context

Every record of every organization will live in one table with a JSON column (the "hybrid store" of ADR-0002), protected by row level security. That is the cheapest design to build and
the one with the most unknowns at size. If it turns out too slow, everything built on it (the write pipeline, the query engine, sharing, the screens) would have to change. The delivery plan
therefore makes the decision a hard gate (**G1**): no work from Sprint 15 on starts until a benchmark has passed. This ADR is the specification of that benchmark and the rule that judges it. It
is written first so that nobody, including its author, can adjust the bar after seeing the result.

## What is measured, and what proves the real thing

The proof uses the **real schema of the repository** (migrations V001 to V035, the manual role migration M001) plus one throwaway table `object_record` that has exactly the shape of the table of
Sprint 14 (V036): tenant, object API name, time-ordered identifier, JSON payload, owner, the base columns, soft delete, version; the base-columns trigger, the tenant guard, row level security
**enabled and forced**, tenant-first indexes. All statements run as the application role (`platform_app`, no right to bypass row level security), inside a transaction that first sets the
application name and the tenant, exactly as the application does (two extra statements per transaction). Only the "row level security off" comparison uses a second role that bypasses it.

The database is a container of the production major version (PostgreSQL 18) with the memory, processor and settings written into the result file. A laptop is not production hardware: **a PASS
is a PASS only for what was measured, on the machine and the data size recorded in the result.**

## Data

| Part | Size at the default run | Why |
|------|--------------------------|-----|
| One large organization, one object (`Employee__c`) | 2,000,000 records (parameter) | The large-tenant case. 10 million in one object is **not** measured in the first run (disk and time on the developer machine); thresholds for 10 million are not judged, and the result says what the 2 million numbers imply for it |
| The same organization, a second object (`Case__c`) | 1,000,000 records | The plan's thresholds are stated "per 1 million records per object" |
| Many small organizations | 100 organizations, two objects each, 2,000 records per object | The noisy neighbour |
| Association rows for the many-to-many scenario | 2,000,000 (two per `Case__c`) | A junction between `Case__c` and `Employee__c` |
| Users, memberships, sign-in grants, organizations | enough rows for the carried notes (see below) | |

A record has 14 fields: 4 text (one low-cardinality, one high-cardinality, one long free text of about 200 characters), a number, a date (ISO text), a boolean, a picklist, a lookup (an identifier), and
fields that are never indexed. Payload size is about 700 bytes. The data is generated inside the database (`generate_series`), deterministic, generic. Indexed fields of the proof (the metadata-driven
index policy of Sprint 16 will create such indexes, one per field flagged "indexed"): a text field with 50 values, a text field with unique values, a number, a date and a lookup. **As amended
(amendment 1 below), each is copied into a plain typed column of the record (a "slot": `s_text_1`, `s_num_1`, `s_date_1`, `s_ref_1`, and so on) and indexed as `(tenant, object, slot, id)`, partial
(`where deleted_at is null`)**; the complete record stays in the JSON payload. Plus `(tenant, object, id)` for the plain list and one index for the recycle bin. The table has a fill factor of 90.

## Scenarios and thresholds

"p95" is the 95th percentile of the latency of one whole transaction (including the two statements that set the application name and the tenant, and the commit), measured with 8 concurrent clients unless
stated, after a warm-up, for 30 seconds per scenario (parameters), over a uniformly random choice of record. Latency is the time at the database server's machine door (a local socket inside the container);
network time is **not** included and is stated separately (see "round trips").

| # | Scenario | Threshold (plan values unless marked *added*) |
|---|----------|----------------------|
| S1 | Read one record by id (`Employee__c`) | p95 ≤ **20 ms** |
| S2 | Update one record by id with its version (read, then guarded update) | p95 ≤ **40 ms** *(added: twice the read, there is no plan value)* |
| S3 | Filter on an indexed text, number, date and lookup field, sorted, one page of 50, keyset; on `Employee__c` (2 million) | p95 ≤ **150 ms** each (the plan value for 1 million; at 2 million it is stricter) |
| S4 | Filter on a **non-indexed** field on `Case__c` (1 million): (a) a value that matches 10 percent, (b) a value that matches fewer than 20 records, which forces a scan of the whole object | (b) p95 ≤ **1 s** at 8 clients; at 16 clients (a pool of 16) every transaction finishes inside a 5 s statement timeout, none fails, and a read of S1 run beside it keeps p95 ≤ **100 ms** *(added: "degrades gracefully, never exhausts the pool", made measurable)* |
| S5 | Sort the whole object by an indexed field with keyset paging, one page of 50 | p95 ≤ **150 ms** |
| S6 | Multi-field filters: two indexed fields with AND, and with OR; a payload containment filter with a GIN index (compared, not required) | AND and OR p95 ≤ **150 ms** |
| S7 | Noisy neighbour: a mix of S1 and S3 against the small organizations, alone and then while the large organization runs S3 at 400 transactions per second and S4(b) at 1 per second (8 and 4 sessions, rate-limited; amendment 2). The same with the large organization's sessions running flat out is reported, not judged | the small organizations' p95 with the load ≤ **1.25 ×** (25 percent worse than) their p95 alone |
| S8 | Concurrent updates of the same few records (16 clients, 10 records) with the version check | no lost update: the final version and counter equal the number of successful updates; exactly one of two simultaneous updates of one version wins *(correctness, must hold)* |
| S9 | Bulk: insert and upsert in batches of 1,000 records by one worker | ≥ **5,000 records per second** per worker, insert and upsert each *(measured inside the database; the client's cost is added in the application and is not part of this number)* |
| S10 | Many-to-many join: the records on the other side of one record, both directions, through the association table, one page of 50 | p95 ≤ **150 ms** *(added: the plan gives none; same as a filter)* |
| S11 | Row level security cost: S1, S3 (text) and S4(b) run as the application role and then as the role that bypasses it, three alternating pairs | the **median of the three p95 values** with security on ≤ **1.10 ×** the median with it off (the plan's 10 percent; the median-of-three is added because milliseconds are noisy) |
| S12 | Storage, bloat and vacuum: size of the table and each index after the load; then 10 minutes of updates of random records of the large object at 8 clients (half of the updates change an indexed field) | table plus index bloat ≤ **30 percent** after autovacuum has run; the p95 of the last third of the run ≤ **1.25 ×** that of the first third *(added)*; the bytes per record, the share of heap-only updates and the autovacuum counts are reported |

**Carried notes (judged by the rule below, not pass/fail):** the cost of (a) the two statements every transaction starts with, (b) the lookup of an organization by host name, (c) the check of a sign-in
grant (the grant joined with the user's status and security version), (d) the membership check of an organization host. Each is measured with the real statement of the application on tables with realistic
numbers of rows. **Rule decided now:** a cache is recommended for a lookup only if its p95 is at least 1 ms **and** it is at least a quarter of the database time that every request pays before it does any
work; the recommendation always states the bound of staleness it would add. If all four together are below 5 ms p95, none is cached. Network time is a stated assumption of 0.5 ms per round trip (a
datacentre), because the benchmark runs on one machine. The same part answers, from what was measured, whether the publish event of the metadata should reach every instance (only if the first question after a
publication is measurably slow) and whether a Redis tier is justified anywhere (only if a lookup that is paid by every request cannot meet its bound without it).

## Rule of judgement

1. The gate **passes** only if **every** threshold marked pass/fail above is met in the result file produced by an unmodified harness on the data sizes above (or larger), and S8 holds.
2. A threshold is judged on the measured number exactly as written. A number is not rounded in favour of the pass; a scenario that was not run is **not measured**, and a gate with a threshold that was not measured does not pass for that threshold (the result says so).
3. Thresholds are not moved after a number is seen. If a threshold turns out to have been badly chosen, that is written in ADR-0083 and the gate is judged as it stood; the next version of the benchmark (Sprint 16) may change it, with a reason.
4. **FAIL** means: stop; adopt the fallback behind `StorageStrategy` (below); re-run the gate; record it.

## Predictions, written down before the run

So that the result teaches something even when it passes:

- **Weakest:** (1) S4(b) at 16 clients (the whole object scanned by many sessions at once). (2) S7 on shared hardware. (3) S12 and S9: every write now also writes the typed copies and their indexes, and an update that
  changes an indexed field cannot be heap-only; an update that changes only the payload can (that is what the fill factor is for). The share of heap-only updates is reported.
- **Strong:** S1, S3 on text and lookup, S5 (index lookups of a few pages).
- **S11** is expected to be within noise at these row counts: the policy is one comparison with a function that PostgreSQL evaluates once per statement.

## Fallback, in the order it is tried

1. **Typed copies of the hot fields:** generated columns or a projection table for the fields flagged "indexed", behind `StorageStrategy`; same table, same API. Cheapest; addresses S3/S4/S5/S9/S12.
2. **Partitioning by organization hash** (and detaching the largest organizations), when S7 or the sizes fail.
3. **A relational table for a high-value object** (for example Account or Case) behind the same interface.
No other design is started before the gate is re-run.

## What this benchmark does not measure

10 million records in one object (extrapolated, not measured, unless the run is made with the larger size); a production-grade server (the result file states the machine); the cost of authorization
predicates of Sprint 17 (the benchmark's queries filter by organization and object only: Sprint 16 promotes the harness to run through the real query engine, and Sprint 17 adds the predicates); field
security; formula and validation work on write (Sprint 15); the application's own time, the pool and the network; full text search; reports and aggregations; backup, replication and failover.

## Consequences

- The benchmark lives in `benchmark/` (outside the Maven build and CI), is repeatable (`run-benchmark.ps1`), writes a readable summary and a raw file, and becomes the regression suite of Sprint 16.
- Sprint 14 Part B starts only after ADR-0083 records PASS.

## Amendment 1 (2026-10-11, written before any measurement run; no threshold changed)

**What was found.** The proof was built as first written (JSON payload, indexes on the expressions `payload ->> 'Field'`). A smoke run on 20,000 records (not a measurement; it only proves the harness) showed filters on an
indexed field about ten times slower under row level security than without it, and the query plan showed why: with row level security, PostgreSQL uses an index for a filter condition only if every function in the
condition is *leakproof* (cannot reveal, for example in an error message, the value it was given), so that the policy is always evaluated first. The JSON field operator (`->>`), the containment operator (`@>`) and every
comparison of decimal numbers (`numeric`) are **not** leakproof; comparisons of text, whole numbers, floating point numbers, dates, timestamps, booleans and identifiers are. (Checked in the running PostgreSQL 18:
column `proleakproof` of `pg_proc`.) Marking a function leakproof needs the superuser, which a deployment cannot be assumed to give. So a design in which every filter goes through the JSON operators cannot use an
index for any tenant user, whatever the size of the data: every filter would read the whole object. That is not a tuning problem; it is the case the plan names as the first fallback ("typed projection tables for the
hot fields").

**Decision.** The proof is the hybrid the plan describes: the complete record in the JSON payload, and the fields flagged "indexed" also copied into plain typed columns that carry the indexes. The thresholds, the
scenarios and the rule of judgement above are unchanged. The pure-JSON design is **kept as a measured diagnostic** (not judged): the harness builds the expression index and the containment index, runs the same
filter under row level security and without it, and saves both query plans, so the result file shows the reason with numbers at the real size and nobody has to take this paragraph on trust.

**Consequences for the design that follow if the gate passes** (recorded now so that Sprints 15 and 16 do not rediscover them): a write copies the value of an indexed field into its slot in the same statement (Sprint 15's
pipeline); the slots have fixed types, so an indexed decimal field is indexed as a floating point number and compared exactly against the payload afterwards (Sprint 16); making a field indexed assigns it a free slot of its
type and fills the slot for existing records, which is a background job (Sprint 16); the number of indexed fields per object is bounded by the number of slots of that type (Sprint 16 sets the limits; the proof has
4 text, 2 number, 2 date, 2 reference and 1 boolean slot).

## Amendment 2 (2026-10-11, before any measurement run; the threshold is not changed)

**What was found.** In the smoke run (tiny data, not a measurement) the noisy-neighbour scenario as first written, in which twelve sessions of the large organization run flat out without a pause on a
six-processor container, made the small organizations' p95 about thirty times worse. Twelve sessions that never rest on six processors saturate the machine: the scenario measured saturation, which hurts every user of
any shared server, and not whether one organization's ordinary heavy use hurts another.

**Decision.** The judged variant fixes the large organization's rate (400 filters a second, one whole-object scan a second), which is a busy organization and not a stampede; the 25 percent threshold is the plan's and is
unchanged. The flat-out variant is kept and its ratio is **reported** in the result, because it is the honest answer to "what happens to the small organizations if one organization floods the server". I saw the smoke number
before making this change; that is why it is written down here with the reason, and why the flat-out number is not hidden. If the judged variant passes and the flat-out variant is bad, ADR-0083 says so and recommends the
resource-governance work (per-organization concurrency and statement limits in the application, Sprint 16 and later) as a consequence; it does not turn the gate into a pass for something that was not measured.

The carried-note costs (round trips, host lookup, sign-in grant, membership) are measured with one session, so that they show the cost of one request and not queueing on a saturated machine.
