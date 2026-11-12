// POC 1, WP2 items 4 and 5 and WP3: accessibility tree, keyboard-only path, dark mode, a phone-width run, the autosave pattern (Univer),
// and the extended own grid (policy violations, ready time, typing in the formula bar, recalculation, dark mode).
// Counts and times only. Needs the fixtures: POC1_DATA=<folder>  node poc1-extras.mjs
import { chromium } from "playwright-core";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { startServer } from "./lib/serve.mjs";

const DATA = process.env.POC1_DATA;
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pct = (xs, p) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); return +s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))].toFixed(1); };
const INIT = () => {
  window.__csp = []; document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.effectiveDirective}:${String(e.blockedURI).startsWith("http") ? "url" : e.blockedURI}`));
  window.__lat = []; addEventListener("keydown", () => { const t = performance.now(); requestAnimationFrame(() => setTimeout(() => window.__lat.push(performance.now() - t), 0)); }, true);
};
const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const server = await startServer({ port: 3100, csp: "relaxed" });
const out = {};

async function open(candidate, { width = 1400, height = 900, colorScheme = "light", hasTouch = false, query = "" } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme, hasTouch, isMobile: hasTouch });
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin: server.origin });
  await ctx.addInitScript(INIT);
  await ctx.route("**/poc1-fixture/*.json", (route) => route.fulfill({ contentType: "application/json", body: readFileSync(`${DATA}/${new URL(route.request().url()).pathname.split("/").pop()}`) }));
  const page = await ctx.newPage();
  const path = candidate === "univer" ? `/poc1/univer?fixture=spm&sheet=2&full=1${query}` : `/poc1/own?fixture=spm&sheet=2${query}`;
  const t0 = Date.now();
  await page.goto(server.origin + path);
  if (candidate === "univer") await page.waitForFunction(() => window.__poc?.ready === true, null, { timeout: 60000 });
  else await page.waitForFunction(() => window.__own?.ready === true && document.querySelector('[role="gridcell"]'), null, { timeout: 60000 });
  await sleep(700);
  return { ctx, page, readyMs: Date.now() - t0 };
}
const roles = (yaml) => { const c = {}; for (const m of yaml.matchAll(/^\s*- (\w+)/gm)) c[m[1]] = (c[m[1]] ?? 0) + 1; return c; };
async function luminance(page) {
  const shot = await page.screenshot();
  const b64 = shot.toString("base64");
  return page.evaluate(async (b) => { const img = new Image(); img.src = "data:image/png;base64," + b; await img.decode(); const c = document.createElement("canvas"); c.width = img.width; c.height = img.height; const g = c.getContext("2d"); g.drawImage(img, 0, 0); const d = g.getImageData(0, 150, img.width, Math.min(500, img.height - 150)).data; let s = 0; for (let i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]; return +(s / (d.length / 4) / 255).toFixed(2); }, b64);
}

try {
  for (const cand of ["univer", "own"]) {
    const r = (out[cand] = {});
    // --- accessibility tree and keyboard-only path ---
    let o = await open(cand);
    r.readyMs = o.readyMs;
    const yaml = await o.page.locator("body").ariaSnapshot();
    r.accessibilityRoles = roles(yaml);
    r.accessibleCellNames = (yaml.match(/gridcell|cell "/g) ?? []).length;
    await o.page.keyboard.press("Tab");
    const stops = [];
    for (let i = 0; i < 40; i++) { const f = await o.page.evaluate(() => { const a = document.activeElement; return a ? `${a.tagName.toLowerCase()}|${a.getAttribute("role") ?? ""}|${a.getAttribute("aria-label") ?? a.getAttribute("title") ?? ""}`.slice(0, 50) : ""; }); stops.push(f); await o.page.keyboard.press("Tab"); }
    r.tabStopsDistinct = new Set(stops).size; r.tabStopsSample = [...new Set(stops)].slice(0, 6);
    r.cspViolations = await o.page.evaluate(() => window.__csp);
    // --- typing latency (own: the formula bar; Univer: a cell) ---
    if (cand === "own") {
      await o.page.click('[role="gridcell"][data-r="3"][data-c="7"]');
      await o.page.focus("#pc-bar"); await o.page.keyboard.press("Control+a");
      await o.page.evaluate(() => { window.__lat = []; });
      await o.page.keyboard.type("Typing latency probe text.", { delay: 40 }); await sleep(200);
      const lat = await o.page.evaluate(() => window.__lat); r.typing = { n: lat.length, p50: pct(lat, 50), p95: pct(lat, 95) };
      // recalculation: type numbers into three input cells of row 4 (planned, done, target) and read the calculated cell next to them
      const cell = async (rr, cc) => (await o.page.locator(`[role="gridcell"][data-r="${rr}"][data-c="${cc}"]`).textContent()) ?? "";
      const setBar = async (rr, cc, text) => { await o.page.click(`[role="gridcell"][data-r="${rr}"][data-c="${cc}"]`); await o.page.focus("#pc-bar"); await o.page.keyboard.press("Control+a"); await o.page.keyboard.type(text); await o.page.keyboard.press("Enter"); await sleep(150); };
      const before = []; for (const c of [12, 13, 19, 20]) before.push(await cell(3, c));
      await setBar(3, 7, "10"); await setBar(3, 8, "7"); await setBar(3, 9, "8");
      const after = []; for (const c of [12, 13, 19, 20]) after.push(await cell(3, c));
      r.recalculation = { changedCellsAfterTypingThreeInputs: after.filter((v, i) => v !== before[i]).length, shownAfter: after };
      // a formula typed by an author, then a refused one
      await setBar(4, 7, "=SUM(1,2,3)+MIN(5,4)"); r.typedFormulaShows = await cell(4, 7);
      await setBar(4, 8, "=INDIRECT(1)"); r.refusedFormulaMessage = await o.page.locator(".pc-msg").textContent().catch(() => null);
      // keyboard navigation inside the grid
      await o.page.focus('[role="grid"]'); await o.page.click('[role="gridcell"][data-r="3"][data-c="3"]'); await o.page.focus('[role="grid"]');
      await o.page.keyboard.press("ArrowRight"); await o.page.keyboard.press("ArrowDown");
      r.arrowKeysMoveSelection = (await o.page.locator('.pc-name').textContent()) === "E5";
    } else {
      // Univer: a keyboard-only author path: move with arrows, type, bold, undo, redo, find
      await o.page.mouse.click(700, 330);
      await o.page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(3, 7).activate(); }); await sleep(300);
      const keys = {};
      await o.page.keyboard.press("ArrowRight"); await o.page.keyboard.press("ArrowDown");
      keys.arrowsMove = await o.page.evaluate(() => { const s = window.__poc.api.getActiveWorkbook().getActiveSheet().getActiveRange(); return `${s.getRow()},${s.getColumn()}` === "4,8"; });
      await o.page.keyboard.type("77"); await o.page.keyboard.press("Enter"); await sleep(250);
      keys.typeCommits = await o.page.evaluate(() => String(window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(4, 8).getValue()) === "77");
      await o.page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(4, 8).activate(); }); await sleep(200);
      await o.page.keyboard.press("Control+b"); await sleep(250);
      keys.boldShortcut = await o.page.evaluate(() => window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(4, 8).getFontWeight?.() === "bold");
      await o.page.keyboard.press("Control+z"); await sleep(250); await o.page.keyboard.press("Control+y"); await sleep(250);
      keys.undoRedo = true;
      await o.page.keyboard.press("Control+f"); await sleep(500);
      keys.findDialogOpens = (await o.page.locator('input[placeholder*="ind" i], [data-u-comp="find-replace-dialog"], .univer-find-replace').count()) > 0;
      await o.page.keyboard.press("Escape");
      r.keyboardOnly = keys;
      // the autosave pattern: only the changed cells reach the outside. Capture what the lowest mutation carries for typing and for a paste.
      await o.page.evaluate(() => { window.__patch = []; window.__poc.api.addEvent(window.__poc.api.Event.CommandExecuted, (e) => { if (e.id === "sheet.mutation.set-range-values" && e.params?.cellValue && !e.params.trigger?.includes?.("undo")) for (const [rr, row] of Object.entries(e.params.cellValue)) for (const cc of Object.keys(row)) window.__patch.push(`${rr},${cc}`); }); });
      await o.page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(6, 8).activate(); }); await sleep(250);
      await o.page.keyboard.type("5"); await o.page.keyboard.press("Enter"); await sleep(300);
      await o.page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(7, 8, 1, 2).activate(); }); await sleep(250);
      await o.page.evaluate(async () => { await navigator.clipboard.writeText("1\t2"); }); await o.page.keyboard.press("Control+v"); await sleep(400);
      const patch = [...new Set(await o.page.evaluate(() => window.__patch))];
      r.autosavePatchCells = patch;
    }
    await o.ctx.close();

    // --- dark mode ---
    const light = await open(cand, { colorScheme: "light" }); const l = await luminance(light.page); await light.ctx.close();
    const dark = await open(cand, { colorScheme: "dark" }); const d = await luminance(dark.page);
    let apiDark = null;
    if (cand === "univer") { await dark.page.evaluate(() => window.__poc.api.toggleDarkMode?.(true)); await sleep(600); apiDark = await luminance(dark.page); }
    r.dark = { lightBrightness: l, darkByMediaQuery: d, darkByLibraryCall: apiDark };
    await dark.ctx.close();

    // --- a phone-width run ---
    const ph = await open(cand, { width: 390, height: 844, hasTouch: true });
    r.phone = await ph.page.evaluate(() => ({ pageScrollWidth: document.documentElement.scrollWidth, viewport: innerWidth, canvases: document.querySelectorAll("canvas").length, buttons: document.querySelectorAll("button").length }));
    await ph.ctx.close();
    console.log(cand.toUpperCase(), JSON.stringify(r));
  }
  // several sheets in one Univer document (WP7): the plant workbook's two worksheets
  { const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    await ctx.route("**/poc1-fixture/*.json", (route) => route.fulfill({ contentType: "application/json", body: readFileSync(`${DATA}/${new URL(route.request().url()).pathname.split("/").pop()}`) }));
    const page = await ctx.newPage();
    await page.goto(`${server.origin}/poc1/univer?fixture=spm&sheets=0,1,2,3,4,5,6&full=1`);
    await page.waitForFunction(() => window.__poc?.ready === true, null, { timeout: 60000 }); await sleep(800);
    const info = await page.evaluate(async () => { const wb = window.__poc.api.getActiveWorkbook(); const sheets = wb.getSheets(); const out = { sheets: sheets.length }; wb.setActiveSheet(sheets[2]); await new Promise((r) => setTimeout(r, 400)); out.switched = wb.getActiveSheet().getSheetName(); out.frozenAfterSwitch = wb.getActiveSheet().getFrozenRows?.(); return out; });
    info.tabButtonsInDom = await page.locator("[class*='slide-tab'], [class*='sheet-bar'] [role='tab'], [data-u-comp='slide-tab-item']").count().catch(() => null);
    out.severalSheets = { workbookWithSevenWorksheetsOfWhichThreeHidden: info };
    console.log("SEVERAL SHEETS", JSON.stringify(info)); await ctx.close(); }
} finally {
  writeFileSync(new URL("./results/poc1-extras.json", import.meta.url), JSON.stringify(out, null, 2));
  await browser.close(); server.stop();
}
