# ADR-0153: What Sprint 24 leaves out, and why

- **Status:** Accepted (the "no step actions" row is reopened, in the narrow form of the hook, by [ADR-0187](0187-approval-hooks-the-lock-owners-write-and-the-step-that-is-skipped.md))
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md), [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **In-app notification** and administrator-written text | needs the notification module v1 with templates and preferences | Sprint 25 (until then the inbox and one closed mail template) |
| **Escalation by time and reminders** | needs a timer per instance; nothing in this sprint waits on a clock | Sprint 25 hardening |
| **Request changes / send back** | changes what a step and an instance are (a loop back) | a later decision |
| **Parallel branches, conditional routing between steps** | the model stays a straight list of steps | a new definition kind, not a change of this one |
| **Quorum and percentage completion** | `ANY` and `ALL` cover the requirement | on request |
| **A permission-based approver** (anybody holding an ability) | it would put an access rule inside an approval; the group kind is explicit | on request |
| **Approval, or any decision, by replying to or following a link in an e-mail** | a link that decides is an attack surface; a mail only opens the inbox | never |
| **A bulk approve** | a bulk click on a financial approval is a mistake waiting to happen | on request |
| **Integration calls as outcome actions** | the connector framework | Milestone M7 |
| **Before-save automation** | needs a bound inside the write transaction | Sprint 25 |
| **A platform administrator who can unlock** | the platform has no business authority inside an organization; a member with the override ability cancels | never |
| **Editing in place of a pending process** | an instance is pinned to its version | by design |
| **A status field added to objects by the platform** | the lifecycle comes from metadata; an outcome update may set a field the organization chose | by design |
| **Retention of finished instances** | approval history is a business record; a policy needs a requirement | on request |
| **Server-side tenant code** | decision D6 is deferred; nothing here runs code of an organization | a design spike |

A definition that needs one of these is refused at save or simply cannot be expressed; it is never half-supported.
