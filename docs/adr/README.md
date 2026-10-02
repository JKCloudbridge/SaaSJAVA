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
