# ADR-0267: A connected application acts as the member who registers it (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0006](0006-no-static-api-keys.md), [ADR-0019](0019-authentication-implementation.md), [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md), [ADR-0268](0268-the-client-registry-and-the-secrets-of-a-connected-application.md)

## Context

Sprint 30 opened `/api/public/v1`, but the only key that fits it is the access token of a signed-in person. A customer's
software cannot sign in. Everything authorization is keyed on a member (`TenantContext` carries `userId` and
`membershipId`; `Permissions`, `Decisions`, `FieldSecurity`, record security, locks and the write pipeline all work from
that). The central question of machine access is therefore: **as whom does a connected application act?**

## Decision

1. **A connected application acts as the member who registers it, for ever.** The registration (`oauth_client`) stores that
   member's user and membership. Its token carries that member's subject and membership, so every permission, field right,
   record rule and lock applies exactly as for that person, and the scopes and the object list (ADR-0270) can only narrow.
   The exit criterion of the sprint, that a scope-restricted token cannot exceed the user who granted it, then holds by
   construction: there is no second place that decides what the application may do with a record.
2. **Authority never grows.** There is no field to name another member in a request to register, and the database refuses a
   change of the user or the membership of a registration (V095, `platform_oauth_client_guard`). A person cannot register an
   application that acts as somebody else, and nobody can change whom an application acts as: a new person registers a new
   application. An administrator with `clients.manage` may switch off, change within the rules, rotate and remove any
   application of the organization, but never change whom it acts as.
3. **When the member can no longer act, the application stops at once.** The token check already refuses a user who is not
   `ACTIVE` and a person who is not an active member of the organization of the host, on every request, so a suspended,
   deactivated or departed member stops the application at the next request (and ending a membership revokes the grants bound
   to the organization). The token endpoint refuses it too. The list shows it as **"stopped: its member is no longer
   active"** (`running` false, `stoppedReason` `member_not_active`), with the words from the catalogue on what to do.
   **Nothing is transferred automatically.** A person registers a new application as another member, gives the software the
   new identifier and secret, and removes the old one.
4. **The recommended practice, which is the reason a service identity is not built now: a dedicated integration member.**
   A customer registers an integration as an ordinary, licensed member that an administrator creates for the purpose (for
   example `integration-a@example.test`, with an access policy that gives only what the integration needs), not as a named
   employee. That member is the one the application acts as, so a staff change never stops the integration, and its rights
   are the ceiling. The registration form shows whom the application will act as, and says this; `docs/public-api.md` and
   the runbook say the same. **Owner's decision, 2026-11-23.**

## Alternatives considered

| Alternative | Why not now |
|---|---|
| A dedicated service identity: a user row that is not a person | Invitations, licences, sign-in, member listings, abilities, the last-access-manager rule and every screen that names a person would all have to learn about a non-person. A large surface for a problem the dedicated integration member already solves with what exists |
| "Run as a chosen member" | A path to privilege escalation: whoever may register could choose a more powerful member. Authority must not grow |

**Known future option, and what would trigger it:** a service identity is built when customers cannot create a dedicated
member (for example no licence to spare for one), or when "service" must be shown in listings and the audit viewer as a
kind of actor. Until then it is deliberately absent.

## Consequences

- One rule holds for permissions, field security, record security and locks: they are the member's. Nothing was added to
  `Permissions`, `Decisions` or `RecordVisibility`.
- A staff member who registers an application for a customer ties its life to their own; the guidance is the mitigation,
  and the registration form and the "stopped" tag make it visible.
- The member behind an application must hold a licence like any member; a member without abilities gives an application
  that can do nothing, by design.
