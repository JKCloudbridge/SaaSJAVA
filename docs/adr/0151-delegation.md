# ADR-0151: Delegation

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0146](0146-approver-resolution.md), [ADR-0147](0147-completion-decisions-recall-reassign-and-concurrency.md)

## Decision

1. **`ApprovalDelegation` is its own tenant-scoped record, never an edit of a process** (note 6, section 18). A person (the delegator) names a
   delegate and a range of days, and optionally one process. While it is in force, tasks that would be assigned to the delegator are
   **also** assigned to the delegate.
2. **Added, not instead of.** The delegator keeps the task, so a delegator who returns early is not locked out. The history says **who
   acted for whom** (`on_behalf_of`).
3. **Decided when the step becomes active.** A delegation created later does not move tasks already assigned (they are reassigned by
   hand, ADR-0147). A delegation that ends does not take a task away either: the assignment stands until the step ends.
4. **No chains.** The delegate's own delegation is not followed. No delegation to oneself. A delegate must be an active member with the
   licence their profile needs, both when the delegation is made and when a step uses it.
5. **The range** is a first and a last day, **both inclusive, as local dates in the time zone the delegation carries** (no organization
   time zone exists; a delegation states its own, as a scheduled workflow does, default `UTC`). At most 366 days; at most 20 current or future
   delegations per person (`platform.approval.limits.*`). A range never starts before today in its zone.
6. **Who may make one for whom**: any member for themselves; a member with `approvals.manage` for anybody. Revoking is the same two. All
   audited (`approval.delegation.*`: the persons by identifier, the dates, the process name; never a reason, there is none).
7. **Nothing runs on a clock.** A delegation is read when a step becomes active; no job starts or ends it.
