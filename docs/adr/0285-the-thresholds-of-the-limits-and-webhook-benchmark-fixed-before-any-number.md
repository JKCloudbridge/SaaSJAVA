# ADR-0285: The thresholds of the limits and webhook benchmark, fixed before any number is seen (S32)

- **Status:** Accepted (thresholds; the result is recorded under "Result" after the first judged run)
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0165](0165-chaos-and-load-tests-and-the-d3-measurements.md), [ADR-0280](0280-rate-limits-and-quotas-the-algorithm-keys-cost-and-paths.md), [ADR-0281](0281-the-counters-redis-and-failing-open.md), [ADR-0279](0279-retries-the-breaker-fair-claim-and-switching-off.md)

## Context

The exit of Sprint 32 asks for "limits enforced under load test", and a webhook sender has a throughput and a loss-free claim of its
own. As with the record store (ADR-0082) and the workflow engine (ADR-0165), the thresholds are written **before any number is
seen and are never moved after one is**; a failure is a finding to fix or to record as a decision, never a threshold to adjust.

## Decision: the thresholds

The harness is `benchmark/limits/` (a single-file Java load generator and a PowerShell wrapper). It is outside the Maven build and
outside CI; `-Size smoke` proves it runs and is **not** a measurement. It talks to a running application (three instances for
L1 and W3) through its public and internal interfaces, so it measures what a customer sees, including the network of this machine.

| # | Threshold | How it is judged |
|---|-----------|------------------|
| **L1** | Over one window, the requests **accepted** for one application across **three instances sharing Redis** never exceed its limit by more than **2 percent** (the design gives exactly the limit) | A burst of three times the limit, spread over the instances round-robin; accepted = answers 200; the limit is read from `RateLimit-Policy` |
| **L2** | An organization that is **ten times over its limit** does not slow **another organization's p95** beyond **1.5 times** that organization's own baseline p95 (with a floor of 20 ms of difference) | The second organization alone, then again while the first is hammered |
| **L3** | With **Redis stopped mid-run** the API still answers: **no 5xx at all**, the p95 of an answer during the outage is at most **2,000 ms**, and what was accepted during it is at most the limit times the number of instances | The harness pauses the Redis container when told to and counts the answers |
| **L4** | The p95 of a counted request at **50 concurrent callers** is at most **250 ms** | The cheapest counted operation (the list of objects) |
| **W1** | Webhook throughput: at least **100 deliveries a second** are made and accepted over 60 seconds | Records created through the public door at the rate it can make them; a local receiver that answers 200; deliveries counted by the receiver |
| **W2** | The delay from the commit of a change to the **first attempt** is at most **3 s at the p95** at half of W1's rate | Time of the receiver's arrival minus the time of the creating request's answer |
| **W3** | **Nothing is lost when an instance is killed mid-run:** every event created is received at least once, and the duplicates are at most **1 percent** of the events (at-least-once is the documented promise) | Distinct delivery identifiers received equal the events created; the wrapper stops one instance at half the run |
| **W4** | One organization with **10,000 deliveries queued** does not delay another organization's first attempt beyond **3 s at the p95** | A quiet organization's events during the first organization's drain |

## What is not claimed

A pass is a pass only for the machine, the number of instances, the settings and the data size written with it. Latencies include
the local network and the application's request time, but not the receiver's own work. The webhook half measures the platform's
delivery, not a customer's software.

## Result

Not yet judged: the harness was written in Sprint 32 and parsed; its first run is its smoke run. The judged result, with the
machine and the settings, goes here.

## Consequences

- A change that makes the counters slower, the fair claim less fair or the delivery lossy fails a threshold written in advance.
