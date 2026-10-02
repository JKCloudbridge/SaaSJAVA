# Working with tenants and events (for contributors)

What every feature that touches tenant data or publishes events needs to know. The reasons are in the decision records:
[ADR-0014](adr/0014-tenancy-model-and-tenant-context.md) (tenancy model and context),
[ADR-0015](adr/0015-row-level-security-implementation.md) (row level security),
[ADR-0016](adr/0016-transactional-outbox-and-idempotent-consumers.md) (outbox),
[ADR-0017](adr/0017-tenant-resolution-by-hostname.md) (host names).

## The rules in one screen

1. The tenant is **never** read from a request. It is derived on the server (the host name now; the authenticated
   membership too from Sprint 5) and held in a `TenantContext`.
2. Use `TenantContexts` (module `tenant`) to read it (`require()`), and to set it for work that has no request (jobs,
   events, tests): `contexts.run(TenantContext.of(tenantId), () -> ...)`. Open the context **before** the transaction begins.
3. Do not write the database tenant setting yourself. The transaction hook of the tenant module does it; a test fails the
   build if any other source file names the setting.
4. Hand work to another thread with `contexts.propagate(runnable)` or on an executor that uses the module's task decorator.
5. Every table with a `tenant_id` column follows the pattern below. Without a tenant, a transaction sees no tenant rows.
6. Announce events with `EventPublisher` and react with `EventHandler` beans, both in the shared kernel. Never call another
   module "backwards"; never depend on the `outbox` module.
7. Working across tenants is a system scope (`SystemScope`), reserved for platform infrastructure. A business module does not
   use it (an architecture test fails the build).

## A new tenant-scoped table (migration template)

```sql
create table example (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    -- business columns here
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid
);
create trigger example_row_guard before insert or update on example
    for each row execute function platform_row_guard();
create trigger example_tenant_guard before update on example
    for each row execute function platform_tenant_guard();
create index example_tenant on example (tenant_id, created_at);
alter table example enable row level security;
alter table example force row level security;
create policy example_isolation on example
    using      (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
```

Nothing is granted to the application role in the migration; default privileges (manual migration M001) cover it. Unique
indexes are partial (`where deleted_at is null`). Then register the table for the isolation tests:
[tenant-isolation-testing.md](tenant-isolation-testing.md).

## Publishing and handling an event

```java
// In the transaction of the change, with the tenant context open (a request, a job, an event handler):
events.publish(new NewEvent("record.created", "{\"recordId\":\"" + id + "\"}"));
```

```java
@Component
class AuditTenantEvents implements EventHandler {
    public String consumerName() { return "audit.tenant-events"; }      // stable: part of the idempotency key
    public Set<String> eventTypes() { return Set.of("tenant.suspended"); }
    public void handle(EventEnvelope event) { /* one transaction, the event's tenant context is open */ }
}
```

A handler's database changes happen once however often the event is delivered. A handler that calls something outside the
database must use `event.eventId()` as that call's idempotency key. A thrown exception retries the event with a growing
delay and, after the limit, sets it aside as a dead letter.

## Users and the tenant context (Sprint 3)

- A **user is a global identity**, not a tenant's data: the identity tables (`platform_user`, `user_credential`, `login_session`,
  `oauth2_authorization`, `audit_record`) are **platform-level** on purpose ([ADR-0022](adr/0022-platform-level-identity-tables-and-audit-v0.md)).
  Their tenant-related columns are called `bound_tenant_id` and `context_tenant_id`, never `tenant_id`.
- `TenantContext.userId` is filled in from the authenticated token (a filter after the token check). The tenant is **still read only from
  the host name**; a header, parameter or body cannot change it, and a token works only on the host it was issued on. On the platform host
  there is no tenant, so there is no context there; the caller is visible through `GET /api/v1/auth/me`. `membershipId` is still empty
  until Sprint 5.
- A new endpoint is **protected by default**: every `/api/v1` path needs a valid token unless it is on the short list in
  `SecurityConfiguration`; a test walks every controller mapping and fails for one that is public without being listed.
- Write the authorization test (allowed, denied, cross-tenant) with `TestBrowser` and `TestSignIn` (`testsupport`), which sign in for real.

## Sign-up, organizations and the mail queue (Sprint 4)

- Two more **platform-level** tables, decided in [ADR-0023](adr/0023-sign-up-verification-and-password-reset.md) and
  [ADR-0024](adr/0024-mail-queue-and-notification-v0.md): `account_token` (one-time link tokens) and `mail_queue` (the e-mail queue). Neither
  has a tenant column: a sign-up and a reset happen on the platform host, where there is no tenant. Both are in
  `SchemaConventions.PLATFORM_TABLES`, which the schema test asserts exactly.
- The first tenant-scoped table since Sprint 2 is `membership` ([ADR-0025](adr/0025-founding-an-organization-and-the-minimal-membership.md)),
  registered in `TenantScopedTables`, so the leak harness covers it. A signed-in person **founds an organization** with
  `POST /api/v1/organizations` on the platform host: the code opens the new tenant's context with `Tenants.newId()` **before** the transaction,
  provisions and opens the tenant, and writes the membership in that transaction. The tenant is never named by the client.
- A mail is queued with the `MailQueue` contract of the shared kernel (in the caller's transaction, with or without a tenant context); the
  notification module sends it later and decides what it becomes. Never put a secret or a link in a `MailRequest` (it refuses such names).
- The endpoints of sign-up and reset are public paths and answer only on the platform host (`NOT_FOUND` on an organization host).

## Configuration of this sprint

| Variable | Meaning |
|----------|---------|
| `PLATFORM_BASE_DOMAIN` | The platform domain; organizations live at `<slug>.<domain>`. Mandatory in every deployment; `local` uses `localhost`. |
| `PLATFORM_TRUST_FORWARDED_HOST` | `true` only behind a trusted proxy that overwrites `X-Forwarded-Host` on every request (default `false`; on in `local`). |
| `PLATFORM_OUTBOX_ENABLED` | Whether this instance runs the outbox relay (default `true`). |
| `PLATFORM_DB_APP_USER`, `PLATFORM_DB_APP_PASSWORD` | The application role created by manual migration M001. |
| `APP_DB_USER`, `APP_DB_PASSWORD` | The same role in the local environment file `infra/local/.env`. |

Tuning of the relay (`platform.outbox.*`: poll interval, batch size, lease, attempts, backoff, retention) is documented in
`OutboxProperties`. The variables of Sprint 4 (mail) are in the engineering guide and in `Sprint 4 manual steps.md`; their tuning is
`MailProperties` (`platform.notification.*`) and `IdentityProperties.Account` (`platform.identity.account.*`).

## On a developer machine

Open `http://tenant-a.localhost:3000` (and `tenant-b`): the `local` profile creates both organizations at start-up. See
[../infra/local/README.md](../infra/local/README.md) for the application role (`init-app-role.ps1`).
