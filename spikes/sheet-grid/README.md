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
