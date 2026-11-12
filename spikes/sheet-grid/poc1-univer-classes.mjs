// POC 1, WP2 item 3: can a cell that a person may not change be changed in Univer 1.4.0?
// 11 operations on N random layouts of fixed (F), filler (P) and reviewer (R) cells, two roles, under four guard settings:
//   none      nothing installed (what Univer does by itself)
//   events    our FILTER guard: the one mutation that writes cell values is rewritten before it runs (forbidden cells removed); structural changes that touch a forbidden cell are cancelled
//   block     our BLOCK guard: the whole operation is cancelled when it touches a forbidden cell
//   builtin   Univer's own worksheet permission API: range protection on every forbidden cell
//   both
// Keyboard and clipboard are real wherever the operation has a keyboard path; the rest go through Univer's documented API (noted).
// Oracle: after the operation every forbidden cell must hold what it held before. Outcome per run: leak / blocked (nothing changed)
// / filtered (only permitted cells changed). A cell that changes counts as a leak even if it is put back later.
//   POC1_DATA=<folder>  node poc1-univer-classes.mjs [--seeds 20] [--guards none,events,builtin]
import { chromium } from "playwright-core";
import { existsSync, writeFileSync } from "node:fs";
import { startServer } from "./lib/serve.mjs";
import { layoutOf, forbiddenSet, mayChange, ROWS, COLS } from "./lib/poc1Layout.mjs";

const arg = (n, d) => (process.argv.includes(n) ? process.argv[process.argv.indexOf(n) + 1] : d);
const SEEDS = Number(arg("--seeds", 20));
const ONLY = arg("--only", "");
const GUARDS = arg("--guards", "none,events,block,builtin").split(",");
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const OPS = [
  "type into a forbidden cell", "type into a permitted cell (must still work)", "Delete over a mixed block", "cut a mixed block and paste",
  "copy a mixed block and paste over a mixed block", "paste text from outside over a mixed block", "drag-fill over a mixed block (API)",
  "undo and redo", "find and replace (API)", "delete a row that holds forbidden cells (API)", "merge a mixed block (API)",
];

async function snapshot(page) {
  return page.evaluate(([R, C]) => {
    const sh = window.__poc.api.getActiveWorkbook().getActiveSheet();
    const vals = sh.getRange(0, 0, R + 4, C).getValues().map((row) => row.map((v) => (v === null || v === undefined ? "" : String(v))));
    let merges = 0; try { merges = sh.getMergedRanges?.().length ?? 0; } catch { /* */ }
    return { vals, merges };
  }, [ROWS, COLS]);
}

async function focusGrid(page) {
  const p = await page.evaluate(() => { let best = null, area = 0; for (const c of document.querySelectorAll("canvas")) { const r = c.getBoundingClientRect(); const a = r.width * r.height; if (a > area) { area = a; best = r; } } return { x: best.x + best.width - 40, y: best.y + best.height - 30 }; });
  await page.mouse.click(p.x, p.y);
}
async function select(page, r0, c0, r1, c1) {
  await focusGrid(page);
  await page.evaluate(([r0, c0, r1, c1]) => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(r0, c0, r1 - r0 + 1, c1 - c0 + 1).activate(); }, [r0, c0, r1, c1]);
  await sleep(250);
}

/** Runs one operation; returns an error text when the operation itself could not be tried. */
async function run(page, op, grid, role) {
  const forb = [...forbiddenSet(grid, role)].map((k) => k.split(",").map(Number));
  const perm = [];
  grid.forEach((row, r) => row.forEach((cls, c) => { if (mayChange(role, cls)) perm.push([r, c]); }));
  switch (op) {
    case OPS[0]: { const [r, c] = forb[0]; await select(page, r, c, r, c); await page.keyboard.type("ZZ", { delay: 30 }); await page.keyboard.press("Enter"); break; }
    case OPS[1]: { const [r, c] = perm[0]; await select(page, r, c, r, c); await page.keyboard.type("ZZ", { delay: 30 }); await page.keyboard.press("Enter"); break; }
    case OPS[2]: await select(page, 0, 0, 1, 2); await page.keyboard.press("Delete"); break;
    case OPS[3]: await select(page, 0, 0, 1, 2); await page.keyboard.press("Control+x"); await sleep(200); await select(page, 3, 0, 3, 0); await page.keyboard.press("Control+v"); break;
    case OPS[4]: await select(page, 0, 0, 1, 2); await page.keyboard.press("Control+c"); await sleep(200); await select(page, 4, 0, 4, 0); await page.keyboard.press("Control+v"); break;
    case OPS[5]: await select(page, 2, 0, 3, 2); await page.evaluate(async () => { await navigator.clipboard.writeText("a1\tb1\tc1\na2\tb2\tc2"); }); await page.keyboard.press("Control+v"); break;
    case OPS[6]: await page.evaluate(() => { const sh = window.__poc.api.getActiveWorkbook().getActiveSheet(); sh.getRange(0, 0, 2, 3).autoFill(sh.getRange(0, 0, 6, 3)); }); break;
    case OPS[7]: { const [r, c] = perm[0]; await select(page, r, c, r, c); await page.keyboard.type("QQ", { delay: 30 }); await page.keyboard.press("Enter"); await sleep(200); await page.keyboard.press("Control+z"); await sleep(200); await page.keyboard.press("Control+y"); await sleep(200); await page.keyboard.press("Control+z"); break; }
    case OPS[8]: await page.evaluate(async () => { const f = await window.__poc.api.createTextFinderAsync("v"); await f.findAll(); await f.replaceAllWithAsync("X"); }); break;
    case OPS[9]: { const r = forb[0][0]; await page.evaluate((r) => { window.__poc.api.getActiveWorkbook().getActiveSheet().deleteRow(r); }, r); break; }
    case OPS[10]: await page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(0, 0, 2, 3).merge(); }); break;
    default: throw new Error("unknown operation");
  }
  await sleep(400);
}

function classify(before, after, forbidden) {
  let leak = 0, permittedChanged = 0;
  for (let r = 0; r < before.vals.length; r++) for (let c = 0; c < COLS; c++) {
    if (before.vals[r]?.[c] !== after.vals[r]?.[c]) {
      if (forbidden.has(`${r},${c}`)) leak++; else permittedChanged++;
    }
  }
  if (after.merges > before.merges) leak++; // a merge over a forbidden cell hides its content: count it
  return leak ? "leak" : permittedChanged ? "filtered" : "blocked";
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const server = await startServer({ port: 3100, csp: "relaxed" });
const results = {};
const t0 = Date.now();
const jobs = [];
for (const guard of GUARDS) {
  results[guard] = {};
  for (const op of OPS) results[guard][op] = { leak: 0, blocked: 0, filtered: 0, error: 0, sample: [] };
  const seeds = guard === "none" ? Math.min(SEEDS, 8) : SEEDS;
  for (let seed = 1; seed <= seeds; seed++) for (const op of OPS) if (!ONLY || op.includes(ONLY)) jobs.push({ guard, seed, op });
}
async function runJob({ guard, seed, op }) {
  const role = seed % 2 ? "filler" : "reviewer";
  const grid = layoutOf(seed);
  const forbidden = forbiddenSet(grid, role);
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin: server.origin });
  const page = await ctx.newPage();
  try {
    await page.goto(`${server.origin}/poc1/univer?classes=${seed}&role=${role}&guard=${guard}&full=1`);
    await page.waitForFunction(() => window.__poc?.ready === true, null, { timeout: 60000 });
    if (guard === "builtin" || guard === "both") { results[guard].builtinSetup = await page.evaluate(() => window.__poc.builtin); }
    await sleep(300);
    const before = await snapshot(page);
    await run(page, op, grid, role);
    const after = await snapshot(page);
    const outcome = classify(before, after, forbidden);
    results[guard][op][outcome]++;
    if (outcome === "leak" && results[guard][op].sample.length < 2) results[guard][op].sample.push({ seed, role });
  } catch (e) {
    results[guard][op].error++;
    if (results[guard][op].sample.length < 2) results[guard][op].sample.push({ seed, role, error: String(e.message).split("\n")[0].slice(0, 90) });
  }
  await ctx.close();
}
try {
  const WORKERS = 4;
  let next = 0, done = 0;
  await Promise.all(Array.from({ length: WORKERS }, async () => {
    while (next < jobs.length) { const j = jobs[next++]; await runJob(j); if (++done % 40 === 0) console.log(`${done}/${jobs.length} runs, ${Math.round((Date.now() - t0) / 1000)} s`); }
  }));
} finally {
  writeFileSync(new URL(ONLY ? "./results/poc1-univer-classes-only.json" : "./results/poc1-univer-classes.json", import.meta.url), JSON.stringify(results, null, 2));
  await browser.close();
  server.stop();
}
for (const g of GUARDS) {
  console.log(`\nGUARD ${g}${results[g].builtinSetup ? " (" + results[g].builtinSetup + ")" : ""}`);
  for (const op of OPS) { const r = results[g][op]; console.log(`  ${op.padEnd(52)} leak ${String(r.leak).padStart(2)}  blocked ${String(r.blocked).padStart(2)}  filtered ${String(r.filtered).padStart(2)}  not-run ${r.error}`); }
}
