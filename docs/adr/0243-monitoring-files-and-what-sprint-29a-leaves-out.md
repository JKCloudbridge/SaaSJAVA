# ADR-0243: Monitoring files, and what Sprint 29A leaves out (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0234](0234-the-attempt-log.md), [ADR-0236](0236-what-sprint-29-leaves-out.md), [ADR-0242](0242-where-the-poll-runs-the-ledger-and-what-a-restart-finds.md)

## Decision

1. **No new screen and no new endpoint.** The facts about files are a part of the health of a connection
   (`IntegrationHealthView.files`, absent for a connection that picks up no files): when the inbox was last looked at, what
   that found (the platform's sentence for the stable reason), when it is looked at next, and four counts: files waiting in
   the inbox, being applied, applied and set aside. The rules of the existing endpoint (ability `integrations.view`, the
   caller's own organization) hold for them. The automation health of an organization gains two counts (`filesWaiting`,
   `filesSetAside`). The Monitoring section of the connection page draws the block when the API sends it; the browser holds
   no reason, state or protocol (the scan test keeps it so).
2. **Counts, never names.** Nothing returned, logged, audited or put in a metric tag holds the name of a file, a path, a row, a
   value, an identifier of the other system, the address, a user name or a key. The audit has `integration.file.processed`
   (the ledger identifier, the result, the reason and four counts) and `integration.stopped` (the reason); the metrics are
   `platform.integration.files.polls{result,reason}`, `platform.integration.files{result,reason}`,
   `platform.integration.files.rows{result}` and `platform.integration.stopped{reason}`; a look is an observation
   (`platform.integration.files.poll`), so a trace has a span for it.
3. **What Sprint 29A leaves out, on purpose.**

   | Left out | Why |
   |---|---|
   | Deleting or tidying files on the server | the platform never deletes on the other side; the runbook says who does |
   | A key protected by a passphrase | one more secret to keep for no gain over a key in the store |
   | Writing a test file to prove a right to write | there is no delete to tidy it away; a missing right shows at the first send as `permission_denied` |
   | A layout of a file of the organization's own (delimiters, fixed widths, nesting) | a closed list of two formats; ADR-0240 |
   | Several entities in one inbox | one inbox, one entity; make another connection |
   | Per-row output of refused rows | rows are data of the organization; the count is shown and the file is kept for a person |
   | Picking up by a pattern of names | a plain name with the ending of the format, nothing cleverer |
   | The messaging adapter | built after the file flows were tested, as decided (the next ADR) |

   A need that fits one of these rows cannot be expressed; it is never half-supported.

## Consequences

An administrator can answer "is the inbox moving, and is anything waiting for me?" from the connection page without opening
the server.
