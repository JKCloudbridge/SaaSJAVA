# ADR-0295: What Sprint 33 leaves out (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0286](0286-deployment-topology-the-ingress-and-the-forwarded-headers.md) to [ADR-0294](0294-operating-it-alerts-logs-and-backups.md), [ADR-0215](0215-what-sprint-27-leaves-out.md)

## Left out, on purpose, with where it goes

| Left out | Why, and where it goes |
|----------|------------------------|
| **Creating the real cloud environment** | Only the owner can (accounts, domain, certificate, money). The files are written and validated; `Sprint 33 manual steps.md` is the ordered list. Nothing was applied, created or changed in any cloud account |
| **Claiming a cloud environment works** | Not claimed. Proved: the images, the rehearsal, the procedures. Written: the files. Owner-only: the cloud |
| **Load and soak tests, tuning, capacity numbers** | Sprint 35. Only the rolling release's own proof (zero failed requests) is built here |
| **Measuring recovery time and data loss** | Sprint 35. Backups are switched on and a restore is rehearsed by script; nothing is measured against the cloud |
| **Security review, penetration test, more scanning gates than CI has** | Sprint 34 |
| **Multi-factor sign-in for platform roles** | A launch gate delivered by Sprint 34 at the latest; the go-live checklist says a deployment without it is not public |
| **User-delegated applications** | Sprint 31A, before Sprint 34 |
| **An orchestrator, a service mesh, multi-region, a CDN** | The container service is enough; multi-region and a CDN are decisions for after launch |
| **A web application firewall, edge rate limits beyond the application's own** | The size and time limits are in the edge sidecar; the limits are the application's (Sprint 32) |
| **A plan in CI** | CI holds no cloud credentials; a read-only plan job is a later decision, apply stays the owner's |
| **Custom-code execution** | Sprint 35's design spike (decision D6) |
| **Billing, a status page** | Not planned for the first release |
| **An error-tracking tool** | Decision 8 of the transition plan, to be taken before staging; the hook is unchanged |
| **Blue/green releases** | Rolling with expand/contract is enough ([ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)) |
| **An outbox lag gauge** | The alert uses dead letters and the workflow event delay; a gauge can be added with its alert |
| **A rollback across the boundary of this sprint** | A release from before the migration job needs its own old environment shape; the first rollback target is a release that has the job |

## Things this sprint changed that are not infrastructure

- `platform.deployment.forwarded-for-hops` and the normalizer (a security fix for an appending balancer, deployment-only), the
  worker drain (one number, one helper), `platform.storage.credentials`, `PLATFORM_MIGRATE_ON_START`, migration V097, the module
  `deployment`, a `/healthz` that drains. None changes a default of `local` or the tests.
