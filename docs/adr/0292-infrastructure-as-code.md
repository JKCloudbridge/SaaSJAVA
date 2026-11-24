# ADR-0292: Infrastructure as code (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0286](0286-deployment-topology-the-ingress-and-the-forwarded-headers.md), [ADR-0291](0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md), [ADR-0293](0293-the-database-the-pool-and-failover.md)

## Decision

1. **The tool: Terraform syntax, run by OpenTofu or Terraform (the same files, the same commands).** The deciding property is a
   **plan step that shows what will change before anything changes**, which the owner reads. The files are small and boring on
   purpose. The transition plan names two tools; the other has no separate plan to read and ties the files to one provider's
   template format. Paid infrastructure software is not needed. The choice of the provider's services is the transition plan's
   (section 11); this ADR does not repeat provider names, and the literal resource types live inside `deploy/infra`.
2. **Layout (`platform/deploy`).** `infra/stack` is the one root module every environment uses; `infra/modules` has one folder
   per layer (network, data, secrets, edge, app, observability); `infra/values` has one values file and one state-backend file per
   environment; `infra/bootstrap` makes, once and with a local state, the two state buckets and the image registry; `edge` holds the
   sidecar image; `alerts` holds the one list of alarms. The first command is in `deploy/README.md`.
3. **Remote state with a lock.** One state object per environment, in its own private, encrypted, versioned bucket that refuses
   plain-text transport, locked by the tool's own lock file (no extra table). The cache password is the one secret that reaches a
   state; the bucket is read only by the owner.
4. **Layers, small:** a network with public, application and data subnets in two zones (the data subnets have no route out); the
   managed database (PostgreSQL 18, mandatory because the migrations use `uuidv7()`; multi-zone with automatic failover and
   point-in-time recovery in production; encrypted; TLS required; deletion protection and a final snapshot in production); the
   cache (password and encryption in transit); the files bucket (private, encrypted, versioned, no public access, TLS only);
   the container service with the load balancer, the wildcard certificate and the DNS names; the secret store entries (names and
   permissions only, never a value); logs, alarms, the database event subscription and the budget.
5. **What CI does and does not do.** CI formats and validates the files (no backend, no credentials) and runs the unit tests that
   hold the files to their rules ([ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md), [ADR-0291](0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md),
   [ADR-0294](0294-operating-it-alerts-logs-and-backups.md)). **CI never plans against a cloud and never applies;** it holds no cloud
   credentials. A plan is made and read by the owner, and an apply is run by the owner only. A later sprint may add a plan in CI
   with read-only credentials; apply stays the owner's.
6. **Destroying.** Staging can be destroyed and rebuilt (`tofu destroy` with its values). Production cannot be destroyed by one
   command: the database and the balancer have deletion protection (a separate, deliberate change to switch off), the database
   takes a final snapshot, the bucket refuses to be destroyed while it holds files.
7. **Nothing is applied by this sprint.** The sprint's claim is that the files are formatted and valid, and that the procedure is
   written and rehearsed. It does not claim a cloud environment exists or works.

## Consequences

- The values files carry `CHANGE-ME` markers for what only the owner can decide (region, domain, instance class after the benchmark,
  budget); the first plan fails fast until they are replaced.
- A new provider would mean new modules behind the same variables; the application and the rehearsal would not change.
