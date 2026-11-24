# ADR-0291: Environments, the matrix, outbound addresses, and what staging may do (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0235](0235-protocol-coverage-soap-and-what-follows.md), [ADR-0274](0274-what-a-webhook-subscription-is-and-where-it-lives.md), [ADR-0290](0290-secrets-keys-and-the-object-store-role-in-a-deployment.md), [ADR-0292](0292-infrastructure-as-code.md)

## Decision

1. **Environments:** `staging` and `production` in code, plus the rehearsal on one machine (not a cloud environment). Developers keep
   localhost. A `test` environment is not made.
2. **The matrix.** Each environment has its own: database and database roles, cache, object-store bucket, secret store entries,
   token signing key pair, key-encryption key, mail sending identity and sender address, platform domain and certificate, network,
   outbound addresses, state of the infrastructure tool, alarm topic, budget and log groups. **No credential and no network path is
   shared.** The one shared thing is the image registry, an artifact store that environments only read (an image is built once and
   promoted); it holds no data and no credential of any environment.
3. **Staging is a true copy of production in code:** the same files, one values file each. The two values files set exactly the same
   variables (a test fails otherwise). They differ in sizes and in the few settings that make staging safe to test in. **No file of
   one environment names a resource of the other** (`EnvironmentIsolationTest`: every identifying value differs, no value mentions
   the other environment, no module names an environment, the two states are separate buckets and objects, the lock is on).
   Production is hard to destroy: deletion protection on the database and the balancer, a final snapshot, a standby, backups kept
   for fourteen days; the object store is never force-destroyed. Staging may be destroyed and rebuilt.
4. **What staging may do.** It **never mails a real person:** every mail goes to a mail catcher that runs in the environment (the
   application sends to it by a private name; its screen is shown only to the owner's address range by a rule of the balancer).
   It **never calls a real customer address:** `PLATFORM_WEBHOOKS_ENABLED` and the two outbound switches
   (`PLATFORM_INTEGRATION_RELIABILITY_ENABLED`, `PLATFORM_INTEGRATION_FILES_ENABLED`) are false, so nothing is queued or sent. A
   tester who needs a webhook or a connector runs a receiver of their own and switches the setting on for the test, knowingly. The
   rejected alternative, an allow-list of recipients, would need a feature in the mail module and is not a protection if the list
   is wrong; a catcher cannot be wrong.
5. **A fixed outbound address.** Webhook, connector and file-transfer calls leave from the application's private network through a
   NAT gateway with a fixed address; those addresses are an operator fact, listed in `runbooks/outbound-addresses.md` and read from
   the infrastructure output. Production uses one gateway (one address to tell a customer; if its zone is lost, outbound calls
   wait in their queues and nothing is lost); two (one per zone, two addresses) is a one-line change with a runbook note. The
   address rules of the application refuse every private range (so the deployment's own network) and the cloud's metadata
   addresses; `platform.integration.egress.development-hosts` stays empty and the `prod` profile refuses to start when it is not
   (`ProdRefusesDevelopmentHostsTest`).
6. **The values files hold no secret and no personal address:** the alarm recipient is given when the plan is made
   (`TF_VAR_alarm_email`); domains are placeholders until the owner replaces them (marked `CHANGE-ME`).

## Consequences

- A new environment is a new values file pair and a new state object; `runbooks/environments.md` lists the steps.
- Staging cannot prove that a real mail provider or a real customer's receiver works; production's first mail and first webhook do,
  with the go-live checks.
