# ADR-0110: Large organizations and partitioning

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S16
- **Related:** [ADR-0083](0083-storage-benchmark-result-gate-g1.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0109](0109-the-benchmark-as-a-regression-suite.md)

## Question

Does the record table need partitioning (by organization or by object) now, because of a very large organization?

## Decision

**No.** Do not partition in Sprint 16, and do not design the record table around it yet.

## Reasons

- The tenant-first indexes and the partial indexes of the slots make a query for one object of one organization touch only that organization's range of
  the index. With 2.2 million records in one object the indexed scenarios that passed answered in 1 to 36 ms across the two runs; the failure (S3 number)
  is about the exact check on a wide range (ADR-0109), not about the size of the table.
- Partitioning by organization would need thousands of partitions (2,000 organizations in the benchmark); planning cost and the catalogue grow with them,
  and moving 3.4 million rows in place is a large operation. Partitioning by hash would not isolate a large organization from a small one.
- The noisy neighbour is bounded by the limits of ADR-0108 (0.72 times with limits against 2.39 without), which is the isolation that matters first.
- Table and index growth under an update-heavy load stayed between 0 and 2.6 percent after vacuum, and the leaf density of the busy indexes was 75 to 90
  percent.

## What was and was not measured

Measured: 3.4 million rows in the record table (2.2 million of one object, 1.2 million of another) in one organization, 2,000 organizations, 100 small ones
beside the large one, a ten-minute update-heavy phase. **Not measured: ten million records in one object** (`-Size large` was not run; the 10 GB budget
does not cover it). The answer above is for the size measured.

## Revisit when

An organization has more than about ten million records in one object, a single index no longer fits in memory on the intended instance, or vacuum cannot
keep up (autovacuum runs and bloat in the benchmark are the signal). The first step then is to run `-Size large`; the second is partitioning by a hash of
the organization with a fixed number of partitions, done as an expand-and-contract migration.
