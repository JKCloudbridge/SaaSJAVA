# ADR-0022: Identity tables are platform-level; audit version 0 and authentication events without a tenant

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S3
- **Stories:** S3-SEC-17, S3-SEC-22
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0014](0014-tenancy-model-and-tenant-context.md),
  [ADR-0015](0015-row-level-security-implementation.md), [ADR-0019](0019-authentication-implementation.md)

## Context

The schema scanner and the leak harness (ADR-0015) fail the build for a table that is neither tenant-scoped (a `tenant_id`, forced row
level security, a tenant guard, a tenant-first index) nor listed as platform-level. Sprint 3 adds five tables for which tenant scoping
is wrong, so adding them to the platform-level list is a **security decision** and is recorded here, as ADR-0015 requires.

Why they cannot be tenant-scoped: a **user is a global identity**: one person may belong to several organizations (Sprint 5), so the
identity, its credential, its sign-in sessions and its token grants exist once for the whole platform and are looked up before any
tenant is known (a sign-in on the platform host has no tenant at all). An **authentication event** can likewise happen where there is
no tenant, so the audit table that holds it cannot require one.

## Decision

1. **Five platform-level tables**, listed in `SchemaConventions.PLATFORM_TABLES` and in the migrations' comments:
   `platform_user` (V005), `user_credential` (V006), `login_session` (V007), `oauth2_authorization` (V008), `audit_record` (V009).
   They have no `tenant_id` and no row level security. Everything else about them follows ADR-0010 (identifier, base columns, the row
   guard trigger, partial unique indexes).
2. **The tenant-related columns are named so they cannot be mistaken for tenant scoping**: `bound_tenant_id` (the organization host a
   session or grant is bound to; null for the platform host) and `context_tenant_id` (the tenant of the request an audit record was
   written under). The scanner treats a column named `tenant_id` as "tenant data, needs row level security", and these do not hold
   tenant data.
3. **Why this is safe.** The tables hold no tenant business data. The application never returns another person's row to a caller:
   the only endpoint that shows a user is `GET /api/v1/auth/me`, which names the caller; the application role can read and write them
   but a bug in a business module cannot reach them (the module boundary and an architecture rule keep credentials and tokens inside
   the identity module; no other module reads these tables). What a user may do inside an organization is decided by the membership
   and the authorization engine, never by these tables. Secrets in them are protected by hashing, not by row level security: the
   password is an Argon2id hash, and every bearer secret (token, code, login cookie) is stored only as a SHA-256 hash.
4. **Audit version 0.** A contract `AuditRecorder` and `AuditRecord` in the shared kernel (so no business module depends on the audit
   module), a small implementation in the `audit` module that inserts one row of `audit_record`, **in the same database transaction as
   the change it describes** when there is one (it joins the caller's transaction) and on its own otherwise. The record has a
   lower-case event type (`auth.sign_in.failed`), an outcome (`SUCCESS`, `FAILURE`, `DENIED`), the user, the tenant of the request (none
   on the platform host), the true internal reason, the request and trace IDs and a bounded set of attributes. The record type
   **refuses an attribute whose name suggests a secret** (password, token, secret, cookie, verifier, code, authorization), so a
   mistake fails in a test instead of leaking. A storage failure is logged (exception type and event type, never content) and does not
   fail the caller's request; inside a transaction that is a limit (a failed insert aborts the transaction), accepted for a table that
   cannot fail in normal operation.
5. **Append-only.** A trigger refuses every update and delete of an audit row whoever runs it; the application role has no
   `TRUNCATE`. The owner role can still truncate or drop the table, which is why a deployment's owner role is not used at run time
   (ADR-0009). Retention, archiving and tamper evidence are Sprint 9 and Sprint 34.
6. **Nothing reads the audit table yet.** No endpoint returns audit rows. Sprint 9 decides the read model (platform rows versus the
   rows of one tenant, and the row level security that goes with it) **before** any read path exists.
7. **Events published so far**: `auth.user.created`, `auth.user.status_changed`, `auth.sign_in.succeeded`, `auth.sign_in.failed`,
   `auth.sign_in.rate_limited`, `auth.account.locked`, `auth.password.changed`, `auth.password.change_refused`,
   `auth.password.hash_upgraded`, `auth.token.issued`, `auth.token.refreshed`, `auth.token.refused`, `auth.refresh.reuse_detected`,
   `auth.session.revoked`, `auth.sign_out`, `auth.sign_out_all`.

## Consequences

- The tenant-isolation tests of Definition of Done have nothing new to register: **no new tenant-scoped table exists in this sprint**.
  The existing leak harness and schema scanner stay unchanged and keep passing; the scanner's list of platform-level tables is asserted
  exactly (`SchemaConventionsIT`), so a further addition needs a visible change and a reason.
- Sprint 9 inherits a working append-only table and a contract; it replaces the implementation and adds the read side.
- A user row, a credential and a token grant are readable by any code that holds the application role's connection. That is the
  reason for the module boundary rule and for storing only hashes; it is also why authorization for those tables is enforced in the
  identity module's code and tested (`AuthEndpointsAuthorizationIT`).

## Alternatives considered

- **Tenant-scope the user table with a "home tenant"**: wrong for a person with memberships in several organizations.
- **A separate identity database or schema**: more isolation, much more operational weight; revisit with the production design.
- **Send authentication events through the transactional outbox** (ADR-0016): it needs a tenant context and the outbox is delivered
  asynchronously, so an event without a tenant would need a platform scope and a security record could be delayed; a synchronous
  record in the same transaction is the stronger guarantee for security events. The audit module may still publish a reduced event
  stream to others later.
- **Row level security with a "platform rows" policy now**: the read model is undecided; a policy written before the reader exists
  would be guesswork.

## References

- Migrations: `V005__create_platform_user_table.sql` to `V009__create_audit_record_table.sql`
- Code: `platform-shared-kernel/src/main/java/app/platform/sharedkernel/audit/`, `platform-app/src/main/java/app/platform/audit/`,
  `platform-app/src/test/java/app/platform/database/SchemaConventions.java`
- Tests: `AuditIT`, `SchemaConventionsIT`, `AuditRecordTest`, `UsersIT`
