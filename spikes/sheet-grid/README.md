# Sheet grid spike (FA0)

Throwaway prototype and measurements for the Audit maintenance cluster's design decisions (ADR-0172 to ADR-0180). **Not part of the build and not part of CI.** Nothing here is product code; the product's
grid is written in FA3 from what this spike decided.

## What is here

| Folder or file | What it is |
|---|---|
| `lib/model.mjs`, `lib/values.mjs`, `lib/gridOps.mjs` | the canonical grid model, the rules for a cell's value, and the pure edit operations (merge, bold, paste, undo patches) |
| `schema/sheet-grid.schema.json`, `schema/validate.mjs`, `schema/run-corpus.mjs` | the schema file, the validator, the 34-case hostile corpus |
| `components/OwnGrid.jsx` | our own table-based grid with virtual rows and columns |
| `components/*Client.jsx`, `adapters/` | the three library candidates and the adapters between their formats and ours |
| `app/` | a small Next.js app with one page per candidate (`/own`, `/univer`, `/fortune`, `/jspreadsheet`, `/baseline`) |
| `run-spike.mjs`, `run/drivers.mjs`, `probe.mjs`, `phone-check.mjs`, `ssr-check.mjs` | the browser measurements and checks |
| `pg/measure.mjs` | the PostgreSQL 18 measurements (throwaway schema `fa0_spike` in the local database) |
| `results/` | the numbers and screenshots the ADRs quote (`browser-full-run.json`, `pg-*.json`, `dark-*.png`, `csp-*.png`, `phone-own.png`) |

## Run it again

You need JDK-free tools only: Node 22.12 or newer (Node 24 used), a Chromium-based browser (Chrome or Edge; set `CHROME_PATH` if it is not in the usual place), and for the database part the local services of the platform
(`cd infra/local; ./init-env.ps1; docker compose up -d; ./init-app-role.ps1`).

```
cd platform/spikes/sheet-grid
npm ci
npm test                  # the grid operations and the adapter round trips (no browser, a few seconds)
npm run corpus            # the hostile corpus against the validator
npm run build             # production build of the spike app (copies the design tokens first)
npm run probe             # does every candidate start, what does it log and request
npm run spike             # the whole browser matrix: sizes, scenario, fill view, dark mode, accessibility, policies (about 15 minutes)
npm run phone             # the own grid on a phone-sized touch screen
npm run ssr               # what happens when a candidate is imported where there is no window
node pg/measure.mjs all   # PostgreSQL: setup, baseline, templates, history, compress, fills (about 5 minutes; loads 365,000 fill-ins)
FILL_TEMPLATES=3 node pg/measure.mjs fills   # the volume load as it was run for the ADRs (1,095,000 fill-ins, about 6 GB of disk)
node pg/measure.mjs cleanup                  # drops the throwaway schema; run it when you are done
```

Options of `npm run spike`: `--only own,univer`, `--sizes S,M` (S 20x8, M 60x15, L 150x30, X 500x100, XX 1000x200), `--skip-checks`. A run writes `results/browser-results.json`; the committed `browser-full-run.json` is the run the ADRs quote.
Do not use the machine for anything heavy while the matrix runs: the timings are real ones and are disturbed by a busy machine.

## Things to know

- The spike styles itself with the platform's design tokens (`app/tokens.css` is a git-ignored copy refreshed by `npm run build`).
- The three libraries are devDependency-like candidates only; none of them goes into the product (ADR-0172).
- The proxy in `lib/serve.mjs` sets the content security policy per run (none, relaxed, strict) in front of the production build.
- Library pages are client-only. The key presses are paced like a person's (the libraries register keys after a programmatic selection only once their own state has settled; the first version of the script had no pause and blamed the libraries for it).
- `pg/measure.mjs` runs as the application role `platform_app` with the tenant setting (`app.current_tenant`) in each transaction, so forced row level security applies. It reads the local credentials from `infra/local/.env` and never prints them.

## POC 1 (2026-11-12): the spreadsheet engine proof of concept

Added on top of the FA0 harness; the FA0 results in `results/` were measured with Univer 1.1.0 and are **kept unchanged**. This folder now pins Univer **1.4.0** and three of its presets (conditional formatting, find and replace,
data validation); to repeat the FA0 matrix exactly, restore `package.json` and `package-lock.json` from the commit before POC 1.

| File | What |
|---|---|
| `components/Poc1Univer.jsx`, `app/poc1/univer/page.jsx`, `adapters/poc1Univer.mjs` | Univer under test: a real workbook (fixture) or a synthetic sheet of fixed, filler and reviewer cells, with the guards |
| `components/Poc1OwnGrid.jsx`, `app/poc1/own/page.jsx`, `lib/poc1Closed.mjs`, `scripts/poc1-gen-css.mjs`, `app/poc1-own.css` | our own grid, extended (closed lists, styles, frozen header, conditional colour, formulas) |
| `lib/poc1Formula.mjs`, `lib/poc1Formula.test.mjs` | the evaluator and the translators for formulas stored by identifier |
| `lib/poc1Layout.mjs` | the random layouts of cell classes |
| `poc1-univer-basic.mjs`, `poc1-univer-formulas.mjs`, `poc1-univer-classes.mjs`, `poc1-extras.mjs`, `poc1-closed-lists.mjs` | the measurements; they print counts and times only |
| `poc1-serve.mjs` | serves the pages and the fixtures for looking by hand |

The two real workbooks are **never** in this folder. The Java spike writes a neutral JSON of each (`ExportNeutral`) into a folder you choose (`POC1_DATA`); it holds the owner's text and must stay outside the repository.
Commands: `docs/spikes/spreadsheet-engine-spike.md`, section 5.

## POC 2 additions (fidelity and the fill-in model)

| File | What |
|---|---|
| `components/Poc2OwnGrid.jsx`, `app/poc2/own/page.jsx`, `app/poc2-own.css`, `scripts/poc2-gen-css.mjs` | the own grid with a tab bar for several sheets, three looks (a: POC 1 closed lists, b: wider closed lists, c: any checked colour), and the check boxes, Yes/No pairs and embedded-file places the extras reader found |
| `lib/poc2Look.mjs`, `poc2-look.mjs` | the candidate looks (written before measuring) and the distance of each to the two real forms |
| `poc2-look-shots.mjs`, `results/poc2-look.json` | every tab of both forms drawn three ways under the strict style policy (violations, colours against the file), and the pictures written OUTSIDE the repository |
| `lib/poc2Formula.mjs`, `lib/poc2Formula.test.mjs`, `lib/poc2-formula-cases.txt` | the larger function set, the growing range `@{COL,*}`, and 225 cases shared with the Java side (POI) |
| `poc2-serve.mjs` | serves the pages and the fixtures (including `plant-extras.json`, positions only) for looking by hand |

Commands: `docs/spikes/spreadsheet-fidelity-spike.md`, section 5.
