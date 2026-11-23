# ADR-0270: Scopes and the object list of a connected application (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md), [ADR-0249](0249-the-access-seam-for-scopes-no-cross-origin-rule-and-what-sprint-30-leaves-out.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md)

## Decision

1. **Three scopes, and only three, in V1:** `objects.read` (list objects and describe their fields; reads no record),
   `records.read` (read one record, query records) and `records.write` (create, change, delete, restore, bulk). They are the
   scopes the closed table of public operations (`PublicApiAccess.OPERATIONS`) already names, kept in one catalogue
   (`ApiScopes` in the shared kernel, read by `ClientScopes` and by the screens through `GET /api/v1/connected-apps/catalogue`),
   and a test holds the two to one list so that a scope cannot exist on one side alone. The browser holds no list of scopes.
2. **`records.write` does not imply `records.read`.** An application that may only write holds no right to read anything back
   beyond the answer of the write itself. A scope never implies another.
3. **Scope is necessary, never sufficient.** A scope lets a request reach an operation; it never decides what the member may do
   with an object, a field or a record. That is the member's, by the same code as for the screens (ADR-0267).
4. **What a token carries.** The scopes of a machine token are in the attribute `api_scopes` (not the standard `scope` claim of
   the web application's sign-in). `PublicApiAccessFilter` answers an operation whose scope is missing with the ordinary
   **403**. A person's own token has no `api_scopes` and still reaches the whole table, as in Sprint 30.
5. **What a request asks for.** The scopes of a token are the requested ones that the application holds. **An empty request
   means every scope the application holds** (`ScopedClientCredentialsProvider`); a request beyond what it holds is
   `invalid_scope` (400).
6. **An optional list of objects per application.** Absent means every object the member may reach; present means only those.
   An object outside the list answers **exactly like an object that does not exist**: 404 `NOT_FOUND` in the words `This object
   does not exist.`, whatever the member may do, and the list of objects shows only the listed ones. It is enforced in one place
   at the door of the public API (`PublicApiInterceptor`, through the shared-kernel `ConnectedAppCaller`), before any
   controller runs, so no operation can forget it.
7. **Why 403 for a scope and 404 for an object are kept apart.** A missing scope is a fact about the application's own
   registration, which its administrator can read; telling the integrator "you were not given this" costs nothing and is what
   the documentation lists operation by operation. An object outside the list is a fact about the organization's data: saying
   that it is excluded would confirm that it exists, which the probing rule of ADR-0244 item 12 forbids. The two answers
   therefore stay different on purpose.
8. **Scope and the list only ever narrow.** Narrowing a registration ends the tokens already issued (ADR-0269) and, because
   the registration is read at every request, no token can keep a right the registration has lost.

## Consequences

- A new operation of the public API is a row of `PublicApiAccess.OPERATIONS` with one of these scopes; a new scope needs an ADR.
- The object list does not check that an object exists when it is written (an object may be created later).
