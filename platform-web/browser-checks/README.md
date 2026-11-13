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

**UI3A addition:** the script now has a section 12 (the list views of the Templates list): the title row holds the view menu, New, the search box and the settings icon with no description under the title; a typed search and a click on a header are asked of the server; the settings menu offers Select fields to display and Filter and no Delete; a field moved out of the selected box leaves the table; a view is saved, opens again after a reload and is removed. Like the rest it is written and checked for syntax only; it was **not run**. Run it with the same command as above (`npm run sheet-grid`).

**UI3B addition:** the script now has a section 13 (a version made from a spreadsheet file): a fresh template with no version is started from a small text file ("Start from a spreadsheet"), the preview says what the file gives and nothing is saved yet, Save makes the draft with the file's columns and rows, the grid shows the column names, a row is made taller by dragging its bottom edge and saved, and the list of versions names the person who last changed it. The workbook (.xlsx) side of the reader is proved by the tests of the server with workbooks made in memory; to try a real workbook by hand follow `Manual tests/Sprint UI3B manual testing.md`. Like the rest it is written and checked for syntax only; it was **not run**. Run it with the same command as above (`npm run sheet-grid`).

**FA6A change:** section 9 (the publish dialog) now uses the tick boxes: no department box is offered before a location is chosen, the departments of the location are tick boxes, **Production** and **Quality** of Plant 1 are ticked and added together with one press, they show ticked and disabled, and one **Publish** publishes both (the script makes a second group and department record for Quality). Section 10 ticks Production again. Like the rest it is written and checked for syntax only; it was **not run**. Run it with the same command as above (`npm run sheet-grid`).

## The application grid and the dashboard (Functional track FA9, ADR-0203)

`npm run application-grid` signs in as the seeded administrator and checks: the start address draws the grid (twenty-two or more tiles, with the five new ones first and Environment Management sixth in the API's order) and stays `/`, the four announced new applications open on their Home without scrolling the page sideways, Supplier Portal says it is being prepared, Document Management opens the dashboard and its Document Control tab under its own name with its own browser tab title, the `/` key focuses the search, a search without a match says so, placeholder applications carry the "Coming soon" tag, the Quality Management System opens at its Home with nine tiles, the page does not scroll sideways at 1280 px, a placeholder tab says it is being prepared, the three lines lead back to the grid, and the grid fits a phone (390 px). Same set-up and options as above; it only reads (it makes nothing). **Status:** written and checked for syntax only; it was **not run**. The `process-applications`, `inspection` and the five new features (`document-management`, `capa`, `audit-management`, `supplier-management`, `management-core`) and `environment-management` must be on for `tenant-a` (the `local` profile does this).

## The look of a sheet (Functional track SH4, ADR-0256)

`npm run sheet-look` (same set-up and options as the sheet grid script; the switch for format version 2 must be on, as in the `local` profile) makes a template with the largest sheet the limits allow in format version 2 (500 rows by 100 columns, a look on every cell, two fixed rows and one fixed column, conditional colours) and another shaped like a 59-column data sheet, and checks the thresholds written before any measurement: T1 first paint, T2 scrolling, T3 cells in the page, T4 an arrow key to the next paint, T5 memory, T7 the data sheet's first paint, T8 the fixed rows and column after a long scroll, T9 no content-policy violation (strict policy, light and dark, print view open), T11 the focus ring and the selection over a dark fill, a light fill and white, a phone, a half-width window, and the dark theme keeping the paper white. With `--workbooks` it also uploads the owner's two workbooks (read from bytes; the paths are `PLANT_WORKBOOK` and `SPM_WORKBOOK`, by default in Downloads), checks T12 (every drawn cell takes the stored fill and text colour) and saves pictures (light, dark and the print view) to `PICTURES_DIR`, a folder outside the repository. It makes templates in `tenant-a` each run and deletes nothing. **Status:** run in SH4 against the real backend and a production build; see `Sprint SH4.md` for the numbers. The sheet it makes has the full 500 x 100 size but a look on about one cell in twelve (a look on every cell exceeds the 1,000,000 byte limit). It asks for a fresh forgery token before every write.
