# Local development environment

Four containers that stand in for the platform's runtime dependencies. Everything binds to `127.0.0.1` only.

| Service | Purpose | Host address (default) |
|---------|---------|------------------------|
| `postgres` | PostgreSQL 18 database | `localhost:5432`, database and user from `.env` |
| `redis` | Redis 8 cache (password protected) | `localhost:6379` |
| `object-storage` | S3-compatible object storage | `http://localhost:8333` |
| `mail-catcher` | Receives all outgoing mail; nothing leaves your machine | SMTP `localhost:1025`, web UI `http://localhost:8025` |

Image names and versions are in [docker-compose.yml](docker-compose.yml) (the only place they appear).

## First time

1. Install a container runtime with Compose and start it (on Windows: the desktop application, with its engine running).
2. From this folder, create your private credentials file (random, local-only, never committed):

   ```powershell
   ./init-env.ps1
   ```

3. Start everything and wait until all four report `healthy`:

   ```powershell
   docker compose up -d
   docker compose ps
   ```

4. Optional but recommended, a functional check that goes beyond "the port is open":

   ```powershell
   ./verify.ps1
   ```

   It runs a SQL query with row level security, authenticates against Redis (and proves an unauthenticated
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

The application does not use these services yet (the empty application needs none). From Sprint 1 it reads
connection settings from environment variables whose values come from `.env`:
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `REDIS_PASSWORD`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`.

## Troubleshooting

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
