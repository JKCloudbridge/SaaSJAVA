# ADR-0001: Modular monolith with Spring Modulith

- **Status:** Accepted (module list extended by ADR-0011 and ADR-0012: `web`, `observability`; and by ADR-0016: `outbox`)
- **Date:** 2026-10-01
- **Sprint:** S0

## Context

The platform has many bounded contexts (tenancy, identity, security, metadata, data, workflow,
integration, ...). Splitting into services now would add distributed-systems cost (network failure,
distributed transactions, per-service deployment) before the boundaries are proven. An unstructured
single application would let those boundaries erode within months. We need one deployable unit whose
internal boundaries are enforced by the build, so modules can be extracted later if measurements justify it.

## Decision

1. The backend is **one Spring Boot application** (`platform-app`), deployed as one unit.
2. The logical modules are the direct sub-packages of `app.platform`, managed with **Spring Modulith**:
   `tenant, identity, licensing, security, metadata, data, application, workflow, approval,
   notification, integration, audit, platformadmin` (the platform-admin module) and `sharedkernel`.
3. A module's **public API is the types in its root package**; sub-packages are internal and
   unreachable from other modules.
4. Every module declares its **allowed outgoing dependencies** in its `package-info.java`
   (`@ApplicationModule(allowedDependencies = ...)`). The initial graph is in [../modules.md](../modules.md).
   Adding an edge is a reviewed change to that file and to this ADR's table.
5. Modules communicate through public APIs, and (from Sprint 2) through domain events delivered by the
   transactional outbox. **No module reads or writes another module's tables.**
6. **Maven modules are few and are not the same thing as logical modules** (see ADR-0004):
   `platform-app`, `platform-shared-kernel`, `platform-api-contract`.
7. Only the `integration` module may know a specific external system's adapter (package
   `app.platform.integration.adapter`). Core modules never reference it.

## Consequences

- One deployment, one transaction boundary, simple debugging; horizontal scaling by running more
  identical instances (stateless).
- Boundaries cost discipline: a convenient shortcut across modules fails the build.
- Extraction of a module into a service later is possible because its only coupling is a public API and events.
- **Enforcement (automated, runs in CI):**
  - `ModuleStructureTest` verifies the module list, declared dependencies, encapsulation and cycles.
  - `ArchitectureCanFailTest` proves the check is not vacuous: it runs the same verification against a
    deliberately broken layout and requires each kind of violation to be reported.
  - `PlatformRulesTest` adds cross-cutting rules (no adapter access outside `integration`, shared kernel and
    API contract independence, no field injection, no standard streams).
  - Demonstrated in Sprint 0 on the real code: a temporary `tenant -> data` reference failed the build with
    "Module 'tenant' depends on module 'data' ... Allowed targets: sharedkernel", and was then removed.

## Alternatives considered

- **Microservices from the start:** rejected; premature, slows delivery, boundaries not yet proven.
- **Unstructured monolith:** rejected; boundaries would not survive.
- **One Maven module per logical module:** rejected for now; heavy build and release overhead without
  adding enforcement beyond what Modulith already gives. Can be revisited per module on extraction.

## References

- Delivery plan, Sprint S0 and "Standing architecture rules".
- Code: `platform-app/src/main/java/app/platform/*/package-info.java`,
  `platform-app/src/test/java/app/platform/architecture/`.

## Update (Sprint 2)

The module list gains one infrastructure module, `outbox` (transactional outbox and relay, allowed dependencies `tenant` and
`observability`; [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md)). The `tenant` module is built
([ADR-0014](0014-tenancy-model-and-tenant-context.md)). Business modules use the event contracts of the shared kernel and
never depend on `outbox`. The edge is recorded in `docs/modules.md`.
