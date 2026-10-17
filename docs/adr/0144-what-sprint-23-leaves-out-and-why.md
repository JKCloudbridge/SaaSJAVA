# ADR-0144: What Sprint 23 leaves out, and why

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md), [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **Start approval** (action) | it needs the approval engine and record locking | Sprint 24 |
| **Notify** (in-app notification action) | it needs the notification module v1 with templates and preferences | Sprint 25 |
| **Call integration** (action) | it needs the connector framework | Milestone M7 (Sprint 26 on) |
| **Before-save (synchronous) automation** | it needs a bound on time, steps and recursion inside the write transaction | Sprint 25 |
| **Mail text written by an administrator** | templates and preferences | Sprint 25 (until then `SEND_EMAIL` uses one closed platform template) |
| **An expression as a value** (`WORKFLOW_VALUE`) | it widens the sandbox and the cost of a check | its own decision, after Sprint 25 |
| **Server-side tenant code** | decision D6 is deferred; the `backend` reference stays inert and read by nothing | a design spike |
| **Custom buttons** | parked | its own sprint |
| **`continueOnError` per action, a per-action timeout, a waiting ("wait until") step, conditional branching** | the model stays a straight list with an optional condition; each of these changes what an execution is | a later decision; branching would be a new definition kind, not a change of this one |
| **A fair share of the queue between organizations** | the rate cap (ADR-0140) bounds one object of one organization; fairness belongs to the hardening of Sprint 25 | Sprint 25 |
| **Delete and restore as actions** | no requirement names them; destroying data automatically needs its own review | on request |

A definition that names an action type of the first three rows is refused at save with the list of types this release carries out.
