# Local development environment

Five containers that stand in for the platform's runtime dependencies (plus an optional sixth, the application itself).
Everything binds to `127.0.0.1` only.

| Service | Purpose | Host address (default) |
|---------|---------|------------------------|
| `postgres` | PostgreSQL 18 database | `localhost:5432`, database and user from `.env` |
| `redis` | Redis 8 (password protected): the sign-in rate limits. The security cache of Sprint 9 does not use it ([ADR-0053](../../docs/adr/0053-the-security-cache-and-its-version.md)) | `localhost:6379` |
| `object-storage` | S3-compatible object storage | `http://localhost:8333` |
| `mail-catcher` | Receives all outgoing mail; nothing leaves your machine | SMTP `localhost:1025`, web UI `http://localhost:8025` |
| `tracing` | Trace viewer: receives the traces of the browser app and the API and shows them (in memory, forgotten on restart) | collector `http://localhost:4318`, web UI `http://localhost:16686` |
| `app` (profile `app`, optional) | The platform application from its container image, wired to the services above | API `http://localhost:8080`, probes and metrics `http://localhost:8081` |

Image names and versions are in [docker-compose.yml](docker-compose.yml) (the only place they appear).

## First time

1. Install a container runtime with Compose and start it (on Windows: the desktop application, with its engine running).
2. From this folder, create your private credentials file (random, local-only, never committed):

   ```powershell
   ./init-env.ps1
   ```

3. Start everything and wait until the services report `healthy` (the trace viewer has no health check, it is up when its logs say so):

   ```powershell
   docker compose up -d
   docker compose ps
   ```

4. Create the application database role, once (it is repeatable). The application connects as its own role, which owns nothing
   and cannot bypass row level security; the local database user is the owner and runs the migrations. The script runs the
   manual migration `db/manual/M001__create_application_role.sql` as the owner and sets the role's password from `.env`
   (`APP_DB_PASSWORD`) through standard input, so it never shows on a command line:

   ```powershell
   ./init-app-role.ps1
   ```

   An existing `.env` from an earlier sprint gets the two new variables from `./init-env.ps1` (it only adds what is missing).

5. Optional but recommended, a functional check that goes beyond "the port is open":

   ```powershell
   ./verify.ps1
   ```

   It runs a SQL query with row level security, checks that the application role is unprivileged and that the tenant policy
   isolates (once the application has migrated the database), authenticates against Redis (and proves an unauthenticated
   command is refused), creates a bucket and uploads and downloads an object through the S3 API, and sends a
   mail that it then reads back from the catcher.

## Daily use

| Task | Command |
|------|---------|
| Start | `docker compose up -d` |
| Stop, keep data | `docker compose stop` |
| Stop and remove containers, keep data | `docker compose down` |
| Wipe all data (database, cache, objects) | `docker compose down -v` |
| Logs of one service | `docker compose logs -f postgres` |
| SQL prompt | `docker compose exec postgres psql -U platform -d platform` (use the values from `.env`) |
| Look at caught mail | open `http://localhost:8025` |

## Connecting the application

Since Sprint 1 the application uses PostgreSQL (and exports traces to the trace viewer); since Sprint 2 it connects as its
own role (`APP_DB_USER` in `.env`, created by `./init-app-role.ps1`) and runs the outbox relay; since Sprint 3 it also uses Redis
(sign-in rate limits shared across instances: `REDIS_PORT` and `REDIS_PASSWORD` in `.env`) and signs users in; since Sprint 4 it sends
e-mail to the mail catcher (`MAIL_SMTP_PORT` in `.env`, SMTP only; you read the messages at `http://localhost:8025`). Object storage is
not used yet. Three ways to run it, all against these services:

1. **As a jar or from the IDE** (the usual loop). Build once with `./mvnw -DskipTests package` in the repository root, then
   `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local`. The `local` profile reads
   `infra/local/.env` directly (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `POSTGRES_PORT`, `APP_DB_USER`, `APP_DB_PASSWORD`,
   `TRACING_COLLECTOR_PORT`, `REDIS_PORT`, `REDIS_PASSWORD`, `MAIL_SMTP_PORT`, `LOCAL_SEED_PASSWORD`),
   so nothing needs to be exported; real environment variables win over the file. The first start migrates the database.
2. **As the container image**, closest to a deployment: build the jar as above, then
   `docker build -t platform-app:local platform-app` and `docker compose --profile app up -d` here. It runs the `prod`
   profile with the settings passed in the compose file, plus Redis and, for this local container only, cookies without the Secure
   flag and a token signing key generated in memory (`PLATFORM_IDENTITY_COOKIES_SECURE=false`, `PLATFORM_IDENTITY_SIGNING_EPHEMERAL=true`;
   a deployment supplies real signing keys and never sets either).
3. **With the web frontend**: `cd ../../platform-web; npm ci; npm run dev`, then open `http://tenant-a.localhost:3000` (or
   `tenant-b`; any name ending in `.localhost` reaches this machine in a browser). The `local` profile creates both
   organizations at start-up, and the page header shows the organization the address belongs to; `http://localhost:3000`
   addresses none. **To sign in**, use the two local users the `local` profile creates at start-up: `user-a@example.test` and
   `admin-a@example.test`, both with the password `LOCAL_SEED_PASSWORD` from `.env` (`./init-env.ps1` generates it; it is never in a
   file of the repository). Open the **Sign in** link in the header. A signed-in session lives in cookies the page cannot read;
   `tenant-a` and `tenant-b` are separate sessions, and `http://localhost:3000` is the platform host (signing in works there too, with no
   organization). The browser talks only to that address; the dev server forwards `/api` to the application (passing the
   original host name, which the `local` profile trusts) and `/telemetry` to the trace viewer.

**Creating an account and an organization (Sprint 4).** On the platform host `http://localhost:3000`, **Create an account**, type an
address, and open the mail catcher at `http://localhost:8025`: the e-mail with the link arrives within a few seconds (the application sends
mail in the background, every two seconds). Open the link, choose your name and a password, sign in, and use **Create an organization** on
the home page; it gives you the address of the new organization (`<short name>.localhost:3000`). **Forgot your password?** works the same way.
**Inviting someone and switching organizations (Sprint 5).** Sign in on an organization's address (for example
`http://tenant-a.localhost:3000` as `admin-a@example.test`), open **Members**, type an address and **Send the invitation**. The e-mail
arrives in the mail catcher within a few seconds; the link opens a page on the platform host where a new person chooses a name and a
password, and a person who already has an account signs in and accepts. A person who belongs to two organizations sees a list in the
header and moves between them without typing the password again. Only administrators of an organization see its members; a person who is
not a member of an organization cannot sign in on its address. Invitation links live 7 days.

**Profiles, roles and access policies (Sprint 7).** What a member may do now comes from their **profile** (plus **access policies** and
abilities given directly), not from an administrator marker. Every organization has two system profiles, **Organization administrator**
(every ability) and **Member** (none, the default); `admin-a` holds the first, `user-a` the second. As `admin-a` open **Setup** (profiles,
access policies, roles) to create your own, and **Members** to create a member (name, address, profile, role; the person only chooses a
password) and to open a member's **Access**. A profile uses one licence of its type, so a new member shows "Waiting for a licence" if none is
free; a new local organization has 5 user and 2 admin licences (your own `tenant-a` and `tenant-b` have more user licences because they were
sized for their existing members when your database was migrated). Changing what a member holds takes effect at once. The organization
always keeps one member who can manage access.

**The platform console, licences and support access (Sprint 6).** The `local` profile also creates three platform people (no
organization, same password `LOCAL_SEED_PASSWORD`): `platform-a@example.test` (platform administrator), `support-a@example.test`
(support) and `billing-a@example.test` (billing). Sign in as one of them on the platform host `http://localhost:3000` and open
**Console**: organizations (open one to see its plan, licence numbers, features, the first-administrator invitation and the
lifecycle buttons), **Set up an organization** (a client's organization stays closed until its first administrator accepts the
mailed invitation; read the mail at `http://localhost:8025`), plans, platform people and sessions. A platform session lasts at most
4 hours and three wrong passwords lock a platform account for a minute. In a real environment there is no such account: the first
platform administrator is created by the manual step `db/manual/M002__grant_first_platform_administrator.sql` (see
`Sprint 6 manual steps.md`). On an organization's address, **Members** now shows the licence numbers, the licence of each member
(assign and take back) and a **Support access** section where an administrator approves, denies or ends a platform person's
request. The two local organizations start on a 30-day trial (5 user and 2 admin licences); the local users hold a licence.

Sign-up and reset pages exist only on the platform host. If the catcher is stopped, mail waits in the queue and is sent when it is back
(`docker compose stop mail-catcher`, then `docker compose start mail-catcher`). Limits (5 sign-ups per hour per source, 5 mails per hour per
address) apply here too; to start again within the hour, delete the counters in Redis (`platform:identity:acct:*`).

Open the trace viewer at `http://localhost:16686` after loading the home page: one trace shows the browser, the API and the
database statements. To see the same identifiers in the database log, set `POSTGRES_LOG_MIN_DURATION_MS=0` in `.env`
and run `docker compose up -d postgres`, then `docker compose logs postgres`: every statement of a request carries
`app=t=<trace id> r=<request id>`. (The default logs only statements slower than 500 ms.)

## Troubleshooting

- **`password authentication failed for user "platform_app"` or `role "platform_app" does not exist` when the application
  starts:** run `./init-app-role.ps1`. After a `docker compose down -v` the role is gone with the data; run it again.
- **`Migration checksum mismatch` or a history row that matches no file:** the local database remembers a migration that
  does not exist in the repository (for example from an experiment). Only on a developer machine, remove that history row, or
  wipe everything with `docker compose down -v`.
- **`run init-env.ps1 first`** when running compose: `.env` is missing. Run `./init-env.ps1`.
- **A port is already in use:** change the matching `*_PORT` value in `.env` and run `docker compose up -d` again.
- **Password changed in `.env` but the database still wants the old one:** the data volume remembers the
  first password. Either restore the old value or wipe the volumes with `docker compose down -v`.
- **A service stays `unhealthy`:** `docker compose logs <service>`. The object-storage check uses `127.0.0.1`
  on purpose (inside the container `localhost` may resolve to IPv6 while the service listens on IPv4).
- **The container engine will not start on Windows ("virtualization is not enabled"):** enable the Windows Virtual Machine
  Platform feature and virtualization in the firmware settings, then restart.
- **Integration tests do not use these containers.** They start their own throw-away PostgreSQL through the
  container runtime, so they work whether or not this environment is running.

## Rules

- These credentials are for local use only. Never reuse them anywhere else and never commit `.env`.
- Use only the `example.test` domain for test email addresses.

## Sample objects (Sprint 10)

The `local` profile gives each local organization one object of its own, made through the same tables the object manager uses: `Employee__c` (with an employee
number, a department picklist, a joining date and a salary) in `tenant-a`, and `Vehicle__c` (plate number, model, next service) in `tenant-b`. Sign in as
`admin-a@example.test` and open **Setup, Objects** to see them next to the standard objects, and the matrix editors at `/setup/profiles` and
`/setup/access-policies` list the same objects. The sample objects of Sprint 8 (`platform.security.sample-objects`) are gone. A deployment has none.
