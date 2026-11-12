// POC 1, WP2 item 6: formulas in Univer on the supplier data sheet. Compares Univer's own calculation with the value the spreadsheet program stored
// (the same numbers POI reproduced), then tries: typing a formula, adding a row, filling a formula down.
//   POC1_DATA=<folder>  node poc1-univer-formulas.mjs
import { chromium } from "playwright-core";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { startServer } from "./lib/serve.mjs";

const DATA = process.env.POC1_DATA;
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const neutral = JSON.parse(readFileSync(`${DATA}/spm.json`, "utf8"));
const sheetFix = neutral.sheets[2];
const formulas = sheetFix.cells.filter((c) => c.t === "f");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const server = await startServer({ port: 3100, csp: "relaxed" });
const out = {};
try {
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await ctx.route("**/poc1-fixture/*.json", (route) => route.fulfill({ contentType: "application/json", body: readFileSync(`${DATA}/${new URL(route.request().url()).pathname.split("/").pop()}`) }));
  const page = await ctx.newPage();
  await page.goto(`${server.origin}/poc1/univer?fixture=spm&sheet=2&full=1`);
  await page.waitForFunction(() => window.__poc?.ready === true, null, { timeout: 60000 });
  // let the calculation finish, then force a recalculation of the whole workbook from the formulas (not the stored values)
  await page.evaluate(async () => { const wb = window.__poc.api.getActiveWorkbook(); try { await wb.getActiveSheet().getRange(0, 0, 1, 1).getValue(); } catch { /* */ } });
  await sleep(1500);
  const got = await page.evaluate((cells) => {
    const sh = window.__poc.api.getActiveWorkbook().getActiveSheet();
    return cells.map((c) => { const rg = sh.getRange(c.r, c.c); const cd = rg.getCellData?.(); const v = cd && cd.v !== undefined ? cd.v : rg.getValue(); return { r: c.r, c: c.c, v: v === undefined ? null : v, display: rg.getDisplayValue?.() ?? null }; });
  }, formulas);
  let equal = 0; let diffs = [];
  formulas.forEach((f, i) => {
    let g = got[i].v;
    if (typeof g === "string" && g.trim() !== "" && !Number.isNaN(Number(g.replace(/[,\s]/g, "")))) g = Number(g.replace(/[,\s]/g, ""));
    let ok;
    if (f.vt === "n") ok = (typeof g === "number" && Math.abs(g - f.v) <= 1e-9) || (g === "-" && f.v === 0) || (typeof g === "string" && g.trim() === "-" && f.v === 0);
    else if (f.vt === "s") ok = String(g ?? "") === f.v;
    else ok = typeof g === "string" && g.startsWith("#"); // the spreadsheet program stored an error
    if (ok) equal++; else diffs.push({ cell: `${f.r + 1}:${f.c + 1}`, excelKind: f.vt, univer: typeof g === "string" && g.startsWith("#") ? g : typeof g });
  });
  out.compare = { formulas: formulas.length, equalToExcelStoredValue: equal, differences: diffs };
  console.log("COMPARE", JSON.stringify(out.compare));

  // typing a formula, as an author would: select an empty cell, type, Enter
  await page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(5, 7).activate(); });
  await sleep(300);
  await page.mouse.click(700, 330); // canvas focus
  await page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(5, 7).activate(); });
  await sleep(300);
  await page.keyboard.type("=SUM(1,2,3)+MIN(5,4)", { delay: 25 });
  await page.keyboard.press("Enter");
  await sleep(600);
  out.typedFormula = await page.evaluate(() => { const r = window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(5, 7); return { value: r.getValue(), formula: r.getFormula() }; });
  console.log("TYPED", JSON.stringify(out.typedFormula));

  // the pattern: row 4 (index 3) holds the formulas. Does inserting a row copy it? Does filling down adjust the references?
  const before = await page.evaluate(() => { const sh = window.__poc.api.getActiveWorkbook().getActiveSheet(); return sh.getRange(3, 12).getFormula(); });
  await page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().insertRowAfter(3); });
  await sleep(500);
  const afterInsert = await page.evaluate(() => { const sh = window.__poc.api.getActiveWorkbook().getActiveSheet(); return { newRow: sh.getRange(4, 12).getFormula(), oldRowNow: sh.getRange(3, 12).getFormula() }; });
  out.insertRow = { patternFormulaPresentOnRow4: before.length > 0, newRowHasFormula: !!afterInsert.newRow, row4Unchanged: afterInsert.oldRowNow === before };
  console.log("INSERT ROW", JSON.stringify(out.insertRow));
  const filled = await page.evaluate(async () => {
    const sh = window.__poc.api.getActiveWorkbook().getActiveSheet();
    // fill the formulas of row 4 down to rows 5 and 6 (indices 4 and 5) the way the fill handle does
    const src = sh.getRange(3, 12, 1, 8); const dest = sh.getRange(3, 12, 3, 8);
    let how = "autoFill";
    try { src.autoFill(dest, window.__poc.api.Enum?.AutoFillSeries?.DEFAULT ?? undefined); } catch (e) { how = "autoFill failed: " + String(e?.message ?? e).slice(0, 60); }
    await new Promise((r) => setTimeout(r, 600));
    return { how, row5: sh.getRange(4, 12).getFormula(), row6: sh.getRange(5, 12).getFormula(), src: sh.getRange(3, 12).getFormula() };
  });
  out.fillDown = { how: filled.how, row5HasFormula: !!filled.row5, referencesMovedWithTheRow: filled.row5 && filled.src && filled.row5 !== filled.src && /5/.test(filled.row5) };
  console.log("FILL DOWN", JSON.stringify(out.fillDown));
  await ctx.close();
} finally {
  writeFileSync(new URL("./results/poc1-univer-formulas.json", import.meta.url), JSON.stringify(out, null, 2));
  await browser.close();
  server.stop();
}
