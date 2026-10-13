# Storage benchmark (gate G1)

The permanent benchmark of the record store. Specification and thresholds: [ADR-0082](../docs/adr/0082-storage-benchmark-specification-gate-g1.md);
the result of each judged run is recorded in ADR-0083. It is **not** part of the Maven build and **not** run by CI: it needs a container
runtime, free disk space and about an hour. Since Sprint 16 it is a regression suite: the query scenarios and the statements of an update and an insert are generated from the production code (the real analyzer, planner and statement builder, `BenchmarkStatements`), a result within 10 percent of a limit is reported as a warning, and it also measures the cost of the typed copies and what the limits per organization do to the noisy neighbour (ADR-0109). It needs the JDK and Maven as well as Docker. Disk: about 10 GB at its peak. Since Sprint 17 it also measures what record-level security costs on the first page of a Private object (an owner, a manager over 50 people, a member with 100 shares) and the search (a rare word, a rare and a common word, a word on 2 percent of the records); the loader gives the large organization 1,000 owners, 100 shares and two search words for every record. The search and the security statements are written by the production code too (`BenchmarkStatements`).

## Run it

Needs Docker and Windows PowerShell. Nothing else is installed on the machine: the load generator (`pgbench`) ships inside the PostgreSQL
container, and the data is generated inside the database.

```powershell
cd platform
.\benchmark\run-benchmark.ps1 -Size smoke     # a few minutes, tiny data: proves the harness works; NOT a measurement
.\benchmark\run-benchmark.ps1                 # the full run (2 million records in the large object); about an hour
.\benchmark\run-benchmark.ps1 -CleanupOnly    # remove the benchmark container and its data
```

Useful switches: `-BigRecords`, `-CaseRecords`, `-SmallTenants`, `-SmallRecords`, `-Users`, `-Seconds`, `-WarmupSeconds`, `-UpdateMinutes`,
`-Clients`, `-Memory` (default 6g), `-Cpus` (default 6), `-SetupOnly` (build and load, then keep the container for your own queries), `-KeepContainer`,
`-Size large` (10 million records in the large object).

The run ends by removing the container and its volume, so the disk comes back. The exit code is 0 when every threshold was met, 2 otherwise.

## What it writes

`benchmark\results\<time>-<size>\` (ignored by git):

| File | Content |
|------|---------|
| `summary.md` | The verdict per threshold, every scenario's percentiles, the carried-note numbers, sizes, environment, what was not measured |
| `raw.csv` | One line per scenario run: transactions, per second, p50, p95, p99, max, failures |
| `environment.txt` | The machine, the container, the PostgreSQL settings |
| `raw\*.txt` | The unedited `pgbench` report of every run, and `diagnostic-plans.txt` (query plans with and without row level security) |

## Layout

| Path | Content |
|------|---------|
| `run-benchmark.ps1` | Orchestrator: container, setup, phases, judgement, files |
| `container\setup.sh`, `container\run.sh` | Run inside the container: build and load; run one scenario and compute percentiles |
| `sql\01-proof-schema.sql` | Helpers of the proof (association table, generator functions, bypass role); applied after the repository's real migrations and manual migration M001. The record table is the real one |
| `sql\10-load.sql` | The data generator |
| `scenarios\*.sql` | One `pgbench` script per scenario (`s` = judged, `c` = carried notes, `x` = diagnostic) |
| `scenarios\generated\*.sql` | Written by every run from the production planner (`BenchmarkStatements`); they replace the hand-written files of the same name |

## Reading a result

Latencies are of one whole transaction (the two statements that set the application name and the tenant, the query, begin and commit) measured at the
database through a local socket: **no network time and no application time**. A pass is a pass only for the machine and the data size written in the
result. Do not compare a smoke run with anything.
