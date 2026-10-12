# ADR-0068: The ability to publish, and changes made at once

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0058](0058-object-and-field-definitions.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md)

## Decision

1. **A new ability `metadata.publish`** (user's answer to question 4, recommendation accepted), held automatically by the administrator profile (full access gives every ability, so no data
   migration). It is the third of three: `metadata.view` (read), `metadata.manage` (draft), `metadata.publish` (put live and roll back).
2. **Who may do what:**
   | Action | Needs |
   |--------|-------|
   | Read objects, fields, relationships, record types, change sets, history | `metadata.view` |
   | Start, add to, take out of, discard a change set | `metadata.manage` |
   | Check or preview a change set, check a rollback | `metadata.view` and (`metadata.manage` or `metadata.publish`) |
   | Publish a change set, roll back the latest release | `metadata.publish` and `metadata.view` |
   | A change made at once (the live endpoints of Sprint 10 and the record types) | `metadata.manage` **and** `metadata.publish` |
3. **A change made at once is a publication of one change**, so it needs the right to publish. A member who held only `metadata.manage` before this sprint now drafts in a change set
   and somebody with `metadata.publish` publishes; the existing live test was changed to say so. Keeping the live endpoints (answer to the second question) keeps every screen and API of
   Sprint 10 working for administrators.
4. **Every endpoint answers on an organization host only**, derives the organization from the host, and a change set of another organization does not exist for the caller (404).
   A platform administrator is refused (404), as for every organization endpoint.
5. A refusal for lack of an ability is audited as before (`membership.action.refused`); a refused publication is audited as `metadata.publish.refused`.