# Storage benchmark (gate G1)

The permanent benchmark of the record store. Specification and thresholds: [ADR-0082](../docs/adr/0082-storage-benchmark-specification-gate-g1.md);
the result of each judged run is recorded in ADR-0083. It is **not** part of the Maven build and **not** run by CI: it needs a container
runtime, free disk space and about an hour. Sprint 16 promotes it to a regression suite that runs through the real query engine.

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
| `sql\01-proof-schema.sql` | The proof table and helpers; applied after the repository's real migrations V001 to V035 and manual migration M001 |
| `sql\02-indexes.sql` | The tenant-first indexes of the proof |
| `sql\10-load.sql` | The data generator |
| `scenarios\*.sql` | One `pgbench` script per scenario (`s` = judged, `c` = carried notes, `x` = diagnostic) |

## Reading a result

Latencies are of one whole transaction (the two statements that set the application name and the tenant, the query, begin and commit) measured at the
database through a local socket: **no network time and no application time**. A pass is a pass only for the machine and the data size written in the
result. Do not compare a smoke run with anything.
