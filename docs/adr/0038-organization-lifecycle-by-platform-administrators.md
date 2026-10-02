# ADR-0038: Organization lifecycle by platform administrators: suspend, reinstate, deactivate

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0014](0014-tenancy-model-and-tenant-context.md), [ADR-0017](0017-tenant-resolution-by-hostname.md),
  [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md), [ADR-0036](0036-administrative-sessions.md),
  [ADR-0037](0037-provisioning-an-organization-for-a-client.md)

## Context

Sprint 2 built the tenant lifecycle (`PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, with events in the outbox and a database
trigger) and its operations, with no authorization and no entry point. The console now offers them to platform administrators.

## Decision

1. **Who and what.** Platform administrators only: `suspend` (ACTIVE to SUSPENDED), `reinstate` (SUSPENDED to ACTIVE),
   `deactivate` (any state to DEACTIVATED, final). Each needs a **required reason** of 1 to 200 characters (validated; kept in the
   audit trail as a bounded attribute, never mailed or shown to the organization) and is audited with the platform person, the
   organization and the reason, in the **same transaction** as the change (a record and its change stand or fall together). The
   tenant module still writes the lifecycle events to the outbox.
2. **What suspension does to people already signed in.** The organization's host answers `TENANT_UNAVAILABLE` at once for every
   request (the host is resolved before the token is looked at), so every session and token of that organization stops working
   immediately; in addition the sessions and grants bound to its host are ended, so **reinstating does not bring any back**:
   people sign in again. (Proved by running it: `OrganizationLifecycleIT`.)
3. **Deactivation is final** (the tenant trigger allows no way back). The console asks for a **typed confirmation** (the short
   name) and a reason, withdraws the organization's open invitations and ends its sessions. Deactivating a `PROVISIONING`
   organization is how a provisioning is cancelled (ADR-0037).
4. **Pending invitations of a suspended organization** stay in place but cannot be accepted while it is suspended (the accept step
   refuses with the same words as any unusable link); after reinstating they work again until they expire.
5. **Concurrency.** The organization row is locked while it moves, so two administrators suspending at the same moment have one
   winner (`409` for the other) and one audit record.
6. Illegal moves are refused in the service (`409`) and by the database.

## Verification

`OrganizationLifecycleIT` (suspend, sessions at once, reinstate needs a new sign-in, reasons required and bounded, legal moves,
two administrators at once, deactivation with the typed name and final, pending invitations, sign-out of everybody), `PlatformRolesIT`
(only administrators), `TenantLifecycleIT` (unchanged).
