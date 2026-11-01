# ADR-0234: The integration log of attempts (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md)

## Decision

1. **What a person sees per delivery:** its attempts (`integration_attempt`), each with the time it began, how long it took,
   how it ended (success, accepted for later, to be tried again, permanent, interrupted), a **stable reason**, a **class of
   status** (`2xx` to `5xx` or `none`) and the status number of an answer that was read.
2. **What it never holds:** a payload, a value, a header, an address, an external identifier, a credential, an exception
   message. The columns are identifiers, small enumerations and numbers, and a reason is checked by the database to be
   lower-case letters and underscores, so the table cannot carry text by construction. `ReliabilityFlowsLogsAreCleanIT`
   plants canaries in every path and searches the logs, the audit trail, the answers, the metrics and the table.
3. **An attempt is written once and never changed** (a database guard); a repeat of the same attempt is ignored.
4. **Retention** follows the deliveries (`platform.integration.flow.delivery-retention`, 30 days): the attempts go with the
   delivery they belong to, removed in bounded batches as the deliveries are.
5. **Audit** records only what a person does or what ends a story: dead-lettering, replay (one or bulk), forcing the
   circuit, changing the limits. By identifiers and counts, never a value.
6. **Metrics** under `platform.integration.*`: `attempts{direction,result,reason}`, `circuit{state}`, `gate{result}`,
   `queue.depth`, `queue.age`, `throttled`, `dead`, `superseded`, `replayed{mode}`; the health source of the automation view
   (`IntegrationHealth`) gives the same counts to the organization and, without a name, to the platform.
