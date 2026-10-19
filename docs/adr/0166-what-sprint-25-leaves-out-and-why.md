# ADR-0166: What Sprint 25 leaves out, and why

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md), [ADR-0153](0153-what-sprint-24-leaves-out-and-why.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **A digest, push notifications, SMS, a chat channel, a mail from an organization's own address** | one more channel is one more place for a value to leak and one more thing to secure; in-app and mail cover the requirement | on request |
| **A field of the record, or its title, as a placeholder** | the text of a notice outlives the request and a mail leaves the platform's control (ADR-0155) | never; a person opens the record |
| **A link, markup or an address in administrator text** | a phishing vehicle; the platform adds its own link | never |
| **A named person or a group as the recipient of `NOTIFY`** | an identifier of a person inside a definition; the owner and a user field already say "whoever the record names" | a new setting with a bounded expansion, on request |
| **An expression as a value** (`WORKFLOW_VALUE`) | widens the sandbox and the cost of a check; no requirement needs it now that a copy of a field exists | its own decision and security review |
| **Before-save automation that writes another record, sends, calls out or loops** | it runs inside the transaction of the write: the only things it may do are those that cannot fail the transaction in an unbounded way (ADR-0160) | never inside the save; use a workflow after commit |
| **A server push for the bell** | a socket to secure and keep alive for little gain; the bell asks on focus | on request |
| **Per-record mute, quiet hours, a per-kind switch** | categories carry the meaning (ADR-0156) | on request |
| **Reminders without a limit, or a second escalation level** | a reminder that never stops is a nuisance; three, then the mark `overdue`, then a person acts | on request |
| **Weighted fairness by plan** | no stated rule of whose work matters more (ADR-0164) | on request |
| **Read-only mode for an ended trial, and deletion of an unpaid organization** | a third organization state in every write path; destroying a client's data automatically needs its own review (ADR-0162) | never automatically |
| **Taking payment** | no processor | a later billing sprint |
| **Alert rules as code** | they depend on the operator's monitoring stack (ADR-0163) | a runbook now |
| **Retention of finished approval instances** | approval history is a business record; a policy needs a requirement (ADR-0153) | on request |
| **The full D3 measurements on the intended instance** | the smoke sizes run here; the full sizes belong to the rehearsal on the target hardware (ADR-0165) | the pre-production rehearsal |
| **A kill of the whole database during the chaos test** | the outbox, the lease and the unique keys protect against a lost *worker*; a lost database is the platform's recovery plan | the production transition plan |

A definition that needs one of the first rows cannot be expressed; it is never half-supported.
