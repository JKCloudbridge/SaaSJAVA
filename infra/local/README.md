# Local development environment

Five containers that stand in for the platform's runtime dependencies (plus an optional sixth, the application itself).
Everything binds to `127.0.0.1` only.

| Service | Purpose | Host address (default) |
|---------|---------|------------------------|
| `postgres` | PostgreSQL 18 database | `localhost:5432`, database and user from `.env` |
| `redis` | Redis 8 cache (password protected) | `localhost:6379` |
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
own role (`APP_DB_USER` in `.env`, created by `./init-app-role.ps1`) and runs the outbox relay. Redis, object storage and the
mail catcher are not used yet. Three ways to run it, all against these services:

1. **As a jar or from the IDE** (the usual loop). Build once with `./mvnw -DskipTests package` in the repository root, then
   `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local`. The `local` profile reads
   `infra/local/.env` directly (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `POSTGRES_PORT`, `APP_DB_USER`, `APP_DB_PASSWORD`,
   `TRACING_COLLECTOR_PORT`),
   so nothing needs to be exported; real environment variables win over the file. The first start migrates the database.
2. **As the container image**, closest to a deployment: build the jar as above, then
   `docker build -t platform-app:local platform-app` and `docker compose --profile app up -d` here. It runs the `prod`
   profile with the settings passed in the compose file.
3. **With the web frontend**: `cd ../../platform-web; npm ci; npm run dev`, then open `http://tenant-a.localhost:3000` (or
   `tenant-b`; any name ending in `.localhost` reaches this machine in a browser). The `local` profile creates both
   organizations at start-up, and the page header shows the organization the address belongs to; `http://localhost:3000`
   addresses none. The browser talks only to that address; the dev server forwards `/api` to the application (passing the
   original host name, which the `local` profile trusts) and `/telemetry` to the trace viewer.

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
