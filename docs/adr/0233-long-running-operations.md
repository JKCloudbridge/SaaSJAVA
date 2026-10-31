# ADR-0233: Long-running operations on the other side (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29

## Decision

1. **A send can be answered "accepted, ask later".** The adapter says so (`Connector.remoteJob`: for the REST connector a
   `202` whose body names a `statusPath`); the framework checks the path with the rules of every path. The delivery then
   becomes `WAITING_REMOTE` with the path (`remote_ref`, kept to be asked about, never shown, logged or audited), a
   **deadline** (one hour) and a due time.
2. **Polling is bounded and jittered:** the question is asked at least one interval apart (15 s plus up to 25 % at
   random), the deadline bounds the number of questions, and a question goes through the same gate as a send (circuit,
   concurrency, rate), so a service that is down is not polled to death.
3. **A question that fails says nothing about the job** (a timeout, a 5xx, a 429, a 408): the delivery asks again later. An
   answer the adapter reads as *failed* ends the delivery as a dead letter (`remote_failed`); one that is *done* applies the
   link exactly as an immediate answer would and ends it `DONE`; the deadline passing ends it as `remote_expired`.
4. **A job is never started twice:** the move out of `WAITING_REMOTE` back to sending is refused by the database guard, so
   a restart only resumes the questions (the claim picks it up by its lease and due time).
5. **The last hash of what was sent is not kept** for a job; the next change is simply sent rather than skipped as
   unchanged.

## Consequences

`LongRunningOperationIT` covers accepted, still running, done, failed, expired and the restart.
