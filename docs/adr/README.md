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
