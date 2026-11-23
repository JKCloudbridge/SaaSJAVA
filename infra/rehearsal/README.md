# Production-like rehearsal on one machine

What it is: the built images and the `prod` profile behind an ingress with https and fake domain names, all in Docker, separate from
`infra/local`. It answers, before any cloud account is paid for: do https cookies, forwarded-header handling, host routing, uploads and
the first start of a clean environment work the way a real deployment needs? It does **not** prove anything about a cloud provider's
load balancer or certificate (see `docs/production-transition-plan.md`, section 5, step 1).

## One-time set-up

1. **Credentials and keys** (throw-away, ignored by git): `./init.ps1`
2. **Names for this machine.** In an **administrator** PowerShell add these lines to `C:\Windows\System32\drivers\etc\hosts` (the file
   cannot hold a wildcard, so each organization you want to open needs a line):
   ```
   127.0.0.1 platform.test
   127.0.0.1 tenant-a.platform.test
   127.0.0.1 tenant-b.platform.test
   ```
3. **Build the two images** from `platform/` (stop the local backend first; a running jar locks the file):
   ```
   ./mvnw -B -ntp -DskipTests package
   docker build -t platform-app:local platform-app
   docker build -t platform-web:local platform-web
   ```
4. **Start:** `docker compose up -d` in this folder, then `docker compose ps` until `backend` and `web` are healthy
   (the backend takes about a minute: it runs the migrations first).
5. **Trust the rehearsal's certificate** (once). The ingress makes its own authority; copy its root certificate out and add it to
   the Windows "Trusted Root Certification Authorities":
   ```
   docker compose cp ingress:/data/caddy/pki/authorities/local/root.crt ./root.crt
   certutil -addstore -user Root .\root.crt
   ```
   Without this the browser shows a certificate warning; nothing else changes.

## First platform administrator and first organization

Sign up on `https://platform.test` with an address of your choice (mail arrives at `http://localhost:8026`), then, as the owner
role, give that account the platform role (manual migration M002, transition plan section 4.4):
```
docker compose exec -T postgres psql -U platform_owner -d platform -v ON_ERROR_STOP=1 -v admin_email=you@example.test -f - < ../../db/manual/M002__grant_first_platform_administrator.sql
```
Then create an organization from the platform address (`tenant-a`) and open `https://tenant-a.platform.test`.

## What to look at

- Sign in and out on the platform host and on an organization host; the cookies are `Secure`.
- Switch between `tenant-a` and `tenant-b` (open the second at `https://tenant-b.platform.test`).
- A file upload and a spreadsheet import (the ingress allows up to 30 MB on `/api`).
- `https://platform.test/actuator/health` answers 404 (the management port is not routed).
- A client-supplied header changes nothing:
  `curl --cacert root.crt https://tenant-a.platform.test/api/v1/session -H "X-Forwarded-Host: tenant-b.platform.test"`.
- The pages load from the production build (`docker compose logs web`).

## Stop and reset

`docker compose down` keeps the data; `docker compose down -v` forgets it (the role and the bucket are made again at the next start).

## Not in the rehearsal

A real mail provider (the catcher stands in), a real certificate, a secret store (a file and a variable stand in), a database with a
standby, backups. Those are the staging environment's job.
