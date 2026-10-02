# ADR-0025: A signed-in person founds an organization; the minimal membership

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S4
- **Related:** [ADR-0014](0014-tenancy-model-and-tenant-context.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md), [ADR-0023](0023-sign-up-verification-and-password-reset.md)

## Context

Sprint 2 decided that no tenant-creation endpoint exists until "the first authenticated way to create a tenant" arrives with
sign-up, and that it uses `Tenants.provision`. The delivery plan also says sign-up creates a membership and a tenant
administrator, while the full `Membership` (statuses, invitations, switching) is Sprint 5 and roles and permissions are
Sprint 7. The user decided that sign-up gives an individual account and that an organization is founded afterwards, by a
signed-in person (ADR-0023).

## Decision

1. **The endpoint**: `POST /api/v1/organizations` (authenticated, platform host only; `NOT_FOUND` on an organization host).
   The body names the organization and its short name (slug); the **tenant is never named by the client** (a test sends a
   tenant ID in the body and in a header and gets a new organization of its own, with no seat anywhere else).
2. **What it does, in one transaction under the new tenant's context**: locks the person's user row, checks the person is
   active and has not reached the limit, provisions the tenant, opens it (`PROVISIONING` to `ACTIVE`, both through
   `Tenants`, with their events in the outbox), writes the membership and audits `tenant.organization.founded`. Nothing is
   half-made: a failure (a taken slug, the limit) rolls everything back.
3. **Limits**: at most **3 organizations per person** (`Tenants.countFoundedBy`: the tenant table records who provisioned
   it; the memberships cannot be counted across tenants because they are isolated per tenant) and **5 foundings per hour per
   person**. The row lock makes the limit exact under concurrency. Sprint 6 attaches plans and may replace the number.
4. **The slug** is checked by `TenantSlug` (format, reserved names) and by the unique index of the tenant module: two people
   asking for one name at once get one winner (a test with six threads). A taken, reserved or malformed slug is a
   `VALIDATION_ERROR` on the field `slug` with the message "Is not available." for both the taken and the reserved case, so the
   answer does not distinguish them. (That a web address is taken is public information anyway: it is a DNS name.)
5. **The minimal membership** (V012): a tenant-scoped table (`tenant_id`, row level security enabled and forced, tenant guard,
   tenant-first index, registered in `TenantScopedTables`, so the cross-tenant leak harness covers it) with the user, a
   status (only `ACTIVE` for now) and `founding_administrator`. **No role or permission is stored**: Sprint 7 attaches them
   to the membership. Sprint 5 extends the statuses, adds invitations and deactivation, validates the host against the
   membership, and fills `membershipId` in the tenant context. Until then, as stated in Sprint 3, any signed-in user can
   sign in on any organization host and gets no permission from it.
6. **The response** carries the organization's host, built by the server from the host the request came to; the browser
   never composes an organization's address.

## Consequences

- The tenant lifecycle events of Sprint 2 are written in real use for the first time; the outbox relay handles them.
- A person can reach an organization's sign-in page right after founding it, and sign in there, but nothing tenant-private
  exists to see until later sprints.
- Counting by `created_by` means an organization provisioned by the platform itself (the local seed, platform administration
  in Sprint 6) does not count against anyone.

## Alternatives considered

- **Sign-up creates the tenant** (the written story): see ADR-0023 for what it leaks.
- **Count memberships to enforce the limit**: not possible across tenants without a system scope; a platform-level counter
  table would add a table for a number the tenant table already has.
- **Make the person an administrator by a role now**: roles do not exist until Sprint 7; a flag on the membership is the
  smallest true statement.

## References

- Migration: `platform-app/src/main/resources/db/migration/V012__create_membership_table.sql`
- Code: `platform-app/src/main/java/app/platform/identity/internal/OrganizationService.java`, `OrganizationController.java`,
  `MembershipRepository.java`, `platform-app/src/main/java/app/platform/tenant/Tenants.java` (`countFoundedBy`)
- Tests: `OrganizationIT`, `TenantIsolationIT` (membership registered in `TenantScopedTables`), `SchemaConventionsIT`
