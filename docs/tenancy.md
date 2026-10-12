# Working with tenants and events (for contributors)

What every feature that touches tenant data or publishes events needs to know. The reasons are in the decision records:
[ADR-0014](adr/0014-tenancy-model-and-tenant-context.md) (tenancy model and context),
[ADR-0015](adr/0015-row-level-security-implementation.md) (row level security),
[ADR-0016](adr/0016-transactional-outbox-and-idempotent-consumers.md) (outbox),
[ADR-0017](adr/0017-tenant-resolution-by-hostname.md) (host names).

## The rules in one screen

1. The tenant is **never** read from a request. It is derived on the server (the host name, checked against the
   authenticated person's active membership since Sprint 5) and held in a `TenantContext`.
2. Use `TenantContexts` (module `tenant`) to read it (`require()`), and to set it for work that has no request (jobs,
   events, tests): `contexts.run(TenantContext.of(tenantId), () -> ...)`. Open the context **before** the transaction begins.
3. Do not write the database tenant setting yourself. The transaction hook of the tenant module does it; a test fails the
   build if any other source file names the setting.
4. Hand work to another thread with `contexts.propagate(runnable)` or on an executor that uses the module's task decorator.
5. Every table with a `tenant_id` column follows the pattern below. Without a tenant, a transaction sees no tenant rows.
6. Announce events with `EventPublisher` and react with `EventHandler` beans, both in the shared kernel. Never call another
   module "backwards"; never depend on the `outbox` module.
7. Working across tenants is a system scope (`SystemScope`), reserved for platform infrastructure. A business module does not
   use it (an architecture test fails the build). The only scope outside infrastructure is the identity module's read-only
   `membership_lookup`, entered by one class ([ADR-0027](adr/0027-which-organizations-does-a-person-belong-to.md)).

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
  there is no tenant, so there is no context there; the caller is visible through `GET /api/v1/auth/me`. Since Sprint 5 `membershipId` is
  filled on an organization host (see below).
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

## Membership, invitations and switching (Sprint 5)

Decisions in [ADR-0026](adr/0026-membership-lifecycle-and-the-administrator-marker.md) to
[ADR-0029](adr/0029-switching-organizations.md). What a contributor needs:

- **Being inside an organization means an active membership.** On an organization host the token check requires an active membership of
  the organization the host names (`MembershipGate`), and `TenantContext` carries `userId` and `membershipId`. Sign-in on an organization host
  refuses a non-member exactly like a wrong password. A test that signs a person in on an organization host must make them a member first
  (`TestMembers.add(...)`, or `TestOrganizations`).
- **"May this member do this?"** is asked in one place, `Administration.run(action, ability, work)`. Since Sprint 7 the answer is the member's
  effective abilities (their licensed profile, access policies and individual grants), not the Sprint 5 `administrator` marker, which nothing
  reads any more (the historical `founding_administrator` fact grants nothing); see "Profiles, roles and access policies (Sprint 7)" below.
  Wrap every administrative action in it so a refusal is audited.
- **Tenant-scoped:** `membership` (lifecycle trigger; since Sprint 7 the "last member who can manage access" rule, ADR-0044) and `invitation` (open, accepted,
  revoked; one open invitation per address and organization). Both are in `TenantScopedTables`.
- **Platform-level:** `organization_handoff` (the 60-second proof of a switch, hash only, column `bound_tenant_id`). `account_token` has a
  new purpose, `INVITATION`, with `context_tenant_id` and `invitation_id`, so the link resolves to the organization on the server.
- **A person's organizations** are answered only by `OrganizationDirectory` (system scope `membership_lookup`, read-only, always by
  user). Do not add another reader of that scope.
- **A new invitation-like mail** follows the Sprint 4 pattern: a `MailTemplate`, a text in `MailTexts`, a branch in `MailComposer`, and
  the account-state decision at send time (here `Invitations.forMail`). Text chosen by an administrator (the organization's name) goes
  through `MailTexts.safeName`.
- Tests: `TestOrganizations`, `TestMembers`, `InvitationFlowIT` (mail catcher, relay driven by the test), `MembershipIT`,
  `SwitchOrganizationIT`, `MembershipGuardIT` (database rules), `MembershipFlowsLogsAreCleanIT`. Limit tests must tolerate one split
  count (Redis answered late, ADR-0021): assert that the limit arrives within twice the number.

## Licensing, platform roles and platform administration (Sprint 6)

Decisions in [ADR-0030](adr/0030-platform-roles-and-the-first-platform-administrator.md) to
[ADR-0038](adr/0038-organization-lifecycle-by-platform-administrators.md). What a contributor needs:

- **Where each new table lives** ([ADR-0031](adr/0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md)).
  Platform-level (no tenant column, listed in `SchemaConventions.PLATFORM_TABLES`): `platform_role_assignment`, `licence_type`,
  `feature`, `plan`, `plan_licence`, `plan_feature`, `subscription`, `entitlement_override` (the last two name the organization
  in `bound_tenant_id`). Tenant-scoped (registered in `TenantScopedTables`): `licence_pool`, `licence_assignment`,
  `support_access_grant`.
- **A platform person never gets a privileged connection or a new system scope.** To read or write one organization's
  tenant-scoped records, open **that one organization's context before the transaction** (`OrganizationScope` in `platformadmin`,
  `OrganizationWork` in `licensing`); row level security then limits the work to it. A list across organizations comes from the
  platform-level tables only. An endpoint that names an organization names a destination chosen by an authorized platform person,
  never "the tenant of the request" (a test forges a tenant header and nothing changes).
- **Platform endpoints** are under `/api/v1/platform/...`, answered on the **platform host only** (`NOT_FOUND` on an organization
  host) and only for the roles each needs (`PlatformCaller.require(principal, roles...)`). Organization endpoints stay on
  organization hosts and ask `Administration`. A platform role gives no organization authority and the reverse.
- **Licences** are asked through the public `Licences` contract of the `licensing` module: the identity module assigns the default
  licence when a membership becomes active and releases it when it ends, in the same transaction. A licence counts assignments
  only; features are asked with `Entitlements.enabled(feature)`; permissions are a third, separate mechanism.
- **Support access:** before any read or change of an organization's data on behalf of a platform person, call
  `SupportAccess.require(organization, platformUser)` (shared kernel contract); it refuses without an approved, unexpired,
  unrevoked grant of that person for that organization.
- **Tests:** `TestPlatform` (platform people and plans), `PlatformRolesIT` (role against every endpoint), `LicencesIT`,
  `ProvisioningIT`, `OrganizationLifecycleIT`, `SupportAccessIT`, `SessionAdministrationIT`, `SubscriptionsAndEntitlementsIT`,
  `PlatformFlowsLogsAreCleanIT`, `FirstPlatformAdministratorScriptIT` (the manual step run with the real command line tool).
- **Settings:** `platform.licensing.default-plan` (`trial`) (Sprint 7 removed `platform.licensing.default-licence-type`; the licence a new member gets is the one of their profile),
  `platform.identity.tokens.platform-session-max` (`4h`).

## Profiles, roles and access policies (Sprint 7)

The contributor view of ADR-0039 to ADR-0045. Every table of the sprint is **tenant-scoped** (`profile`, `access_policy`, `security_role`,
`member_access`, `member_access_policy`, `member_grant`); none is platform-level, so there is nothing to seed outside an organization's own
context. The two system profiles are created in that context (founding, provisioning by a platform administrator, the first member, the
backfill); a platform administrator reaches one organization by opening its context before the transaction (`OrganizationScope`), as for
pools, with no privileged connection and no new system scope.

- A foreign key check bypasses row level security, so every table has a guard trigger that checks the rows it names belong to the same
  organization (and that a member is active). A foreign identifier is simply not found.
- Whoever writes a membership must give it access in the same transaction: `MemberAccess.join` (new), `returned` (reactivation), `left`
  (deactivation or leaving). A platform person who opened an organization's context has no membership and therefore no abilities.
- Changing what members hold takes the per-organization advisory lock first (`lockAccessChanges` in the identity, licensing and security
  repositories, the same lock the database triggers take); then the member row; then the licence pool row.
- Test fixtures: `TestMembers.add` (see the engineering guide), `TenantScopedTables` lists the six new tables, `MigrationIT` has the backfill
  test, `AccessGuardIT` the guard under real concurrency. A test of a table's policy uses the application role, not the owner.

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

## Public groups, object and field permissions and the decision API (Sprint 8)

Short form; decisions in ADR-0046 to ADR-0052.

- **Tenant-scoped tables (ADR-0015):** `public_group`, `public_group_member`, `public_group_access_policy`, `object_permission`, `field_permission`, each with
  forced row level security, the tenant guard and a guard that keeps every row inside its organization (a foreign key bypasses row level security).
  `licence_type` gained a `kind` (platform-level catalogue). Register a new table in `TenantScopedTables`.
- **Groups** carry access policies; no loops (service and database, one lock per organization); a member who ends leaves every group.
- **Permissions on data** live in the same three containers as abilities and are combined by the same union; objects and fields are named by key and
  validated by `ObjectCatalog`. Ask only through `Decisions` (`can`, `accessTo`, `accessToAll`); the tenant is the context, never a parameter; unknown and
  forbidden answer alike.
- **Licences:** a policy of the licence type of the member's profile uses no extra licence; a different type takes one (ADR-0046).
- **Platform functions:** annotate a platform console endpoint with `@PlatformFunction`; an architecture test fails without it (ADR-0052).
- **Tests:** `DecisionMatrixTest`, `DataAccessPropertyTest`, `GroupIT`, `GroupGuardIT`, `DataAccessIT`, `PolicyLicenceRuleIT`, `GroupFlowsLogsAreCleanIT`.

## The security cache, audit v1 and retention (Sprint 9)

Short form; decisions in ADR-0053 to ADR-0057.

- **The security version (ADR-0053).** `security_version` holds one counter per organization (tenant-scoped). A trigger on every table that
  decides what a member may do raises it in the same transaction as the change (`profile`, `access_policy`, `member_access`,
  `member_access_policy`, `member_grant`, `public_group`, `public_group_member`, `public_group_access_policy`, `object_permission`,
  `field_permission`, `licence_assignment`, `membership`). **A new table that feeds an answer of `Permissions` or `Decisions` needs the
  trigger** (copy one from V028) **and an entry in `SecurityVersionIT`**; the test fails when the set differs.
- **The cache** keeps an answer with the version it was computed under and serves it only while the version is unchanged: the bound is "the next
  question after the commit" on every instance. A transaction that has written never uses or fills it. Setting
  `platform.security.cache.enabled=false` turns it off; `platform.security.cache.max-entries` bounds it. It does not use Redis. **Cost note:** every
  question pays one extra read (the version); it pays off only for answers that take several reads. Read ADR-0053 before changing it.
- **Audit v1.** Keep writing through `AuditRecorder`. `AuditRecord` has optional `objectKey`, `recordId`, `oldValue`, `newValue`, `source`
  (`onObject`, `changing`, `from`); values are short keys, never typed text. A new kind of record gets an audience in `AuditAudience` (organization,
  platform, both); without a rule it is visible only to the side it happened on.
- **`audit_record` is platform-level with a read policy by audience** (ADR-0054): a reader with a tenant sees that organization's rows meant for
  organizations, a reader without one sees the rows meant for the platform. Row level security is enabled and **not** forced on this one table (the
  owner runs the purge function and must see every row); `TenantIsolationIT` names this exception.
- **Append-only, with one door:** only `platform_audit_purge()` deletes (older than the retention period, never younger than 30 days, writes its own record).
  Nobody else, not even the owner, can change or delete a record. Retention: `platform.audit.retention.*` (400 days default).
- **Viewer:** `GET /api/v1/audit-events` (ability `audit.view`, organization hosts) and `GET /api/v1/platform/audit-events` (platform administrators, platform host); the
  public interface is `AuditEvents`.
- **Events:** the tenant lifecycle events of the outbox become `tenant.lifecycle.*` records (once each: the unique `source_event_id`). The use of the
  `membership_lookup` system scope becomes `system.scope.used`.
- **Invitations:** a closed invitation keeps its address 30 days (`platform.identity.cleanup.keep-closed-invitations`), then it is blanked by the
  identity clean-up (ADR-0057).
- **Tests:** `SecurityCacheTest`, `SecurityVersionIT`, `SecurityCacheIT` (three real instances, cache on and off), `SecurityCacheConsistencyIT`,
  `SecurityCacheRedisOutageIT`, `AuditStorageIT`, `AuditViewerIT`, `TenantLifecycleAuditIT`, `InvitationRetentionIT`.

## Object and field definitions (Sprint 10)

Short form; decisions in ADR-0058 to ADR-0062, the how-to in [metadata-guide.md](metadata-guide.md).

- **Tenant-scoped tables (ADR-0015):** `object_definition`, `field_definition` and `metadata_version` (V031), each with forced row level security, the tenant
  guard and a tenant-first index; registered in `TenantScopedTables`. The standard objects and fields are **not** in the database: they are read from
  `metadata/standard/*.yml` at start and are the same for every organization, so no tenant has a way to write them (ADR-0059).
- **A field names its object by API name**, not by identifier, because the object may be a standard one that has no row. A guard keeps the API name, the object
  and the data type of a field from ever changing and ties a field of a custom object to a live custom object of the same organization.
- **Metadata version (ADR-0061).** `metadata_version` has one counter per organization, raised by triggers on both definition tables in the same transaction.
  The catalogue cache keeps a snapshot with the version it was built under and serves it only while the version is unchanged. Both definition tables also raise
  the **security version** (a new object or field can change an answer of `Decisions`), so they have an entry in `SecurityVersionIT`.
- **Ask what exists through `Metadata`** (metadata root): `objects()`, `object(apiName)`. Security reads the same catalogue through its `ObjectCatalog`. Both
  answer for the thread's tenant context; asked outside a transaction they open a short one of their own (the tenant is set only inside a transaction).
- **Removing** an object or a field ends its permissions in the same transaction through `DataPermissionCleanup` (security root) and is refused while a field of
  another object points at the object; `ObjectUsage` (metadata root) is the seam the data module implements to refuse removal while records exist.
- **Names:** an organization's API names end in `__c`; the platform's never do. The API name is the key of permissions on data (V032 widened the three key checks).
- **Tests:** `StandardMetadataLoaderTest`, `StandardMetadataBaselineTest`, `StandardObjectsDocTest`, `FieldRulesTest`, `NameRulesTest`, `ConfigCodecTest`,
  `MetadataApiIT`, `MetadataCacheIT` (three real instances), `MetadataFlowsLogsAreCleanIT`, plus the isolation harness and `SecurityVersionIT` for the new tables.
  Tests that need objects to name get `ObjectA__c` and `ObjectB__c` from `TestObjects` (every `TestOrganizations.create`).

## Relationships, record types and the lifecycle of metadata (Sprint 11)

Short form; decisions in ADR-0063 to ADR-0068, the how-to in [metadata-guide.md](metadata-guide.md).

- **Four more tenant-scoped tables (ADR-0015, V033):** `record_type`, `metadata_change_set`, `metadata_change` and `metadata_release`, each with forced row level security, the tenant
  guard and a tenant-first index; registered in `TenantScopedTables`. `record_type` is **published** metadata (it raises the metadata version); the other three are the
  lifecycle and never touch the version or the catalogue.
- **A draft is invisible.** Only published rows are in the catalogue snapshot, the cache, the metadata version and the security version. A change set is rows in
  `metadata_change_set` and `metadata_change`; checking and previewing apply the changes in a transaction that is **always rolled back** (`MetadataAdministration.rehearse`),
  publishing commits the same work (ADR-0066). Never write to a definition table from anywhere but `MetadataService`, `RecordTypeService` and `ChangeApplier`.
- **Every metadata change goes through the lifecycle** (`MetadataLifecycle`): the live endpoints are a publication of one change (`applyNow`), a change set is a publication
  of many, a rollback is a publication of the undo. All take the organization's publication lock first and all end with the dependency check (ADR-0065).
- **A new kind of dependent** (a layout, a validation rule) implements `DependencyGraph.Contributor` as a bean; nothing else needs to know. A rule it adds is a violation with a message
  that names the dependent and never prints a typed value.
- **Asking** record types and relationships: `Metadata.recordTypes(object)` (metadata root) and the relationships endpoint; relationships are computed from the fields, never stored.
- **Abilities:** `metadata.view`, `metadata.manage` (draft) and `metadata.publish` (put live, roll back); a live change needs `manage` and `publish` (ADR-0068).
- **Tests:** `RelationshipsIT`, `RecordTypesIT`, `ChangeSetLifecycleIT`, `RollbackIT`, `RollbackWithRecordsIT` (since Sprint 14 with real records), `LifecycleAccessIT` (allowed, denied, cross-tenant, forged
  header, platform administrator, unauthenticated for every new endpoint), `LifecycleFlowsLogsAreCleanIT`, `DependencyGraphTest`, `RecordTypeRulesTest`, and the isolation harness for the four tables.

## Layouts, record pages, applications and assignments (Sprint 12)

- **Four tenant-scoped tables** (V034), each with `tenant_id not null default platform_current_tenant()`, row level security enabled **and forced**, the tenant guard, a tenant-first partial unique index and the
  metadata version trigger: `page_layout`, `record_page`, `application`, `layout_assignment`. They are registered in `TenantScopedTables`, so `TenantIsolationIT` probes them.
- **The tenant is never in a request.** Every endpoint is answered on an organization host only, derives the organization from the host and the token, and a definition of another organization does not exist
  for the caller (404). The member's menu (`GET /api/v1/applications`) is the menu of the member the token names.
- **A profile in an assignment or an application is a plain identifier** of the same organization's profile table; there is no foreign key, so deleting a profile is never blocked (ADR-0071). The profile of a
  member is read with `MemberAccess.profileOf`.
- **Drafts are not rows of these tables.** A layout in a change set is a row of `metadata_change`; the new tables hold what is published (ADR-0066, ADR-0069).
- **Contributor notes:** a new check that depends on layouts goes into `DefinitionDependencies`; a new field of a definition goes into the schema file and nowhere else.

## Validation rules, formulas and the metadata runtime (Sprint 13)

- **One tenant-scoped table** (V035), built like the four of Sprint 12: `validation_rule` with `tenant_id not null default platform_current_tenant()`, row level security enabled **and forced**, the tenant guard, the
  identity guard of V034 (the name and the object of a rule never change; a rule of a custom object names a live custom object of the same organization), a tenant-first partial unique index and the metadata
  version trigger. It is registered in `TenantScopedTables`, so `TenantIsolationIT` probes it.
- **The expression engine has no tenant.** It takes a map of values and a map of field types and touches nothing else (no database, no tenant context, no clock): there is nothing in it to isolate, and an
  architecture test (`ExpressionSandboxTest`) keeps it that way. The check and try-it endpoints read only the caller's own catalogue; a forged tenant header changes nothing; a definition of another organization
  does not exist for the caller (404).
- **The compiled artefacts are per organization.** `MetadataResolver` answers for the thread's tenant context; the compiled rules and formulas are kept in the snapshot of that organization and in no other place
  (`MetadataRuntimeIT.anotherOrganizationNeverGetsTheArtefactsOfThisOne`).
- **The publish event** (`metadata.published`) is written through `EventPublisher` in the publishing transaction with the tenant of that transaction; the handler `MetadataWarmer` runs in the tenant context of the event and
  never takes a tenant from the payload (the payload holds the release number and the version only). The outbox hands the event to one instance (ADR-0080).
- **Text of the organization is never logged or audited:** the expression, the message of a rule, the sample values and the answers. The audit trail names the rule and the counts (`metadata.validationrule.*`).
- **Contributor notes:** a new check that depends on formulas or rules goes into `ExpressionDependencies` (and reads `FormulaAnalysis`); a new function goes into `Functions` (guide section 12.6); a new kind of definition
  follows the recipe of Sprint 12 (a table, three `Change.Kind`s, `ChangeApplier` and `ChangeCodec`, a contributor, a schema or checks).

## Records: how one is stored, how to add a table that holds tenant records, how to read and write (Sprint 14)

Decisions in ADR-0084 to ADR-0090; the benchmark that chose the shape is `benchmark/` (ADR-0082, ADR-0083).

**How a record is stored.** One tenant-scoped table, `object_record` (V036): `tenant_id` (defaulted from the session, row level security forced), `object_api_name` (from the metadata catalogue, never changes),
`id` (a version 7 UUID made by the database), `owner_id` (a user), `record_type` (null until Sprint 15), `payload` (a JSON object of the values keyed by field API name; a field without a value has no key), the version, the base
columns and the soft delete (`deleted_at`, `deleted_by`). Eleven typed "slot" columns (`s_text_1`, `s_num_1`, `s_date_1`, `s_ref_1`, `s_bool_1` and the others) are empty until Sprint 16 copies the value of an indexed field into one and indexes
it: with forced row level security an index on the JSON field operator is not usable (ADR-0083), so indexes live on plain typed columns. Live rows, the recycle bin and the age of the bin have a partial index each, tenant first.

**What guards a record in the database, whatever the application does:** the tenant guard (a row never changes organization), the identity guard (a record never changes object), the row guard (the identifier and creation data are immutable, every
update raises the version by exactly one, a record in the recycle bin is read-only until restored), the shape checks (an API-name-shaped object and record type, a JSON object of at most 512 KiB), and forced row level security (a statement that forgets the
organization sees nothing, not everything).

**How to read and write records: through `Records` only.**

- Another module that needs records (a workflow action, an integration mapping) injects `app.platform.data.Records` and calls it. It gets the permission check of the signed-in member, the checks of a write, the audit record and the event, in the transaction
  of the call. It never injects `StorageStrategy`, never writes SQL against `object_record`, and never reads the table of another module (a standing rule).
- The tenant context must be open **before** the transaction begins (as everywhere); `Records` throws `ApiException` (`NOT_FOUND` for an object the caller may not read, `FORBIDDEN`, `VALIDATION_ERROR`, `CONFLICT`, `CONCURRENT_MODIFICATION`).
- The values are a map of field API name to a text, a number (a `BigDecimal` is kept exactly), a boolean or a list of those. Do not put a value in a log, an audit record, an event or an error message.
- Changing storage (a typed table for one object, say) means a second `StorageStrategy` behind `Records`; nothing outside the `data` module changes.

**How to add a table that holds tenant records** (for a store other than the generic one, or an association table such as the many-to-many junctions of Sprint 16):

1. A migration `V<nnn>` with `tenant_id not null default platform_current_tenant()`, the base columns and the `platform_row_guard`, the tenant guard, row level security **enabled and forced** with the four policies of V036, a tenant-first index, and partial unique indexes.
2. An entry in `TenantScopedTables` (name and a way to insert one valid row with an explicit tenant) so that `TenantIsolationIT` probes it; the schema scanner fails the build if a table with a `tenant_id` is missing there.
3. Statements that are constants with parameters only, and that filter by `tenant_id` from the tenant context in addition to row level security (`JsonbRecordStorage` is the model); a test with a hostile object and field name.
4. If the table feeds an answer of `Permissions` or `Decisions`, a security version trigger (Sprint 9); records do not.
5. If it holds the records of an object, `RecordObjectUsage` must count it (or the object could be removed with records in it).

**The clean-up of the recycle bin** (`RecycleBinRetention`) works organization by organization in that organization's own tenant context and its own transaction; it uses no system scope. Settings: `platform.data.retention.recycle-bin` (30d),
`interval` (1h), `batch-size` (500), `enabled` (true), and `platform.data.limits.max-record-bytes` (262144).

**Tests:** `RecordsApiIT`, `RecordsAccessIT` (allowed, denied, cross-tenant, forged header, platform administrator refused, unauthenticated for every endpoint), `RecordsConcurrencyIT` (one request, eight at once, two real instances, a delete
racing a change), `RecordStorageIT` (hostile names, the organization filtered by the store itself, the database guards), `RecordUsageIT` and `RollbackWithRecordsIT` (an object or field in use is not removed), `RecordEventsAndAuditIT`,
`RecycleBinRetentionIT`, `RecordFlowsLogsAreCleanIT`, `RecordValuesTest`; the isolation harness lists `object_record`.

## The write pipeline, field security and auto-numbers (Sprint 15)

Short form; decisions in [ADR-0091](adr/0091-the-write-pipeline-and-its-order.md) to [ADR-0097](adr/0097-links-between-records-on-write.md).

**Writing a record.** `DefaultRecords` runs `RecordGate` (who, object, permission), then `WritePipeline`, then the storage. The stages are `RecordValues` (shape), `RecordTypeCheck`, `FieldSecurity.checkWrite`, `FieldValue.check`
(type), the required check, the link check (`StorageStrategy.liveIds`, one question per target object), `Calculations.rules` and `Calculations.formulas`; defaults and auto-numbers are applied on create. A stage reports every
problem it finds in a `Findings`, and the pipeline stops after the first stage that found any. **Add a new check** as a step of the right stage, with its place and a message that never repeats a value; add a case to
`WritePipelineStagesIT` for its position in the order.

**Reading a record.** Everything handed back by `DefaultRecords` passes `FieldSecurity.visible(record, access)` with the `ObjectAccess` asked once per request. **Never return a `DataRecord` from `storage` to a caller without it.**

**The seam for indexes.** `RecordSlots.copyIndexed(object, values)` is called after the last stage and before the storage; Sprint 16 plugs the index policy in there (ADR-0091).

**Adding a stored shape for a field type** means a case in `FieldValue.check`, `defaultOf`, `toEngine` and `fromEngine`, the same rules in `FieldRules` (metadata) for the default, and a row in `FieldValueTest` and in
`PipelineSupport.CASES` (the 19-type matrix of `FieldSecurityMatrixIT` then covers read and write for it).

**Tables.** `auto_number_counter` (V037) is a tenant table like `object_record`: it is in `TenantScopedTables` and `TenantIsolationIT` probes it. Take a number only through `AutoNumbers.next`, in the transaction of the create, as
the last step before the insert.

**Tests (Sprint 15).** `WritePipelineStagesIT` (the order, defaults, required, rules, formulas, a record reads back as written), `FieldSecurityMatrixIT` (19 types by three kinds of member: edit, read, none; every path),
`RecordTypesOnRecordsIT`, `RecordLinksIT`, `AutoNumberIT` (two real instances), `RecordsSetupChangeIT`, `RecordPipelineLogsAreCleanIT`, `RecordSlotsIT`; unit: `FieldValueTest`, `FieldSecurityTest`,
`RecordTypeCheckTest`, `WritePipelineOrderTest`, `FieldWritePropertyTest` (jqwik); the isolation harness lists `auto_number_counter`.
