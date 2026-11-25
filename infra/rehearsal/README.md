# Production-like rehearsal on one machine

What it is: the built images and the `prod` profile behind an ingress that behaves like the cloud load balancer, with a migration job
that runs first, several application replicas (each with its edge sidecar beside it, as in a task), and the scripts that release,
roll back, scale, interrupt and rotate them under load. All in Docker, separate from `infra/local`. It answers, before any cloud
account is paid for: does a release with live traffic drop requests, does the previous image run on the new schema, do the ingress
rules hold, do keys rotate without a logout, does a restore work, does the pool survive a database that goes away.
It does **not** prove anything about a cloud provider's own services (see `docs/production-transition-plan.md`, section 5, step 1).
Decisions behind it: ADR-0286 to ADR-0295; the numbers of a release: ADR-0289.

## One-time set-up

1. **Credentials and keys** (throw-away, ignored by git): done by `./up.ps1` (it runs `./init.ps1`).
2. **Build the images** from `platform/` (stop the local backend first; a running jar locks the file). The web image runs
   `next build` inside Docker, which does not touch the dev server's folders:
   ```
   ./mvnw -B -ntp -DskipTests package
   ./infra/rehearsal/build-images.ps1 -Tag local
   ```
3. **Names:** the scripts need no change to your system (they resolve the names themselves). To open the rehearsal in a browser,
   add these lines to `C:\Windows\System32\drivers\etc\hosts` in an **administrator** PowerShell:
   ```
   127.0.0.1 platform.test
   127.0.0.1 tenant-a.platform.test
   ```
   and trust the rehearsal's certificate authority once: `certutil -addstore -user Root .\.run\root.crt`.

## The scripts (each prints PASS or FAIL; the exit code is 0 only when everything passed)

| Script | What it proves |
|--------|----------------|
| `./up.ps1 [-Replicas 2]` | A clean environment starts the way a deployment starts: data services, the application role (M001), **the migration job first**, then the replicas behind the ingress, then a seeded organization. `./down.ps1 [-Forget]` stops it |
| `./checks.ps1` | https and HSTS, the management port unreachable, a **forged `X-Forwarded-Host`** changes nothing, a **forged `X-Forwarded-For`** does not choose the source, every size limit holds (announced and unannounced length), a schema that is behind refuses to start |
| `./build-images.ps1 -Tag next` then `./release.ps1` | **The exit test in miniature:** a rolling release (migration job with the new image, each replica replaced, drained, stopped) under ~100 requests a second with **zero failed requests** and a p95 within 2x of the steady state |
| `./build-previous-image.ps1` then `./rollback.ps1` | The **previous** image runs on the schema the new release migrated, during a rolling release, with no failed request |
| `./scale.ps1` | The same at 1, 2 and 3 replicas: no failures, every replica serves, sign-ins work across replicas, the sign-in limit is shared |
| `./db-interrupt.ps1` | The database goes away for 12 seconds in the middle of load: readiness turns false, the pool recovers with no restart, requests are served again |
| `./rotate-signing-key.ps1` | The signing key is replaced in three releases under load with no token refused that should work |
| `./rotate-kek.ps1` | The key of the secrets store is replaced as `runbooks/secrets.md` says, under load |
| `./restore.ps1` | A dump restored into a clean database, timed; the application starts on it |

The load generator is `load/ReleaseLoad.java`, one file the JDK runs, outside the build and outside CI (like `benchmark/limits`).
**The pass rule is written in ADR-0289 and is never moved after a number is seen:** zero failed requests (a connection error, a
timeout, a 5xx or a 401), and a release-time p95 of at most 2 times the steady-state p95.

## What stands in for what

| In a deployment | In the rehearsal |
|-----------------|------------------|
| Load balancer: wildcard certificate, host-based routing, health checks, deregistration | `ingress` (Caddy, `Caddyfile`): the rehearsal's own authority, one-second health checks, **no retry** (a cloud balancer does not retry), X-Forwarded-For **appended** and X-Forwarded-Host **passed through** |
| The edge sidecar of every task (`deploy/edge`) | The **same image and the same rules file** (`deploy/edge/api-rules.caddy`), one beside every application replica, in the replica's network |
| Container service rolling a service | `Invoke-RollingRelease` in `lib.ps1`: new beside old, wait ready, stop one old, repeat (min healthy 100%, max 200%) |
| Secret store handing secrets to a task | Environment variables on the container; the key files go through the image's entry point (`PLATFORM_KEYFILE_B64__...`), as in a deployment |
| Managed database, cache, object store, mail provider | PostgreSQL 18, Redis 8, an S3-compatible container, a mail catcher (`http://localhost:8026`) |

## Not in the rehearsal

A real certificate, a real secret store, a database with a standby and its failover time, the provider's backups and point-in-time
recovery, a real mail provider, the fixed outbound address. Those are the staging environment's job and the owner's manual steps
(`Sprint 33 manual steps.md`).

## How the rehearsal is built, and what to know before changing it

- **The ingress has fixed slots, not DNS names.** Replicas are given the aliases `backend-1` to `backend-8` and `web-1` to `web-8`, and the
  ingress health-checks every slot actively. A proxy that resolves names without health checks sends traffic to a replica that is still
  starting; the first rolling release failed that way (502s) before this was changed. The pass rule of the release was not touched.
- **Only the ingress is a trusted hop.** It has the fixed address `172.31.77.250` on a fixed subnet, and the edge trusts that one address
  (`$script:TrustedHop` in `lib.ps1`). Trusting the whole network would also trust the host gateway, and a forged forwarded-for would pass.
- **The previous image is built from `git archive`,** into a folder of the rehearsal's own, so no branch, worktree or stash is created or
  changed (`build-previous-image.ps1`). It runs Maven and Docker and takes a few minutes.
- **A load generator can outlive an aborted run.** `Stop-StrayLoads` ends them at the start of a run; a result taken while one was still
  running is not trusted. Limits are reset (`Reset-Limits`) at the start of the checks and of every load run, because the sign-in limiter
  is shared state and works as intended on a burst.
- **Size limits are decimal.** The proxy's `MB` is 1,000,000 bytes, not 1,048,576; the size tests use the decimal figure.
