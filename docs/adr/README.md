# Architecture decision records

One file per decision, numbered in the order decisions were taken. Start from [0000-template.md](0000-template.md).
An accepted ADR is never rewritten: a changed decision gets a new ADR that supersedes the old one.
A decision that is still open has status **Proposed** and names the gate or sprint that closes it.

| ADR | Title | Status | Decision |
|-----|-------|--------|----------|
| [0001](0001-modular-monolith.md) | Modular monolith with Spring Modulith | Accepted | — |
| [0002](0002-hybrid-data-store.md) | Hybrid data store: relational platform data, JSONB tenant records | **Proposed (gate G1 pending, Sprint 14)** | — |
| [0003](0003-rls-defence-in-depth.md) | Tenant isolation: row level security as defence in depth | Accepted | — |
| [0004](0004-maven-build.md) | Maven build, wrapper, BOM and enforcer rules | Accepted | D1 |
| [0005](0005-authentication-approach.md) | Authentication: Spring Authorization Server with local credentials behind a provider abstraction | Accepted | D2 |
| [0006](0006-no-static-api-keys.md) | No static API keys in V1 | Accepted | D10 |
| [0007](0007-secrets-store-interface.md) | Cloud-neutral secrets store interface | Accepted (interface only) | D4 |
| [0008](0008-database-migrations-single-source.md) | Database migrations: one source of truth | Accepted | — |
| [0009](0009-migration-framework-and-database-roles.md) | Migration framework, database roles and start-up | Accepted | — |
| [0010](0010-base-schema-conventions.md) | Base schema conventions: identifiers, audit columns, soft delete, versions | Accepted | — |
| [0011](0011-api-conventions.md) | API conventions: versioning, envelope, errors, paging, contract | Accepted | — |
| [0012](0012-observability.md) | Observability: logs, metrics, traces, error tracking, correlation | Accepted | — |
| [0013](0013-local-deployment-approach.md) | Deployment target for local development | Accepted for local (production part open until Sprint 33) | D5 |
| [0014](0014-tenancy-model-and-tenant-context.md) | Tenancy model, tenant lifecycle and the tenant context | Accepted | — |
| [0015](0015-row-level-security-implementation.md) | Row level security: roles, policies, system scope and what was verified | Accepted (implements 0003) | — |
| [0016](0016-transactional-outbox-and-idempotent-consumers.md) | Transactional outbox, polling relay and idempotent consumers | Accepted for the outbox and relay (processor and broker close in Sprint 22) | D3 |
| [0017](0017-tenant-resolution-by-hostname.md) | Tenant resolution by host name | Accepted | — |
| [0018](0018-persistence-access-jdbc-now-jpa-open.md) | Persistence access: plain JDBC for platform tables now, JPA adoption left open | Accepted for current code (JPA decision open, closes by Sprint 14) | — |
| [0019](0019-authentication-implementation.md) | Authentication implementation: sign-in, sessions, tokens, revocation and keys | Accepted (implements 0005) | D2 |
| [0020](0020-passwords-and-hashing.md) | Passwords: policy, Argon2id hashing and its measured parameters | Accepted | — |
| [0021](0021-brute-force-protection-and-rate-limits.md) | Brute-force protection: uniform answers, constant work, the lock, rate limits and the outage rule | Accepted | — |
| [0022](0022-platform-level-identity-tables-and-audit-v0.md) | Identity tables are platform-level; audit version 0 and authentication events without a tenant | Accepted | — |
| [0023](0023-sign-up-verification-and-password-reset.md) | Sign-up, e-mail verification and password reset: address first, one answer for every address, one-time links | Accepted | — |
| [0024](0024-mail-queue-and-notification-v0.md) | The mail queue and notification v0: a platform-level queue, a relay with retries, no secret at rest | Accepted | — |
| [0025](0025-founding-an-organization-and-the-minimal-membership.md) | A signed-in person founds an organization; the minimal membership | Accepted | — |
| [0026](0026-membership-lifecycle-and-the-administrator-marker.md) | Membership lifecycle, the administrator marker and the check on every request | Accepted; the marker was replaced in Sprint 7 by [ADR-0043](0043-replacing-the-administrator-marker.md) | ADR-0043 |
| [0027](0027-which-organizations-does-a-person-belong-to.md) | Which organizations does a person belong to: a narrow system scope | Accepted | — |
| [0028](0028-invitations.md) | Invitations: a record of the organization, a one-time link, a membership only on acceptance | Accepted | — |
| [0029](0029-switching-organizations.md) | Switching organizations: a one-time proof carried to the other host | Accepted | — |
| [0030](0030-platform-roles-and-the-first-platform-administrator.md) | Platform roles, the first platform administrator and what protects those accounts | Accepted (multi-factor sign-in is a go-live gate) | — |
| [0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md) | Where the Sprint 6 tables live, and how a platform administrator reaches one organization | Accepted | — |
| [0032](0032-licences-pools-assignments-and-their-rules.md) | Licences: types as data, a pool per organization, an assignment per member, and the rules | Accepted | — |
| [0033](0033-subscriptions-trials-and-the-organization-limit.md) | Subscriptions, "try for free" trials, and the limit of organizations per person | Accepted | — |
| [0034](0034-feature-entitlements.md) | Feature entitlements: keys as data, plan defaults, overrides, one read contract | Accepted | — |
| [0035](0035-controlled-support-access.md) | Controlled support access: request, approval, time limit, audit, no automatic access | Accepted | — |
| [0036](0036-administrative-sessions.md) | Administrative sessions: listing, signing a person or an organization out | Accepted | — |
| [0037](0037-provisioning-an-organization-for-a-client.md) | Provisioning an organization for a client and inviting its first administrator | Accepted | — |
| [0038](0038-organization-lifecycle-by-platform-administrators.md) | Organization lifecycle by platform administrators: suspend, reinstate, deactivate | Accepted | — |
| [0039](0039-abilities-profiles-access-policies-and-individual-grants.md) | Abilities, profiles, access policies and individual grants | Accepted | — |
| [0040](0040-effective-permissions-and-the-read-contract.md) | Effective permissions: the algorithm (union, no deny) and the one read contract | Accepted | — |
| [0041](0041-where-the-access-tables-live-and-the-module-edge.md) | Where the access tables live (all tenant-scoped), and the module edge identity to security | Accepted | — |
| [0042](0042-the-role-hierarchy.md) | The role hierarchy: visibility only, no loops (service and database) | Accepted | — |
| [0043](0043-replacing-the-administrator-marker.md) | Replacing the administrator marker; creating a member by invitation; leaving | Accepted | ADR-0026 in part |
| [0044](0044-the-last-member-who-can-manage-access.md) | The last member who can manage access stays (database and service) | Accepted | — |
| [0045](0045-system-profiles-seeding-backfill-and-licence-consequences.md) | System profiles, seeding, the backfill, and what licences mean for abilities | Accepted | — |
| [0046](0046-licence-kinds-and-the-licence-rule-of-access-policies.md) | Licence kinds (seat, add-on) and the licence rule of access policies | Accepted | ADR-0039 point 3 in part |
| [0047](0047-public-groups.md) | Public groups: nesting, access policies, loops | Accepted | — |
| [0048](0048-groups-and-the-last-holder.md) | Groups and the last member who can manage access | Accepted | — |
| [0049](0049-object-and-field-permissions.md) | Object and field permissions: keys, containers, union, implications, no deny | Accepted | — |
| [0050](0050-the-permission-decision-api.md) | The permission decision API | Accepted | — |
| [0051](0051-dropping-the-administrator-marker.md) | Dropping the administrator marker (contract migration) | Accepted | — |
| [0052](0052-platform-authorization-manager-and-method-security.md) | PlatformAuthorizationManager and method security | Accepted | — |
| [0053](0053-the-security-cache-and-its-version.md) | The security cache and the security version (with the cost note) | Accepted | delivery plan S9: no Redis tier |
| [0054](0054-audit-v1-record-source-and-read-model.md) | Audit v1: the record, its source, who may read it | Accepted | ADR-0022 in part |
| [0055](0055-audit-retention-and-the-append-only-door.md) | Audit retention and the one door through the append-only rule | Accepted | — |
| [0056](0056-the-audit-viewer.md) | The audit viewer (organization and platform) | Accepted | — |
| [0057](0057-retention-of-closed-invitations.md) | Retention of closed invitations | Accepted | — |
