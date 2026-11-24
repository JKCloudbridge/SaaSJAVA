# Runbook: environments

Sprint 33 (decisions in [ADR-0291](../adr/0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md) and
[ADR-0292](../adr/0292-infrastructure-as-code.md)). This page is the committed list of every setting a deployment gives the
application; a test (`ConfigurationDocumentedTest`) fails when a setting the application cannot start without is missing here or
from the deployment files, or when a deployment file names a variable the application does not read (a misspelled variable is never
an error at run time: the setting silently takes its default, and a default is chosen for a laptop).

## When to use this

To see what an environment needs, to make a new one, or to find out why one differs from another.

## The matrix: what each environment has of its own

| | staging | production | the rehearsal (one machine) |
|---|---|---|---|
| Network, database, roles, cache, bucket | its own | its own | containers |
| Secret store entries, signing key pair, key-encryption key | its own | its own | throw-away files |
| Platform domain, wildcard certificate | its own | its own | `platform.test`, the rehearsal's authority |
| Mail | a mail catcher, never a real person | the real provider, a verified sending domain | a mail catcher |
| Webhooks and connectors | off | on | off by not having a receiver |
| Database | single zone, small | standby in a second zone, point-in-time recovery, deletion protection, final snapshot | one container |
| Outbound address | its own fixed address | its own fixed address | none |
| Infrastructure state | its own object, its own bucket | its own object, its own bucket | none |
| Image registry | shared, read only (an image is built once and promoted) | shared, read only | local images |

## Every setting

Mandatory means the application does not start without it. *Secret* means the value is in the environment's secret store and
reaches the container as a secret; everything else is written in the values file or computed by the infrastructure files.

| Variable | Used by | Meaning |
|----------|---------|---------|
| `PLATFORM_DB_URL` | application, migration job | JDBC URL of PostgreSQL; add `sslmode=require` (or stronger) in a deployment. Mandatory |
| `PLATFORM_DB_APP_USER`, `PLATFORM_DB_APP_PASSWORD` | application | The role of manual migration M001. Mandatory; the password is *secret* and is set out of band |
| `PLATFORM_DB_OWNER_USER`, `PLATFORM_DB_OWNER_PASSWORD` | migration job only (and an application that migrates on start) | The owner role. Mandatory only while the process migrates; the application of a deployment never has them (`PLATFORM_MIGRATE_ON_START` false). The password is *secret* (kept by the database service) |
| `PLATFORM_MIGRATE_ON_START` | application | `true` (default): the instance migrates, as the owner. A deployment sets `false`: the migration job ran first and the application only checks the schema (ADR-0288) |
| `PLATFORM_BASE_DOMAIN` | application | The platform domain; organizations live at `<slug>.<domain>`. Mandatory |
| `PLATFORM_PUBLIC_BASE_URL` | application | `https://<platform domain>`, no path; the links in mails. Mandatory |
| `PLATFORM_ENVIRONMENT` | application | The label on every metric (`staging`, `production`) |
| `PLATFORM_TRUST_FORWARDED_HOST` | application | **false behind the cloud balancer** (it preserves `Host`, which is the tenant, and never sets this header: ADR-0286). True only behind an ingress that overwrites it |
| `PLATFORM_TRUST_FORWARDED_FOR` | application | true in a deployment, with `PLATFORM_FORWARDED_FOR_HOPS` |
| `PLATFORM_FORWARDED_FOR_HOPS` | application | How many trusted proxies appended an entry (1 behind the balancer): the entry that many places from the right becomes the caller's address (ADR-0286). Default 0 elsewhere |
| `PLATFORM_HTTP_PORT`, `PLATFORM_MANAGEMENT_PORT` | application | API port (8080; 8090 in a task, behind its edge sidecar) and probes and metrics port (8081, never routed) |
| `PLATFORM_SHUTDOWN_DELAY` | application | How long a stopping instance keeps serving after it says "not ready" (10 s; ADR-0289) |
| `PLATFORM_SHUTDOWN_TIMEOUT` | application | How long one phase of a stop may take (30 s; ADR-0289) |
| `PLATFORM_REDIS_HOST`, `PLATFORM_REDIS_PORT`, `PLATFORM_REDIS_PASSWORD` | application | The cache for the limits. The host is mandatory; the password is *secret*; encryption in transit is `SPRING_DATA_REDIS_SSL_ENABLED` |
| `PLATFORM_SMTP_HOST`, `PLATFORM_SMTP_PORT`, `PLATFORM_SMTP_USER`, `PLATFORM_SMTP_PASSWORD` | application | The mail server. The host is mandatory; user and password are *secret*; a staging environment sends to its catcher |
| `PLATFORM_SMTP_AUTH`, `PLATFORM_SMTP_STARTTLS_REQUIRED` | application | Login and encryption of the connection; switched off only for a catcher |
| `PLATFORM_MAIL_SENDER` | application | The address mails come from, under a domain verified with the mail provider. Mandatory |
| `PLATFORM_STORAGE_BUCKET` | application | The files bucket. Mandatory |
| `PLATFORM_STORAGE_CREDENTIALS` | application | `keys` (default) or `role` (a role attached to the container: no key and no secret exist; ADR-0290). A deployment uses `role` |
| `PLATFORM_STORAGE_ACCESS_KEY`, `PLATFORM_STORAGE_SECRET_KEY`, `PLATFORM_STORAGE_ENDPOINT`, `PLATFORM_STORAGE_REGION`, `PLATFORM_STORAGE_PATH_STYLE` | application | Only with `keys`, or for a store that is not the provider's own |
| `PLATFORM_SIGNING_KEY_ID`, `PLATFORM_SIGNING_PRIVATE_KEY_FILE`, `PLATFORM_SIGNING_PUBLIC_KEY_FILE` | application | The token signing key pair, as files (made from `PLATFORM_KEYFILE__<name>` secrets by the image's entry point). Mandatory in a deployment |
| `PLATFORM_IDENTITY_SIGNING_KEYS_<n>_ID`, `..._PRIVATE_KEY_FILE`, `..._PUBLIC_KEY_FILE` | application | The same for several keys at once (a rotation): both keys in this form |
| `PLATFORM_KEYFILE__<name>`, `PLATFORM_KEYFILE_B64__<name>`, `PLATFORM_KEYFILE_DIR` | the image's entry point | A secret written to a private file and removed from the environment; the Base64 form for a platform that cannot carry line breaks |
| `PLATFORM_SECRETS_KEK_ACTIVE_ID`, `PLATFORM_SECRETS_KEK_ACTIVE_KEY` | application | The key-encryption key of the secrets store: the identifier, and the key as a *secret*. Mandatory (the application does not start without a valid one) |
| `PLATFORM_SECRETS_KEK_PREVIOUS_<n>_ID`, `..._KEY` | application | Older keys, while one is being replaced |
| `PLATFORM_WEBHOOKS_ENABLED`, `PLATFORM_INTEGRATION_RELIABILITY_ENABLED`, `PLATFORM_INTEGRATION_FILES_ENABLED` | application | Outbound switches; false in staging |
| `PLATFORM_SHEET_FORMAT_TWO_ENABLED` | application | Keep false until the sheet engine is released |
| `PLATFORM_TRACE_EXPORT`, `PLATFORM_TRACE_ENDPOINT`, `PLATFORM_TRACE_SAMPLING` | application | Spans: off by default |
| `PLATFORM_BUILD_COMMIT`, `PLATFORM_BUILD_TIME` | image | The release stamp, set by the image build, never a secret |
| `WEB_SHUTDOWN_DELAY`, `PLATFORM_API_ORIGIN`, `PLATFORM_TRACE_COLLECTOR_ORIGIN` | web server | The web server's own delay while it drains (10 s); where the development rewrites go (unused behind the ingress) |

The full list with the rest of the application's tunables (limits, workers, retention) is in `platform/docs/engineering-guide.md`;
those all have safe defaults.

## Making a new environment

1. Copy a values file pair under `deploy/infra/values` (`<name>.tfvars`, `<name>.backend.hcl`); change every name that identifies a
   resource; a test refuses an environment that shares one with another.
2. Run `deploy/infra/bootstrap` again if it should have its own state bucket; plan, read, apply (`deploy/README.md`).
3. Put the secret values into the store, run M001 and M002, run the migration job, start the services (`Sprint 33 manual steps.md`).

## How I know it worked

The tests above pass; `tofu plan` for the new environment shows only the resources of that environment; the smoke checks of
[deploy.md](deploy.md) pass against it.
