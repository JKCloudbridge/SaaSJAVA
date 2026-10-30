# ADR-0209: The identity of an integration, its permissions and tenant isolation

- **Status:** Accepted (development decision of Sprint 26)
- **Date:** 2026-10-30
- **Sprint:** S26
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0006](0006-no-static-api-keys.md), [ADR-0035](0035-controlled-support-access.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0122](0122-bulk-record-operations.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md)

## Decision

### Three mechanisms, kept apart

| Mechanism | Question | Where |
|-----------|----------|-------|
| Being a member | Is there a door at all for this caller in this organization? | `IntegrationAccess`: the caller must have a membership of the organization the host names; a platform person who is not a member is "not found" |
| Ability | May this member do this? | `integrations.view` (see), `integrations.manage` (create, change, switch on or off, remove; needs view too), `integrations.test` (call the other system once; needs view too) |
| Plan feature | Did the organization buy it? | the existing `integrations` feature (V015, in the trial plan); without it every endpoint answers `409` with fixed words. A new feature was not needed, so the prompt's question 2 has its answer in the catalogue already |

Seeing, testing and managing are three abilities because they are three different risks: a test makes the platform call out
of its network, and managing decides where records go and what the connection may touch. The administrator profile holds
every ability; nobody else does until an administrator gives them.

### The identity an integration acts under

**A dedicated identity per integration, never the creator's rights.** The identity of a connection is its **identifier**
(`integration_connection.id`, returned as `id`). It is not a user and not a member: it holds no licence, has no sign-in, no
password, no session and no token (no second login mechanism; machine clients arrive in Sprint 31, ADR-0006). What it may
touch is **only its stated scope**, an explicit list stored with the connection: an object and the things it may do with it
(`read`, `create`, `update`; never delete). The scope is the whole of its rights; the rights of the person who set it up are
never copied, and removing that person changes nothing.

`Integrations.allows(connection, object, action)` answers from the stored scope and **only while the connection is
`ENABLED`** (a connection that is off, failing, untested or removed has no rights at all, at the next question, on every
instance: nothing is cached). `Integrations.subscribersOf(event, object)` answers which switched-on connections listen for
an event. Both are the small public contract of the module; neither names an external system.

**The Sprint 8 model applied.** The scope can only **narrow**. Sprint 28 will call `allows` *before* it calls the record
store, and the record store still applies its own gate (object permission, field security, record security) with the
integration as a **named, licence-free actor** — a source like `BackgroundWrites` (ADR-0139) and never a private path to
records (ADR-0122). What the gate needs to decide *field* and *record* rights for an actor that is not a member is part of
that sprint's design, because it is only exercised there; Sprint 26 stores the object-level scope and the identity so that
the design has something to hang on. This is a decision, not a placeholder: no code in Sprint 26 reads or writes a record
on behalf of a connection.

**Audit.** Every change is a record `integration.created|updated|enabled|disabled|removed|tested` written in the
transaction of the change by the signed-in person, with the connection's identifier, the connector type key and counts
(events, scope entries); a test also carries the stable reason and the resulting state. Never an address, a label, a
credential name or a header. The record is visible to the organization (and not to platform people, which is the default
for a kind no rule names when it is about an organization).

**Support access.** Integration settings are never read on behalf of the platform: no platform endpoint exists, and a platform
person's request to an organization host fails at the membership check (`404`). If a platform endpoint is ever added it must
call `SupportAccess.require(organization, person)` first; the access test `aPlatformAdministratorHasNoDoorHere...` keeps the
current state.

### Tenant isolation

`integration_connection` is **tenant-scoped**: `tenant_id not null default platform_current_tenant()`, forced row level
security with the four policies, the tenant guard trigger, a tenant-first partial unique index on the name, a tenant-first
index on the state, and an entry in the isolation harness (`TenantScopedTables.INTEGRATION_CONNECTION`). Every statement in
`ConnectionStore` names the tenant as well, so the application filter and the database agree (ADR-0003). The secret reference
is resolved in the organization's own scope only (ADR-0208).

### Inbound tenant identification, designed now (built in Sprint 28)

An inbound call arrives before any tenant is known and must never name one. The table already carries **`inbound_id`**: a
random, unguessable 32-character identity (`replace(gen_random_uuid()::text, '-', '')`, unique across the platform among live
rows, never changed, never returned by the API today). Sprint 28's receiver will resolve `inbound_id` to the connection and,
through it, to the organization, using a **read-only system scope** that returns exactly that pair (the pattern of
`membership_lookup`, ADR-0026 to ADR-0029), then check the signature with the connection's credential and run the rest of
the request in that organization's context. Because the column exists, Sprint 28 needs no contract migration. Nothing
reads the column in Sprint 26.

## Consequences

- A connection can be given rights nobody else has been given, so the ability to manage integrations is as sensitive as the
  ability to manage access; it is not part of the member profile.
- There is no per-field or per-record scope yet. A connection that may `read` an object reads what the record store's gate
  lets the integration actor read when Sprint 28 builds that gate; until then it reads nothing.
