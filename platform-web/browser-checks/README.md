# Browser checks of the web application

Scripts that drive the **production build** of `platform-web` in a real Chromium-based browser against a **running backend**, the way a person would. They are not part of the build and not part of CI: they need the local services, the backend, a build and a browser.

## The sheet grid (Functional track FA3, ADR-0172)

What it checks that a jsdom test cannot: only the cells on the screen are drawn at the largest size (500 rows by 100 columns), scrolling to the far corner draws it, typing, Ctrl+B, Ctrl+Z and a pasted block work in a real browser, moving around makes no request, the grid works under a strict content policy (no inline style refused), dark mode darkens it, a phone (390 px) fits, opens the form view and shows the grid read-only. It writes screenshots to `results/`.

1. Start the local services and the backend: `cd infra/local; ./init-env.ps1; docker compose up -d; ./init-app-role.ps1`, then `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local` (after `./mvnw -DskipTests package`).
2. Build and start the web application: `cd platform-web; npm ci; npm run build; npm start` (it proxies `/api` to the backend; the page is `http://tenant-a.localhost:3000`).
3. In another terminal: `cd platform-web/browser-checks; npm ci`, then set the password of the seeded administrator (`LOCAL_SEED_PASSWORD` in `infra/local/.env`) and run:

```
# PowerShell
$env:LOCAL_SEED_PASSWORD = "<the password from infra/local/.env>"
npm run sheet-grid
```

Options (environment): `BASE_URL` (default `http://tenant-a.localhost:3000`), `ADMIN_EMAIL` (default `admin-a@example.test`), `CHROME_PATH` (a Chromium-based browser; the usual places are tried).

It prints one line per check, `ok` or `FAIL`, and exits with 1 when any failed. It makes one template named "Browser check" in `tenant-a` each run; nothing is ever deleted (the template object keeps its records), so run it on a developer machine only.

**Status:** written in FA3 and checked for syntax only; it was **not run** in the sprint (it needs the services, the backend and a production build). Run it once and fix what it finds before relying on it.

**FF1 addition:** the script now has a section 11 (a fill-in): the template left published by section 10 is opened, **Start a fill-in** is pressed, a cell the template fixes is double-clicked and is not opened, an input cell takes a typed value, the fill-in is saved and read back through the API against version 1, and on a phone the fill-in opens in the form view without scrolling sideways. Like the rest it is written and checked for syntax only; it was **not run**.

**FA5 addition:** the script now has a section 10 (change requests): the template is published again, a column of the version in use is clicked, a comment is written and the request sent, the request is listed with the column marked in the grid, Enter on a cell takes the keyboard to the box of a comment, and the owner accepts the request. Like the rest it is written and checked for syntax only; it was **not run**.

**FA4 addition:** the script now has a section 9 (the master list with its filters, and the publish dialog: Location, then Department). Like the rest it is written and checked for syntax only; it was **not run**.
