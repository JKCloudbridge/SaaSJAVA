# ADR-0118: View all, modify all and the one evaluation

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0049](0049-object-and-field-permissions.md) (the flags existed and did nothing), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0116](0116-defaults-hierarchy-and-sharing-rules.md), [ADR-0117](0117-explicit-sharing-and-who-may-share.md)

## Context

Since Sprint 8 an object permission can carry `view-all` and `modify-all`. Until now they did nothing because no record was hidden from anyone. The exit criterion of this sprint is that **a list and a single-record question never disagree**.

## Decision

1. **View-all and modify-all bypass the record rules, for that object only.** View-all reads every record; modify-all reads, changes and deletes every record. The bypass is applied inside `security` when the description is made: it yields `Everything` for the actions it covers. It does not widen the object permission: a member who may not read the object reaches no record, whatever else they hold.
2. **The description for a member and an object is the union, per action** (`RecordAccessResolver`, a pure function of facts):
   - READ: everything when the member has view-all or the default is not `PRIVATE`; otherwise the member's own records, the records owned below them in the hierarchy (when the object's switch is on), the records the member's rules give (at any level), and the records shared with the member or with a group they are in.
   - EDIT: everything when the default is `PUBLIC_READ_WRITE`; otherwise own records, the records a rule gives at `READ_WRITE` and the records shared at `READ_WRITE`. The hierarchy gives no edit.
   - DELETE: everything when the default is `PUBLIC_READ_WRITE`; otherwise the member's own records only.
   The three results are contained in each other by construction (delete in edit in read): a record that cannot be read can never be edited or deleted.
3. **One evaluator.** The statement is the only place a description is evaluated (ADR-0115 point 2). The single-record answer (`Decisions.can(..., RecordRef)`), the list, the query, the lookup and the check before a change all run the same condition from the same builder. There is no second interpreter in the product that could be right when the statement is wrong or the reverse.
4. **The statement is held to an oracle in tests.** `RecordAccessResolverTest` and `RecordAccessPropertyTest` (jqwik) generate organizations (roles, groups, rules, shares, flags) and compare what the description admits with a small separate interpreter of explicit facts that exists in test code only. `DecisionMatrixTest` is the table of the rules above, one row for each sentence of this ADR. `RecordLevelSecurityIT` proves through the API that for every member and every record the list, the query, the read and the decision API agree, and that a change of the default, the hierarchy, a rule, a group, a role or a share is seen by the next question. `RecordAccessCacheIT` proves the same on three instances, with and without the cache.
5. **Failing closed.** A description that cannot be completed (the owner bound of ADR-0115 point 3, a rule whose source is gone, an object the metadata does not know) admits less, never more.
6. **A platform administrator or support person has no record access** through any of this: platform roles give no organization authority (Sprint 6), and a support person who opens an organization's context still goes through `SupportAccess` and the same evaluation as a member would.

## Consequences

- View-all and modify-all are now real; an organization that granted them in Sprint 8 sees no change under the default.
- Any later change to the model (Sprint 17A, a deny rule if ever wanted) is a change to one tree and one interpreter, with a property test that fails first.
