// POC 2, WP4.2 and WP4.3: draws every tab of both real forms in the own grid under each candidate look, under the STRICT content policy
// (style-src 'self': no style attribute written into markup, no style element), counts the violations, checks that the colours that reach
// the page are the file's own (look c), and writes one picture per tab and look into a folder OUTSIDE the repository (the pictures show the
// owner's text). Counts only are printed.
//   POC1_DATA=<folder with plant.json, spm.json, plant-extras.json> [OUT=<folder>] node poc2-look-shots.mjs
import { chromium } from "playwright-core";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { startServer } from "./lib/serve.mjs";

const DATA = process.env.POC1_DATA;
const OUT = process.env.OUT ?? `${DATA}/side-by-side`;
mkdirSync(OUT, { recursive: true });
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const INIT = () => { window.__csp = []; document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.effectiveDirective}:${String(e.blockedURI).startsWith("http") ? "url" : e.blockedURI}`)); };

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const server = await startServer({ port: 3100, csp: "strict" });
const out = { policy: "strict", pages: [] };
try {
  for (const [fixture, tabs] of [["plant", 2], ["spm", 4]]) {
    for (let tab = 0; tab < tabs; tab++) {
      for (const look of ["a", "b", "c"]) {
        const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, colorScheme: "light" });
        await ctx.addInitScript(INIT);
        await ctx.route("**/poc1-fixture/*.json", (route) => {
          const f = `${DATA}/${new URL(route.request().url()).pathname.split("/").pop()}`;
          return existsSync(f) ? route.fulfill({ contentType: "application/json", body: readFileSync(f) }) : route.fulfill({ status: 404, body: "" });
        });
        const page = await ctx.newPage();
        await page.goto(`${server.origin}/poc2/own?fixture=${fixture}&tab=${tab}&look=${look}`);
        await page.waitForFunction(() => window.__own2?.ready === true && document.querySelector('[role="gridcell"]'), null, { timeout: 60000 });
        await sleep(400);
        // let the whole sheet show in one picture: the scroller's height and overflow are set through the CSS object model
        await page.evaluate(() => { const s = document.querySelector(".pc-scroll"); s.style.height = "auto"; s.style.maxHeight = "none"; s.style.overflow = "visible"; });
        const info = await page.evaluate(() => {
          const cells = [...document.querySelectorAll('[role="gridcell"]')];
          let withVar = 0, mismatched = 0;
          for (const c of cells) {
            const bg = c.style.getPropertyValue("--pc2-bg");
            if (bg) { withVar++; const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(bg.trim()); const want = m ? `rgb(${parseInt(m[1], 16)}, ${parseInt(m[2], 16)}, ${parseInt(m[3], 16)})` : ""; if (getComputedStyle(c).backgroundColor !== want) mismatched++; }
          }
          const hasStyleAttrInMarkup = [...document.querySelectorAll("[style]")].length; // set through the object model, not written in the HTML
          return { cells: cells.length, withValidatedHex: withVar, mismatchedColours: mismatched, elementsWithStyleProperties: hasStyleAttrInMarkup, tabs: window.__own2.tabs, hidden: window.__own2.hidden };
        });
        const violations = await page.evaluate(() => window.__csp);
        await page.screenshot({ path: `${OUT}/${fixture}-tab${tab + 1}-look-${look}.png`, fullPage: true });
        out.pages.push({ fixture, tab: tab + 1, look, ...info, violations: violations.length, violationKinds: [...new Set(violations)] });
        await ctx.close();
      }
    }
  }
  // dark mode, one picture, look c
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, colorScheme: "dark" });
  await ctx.addInitScript(INIT);
  await ctx.route("**/poc1-fixture/*.json", (route) => { const f = `${DATA}/${new URL(route.request().url()).pathname.split("/").pop()}`; return existsSync(f) ? route.fulfill({ contentType: "application/json", body: readFileSync(f) }) : route.fulfill({ status: 404, body: "" }); });
  const page = await ctx.newPage();
  await page.goto(`${server.origin}/poc2/own?fixture=spm&tab=2&look=c`);
  await page.waitForFunction(() => window.__own2?.ready === true && document.querySelector('[role="gridcell"]'), null, { timeout: 60000 });
  await sleep(300);
  await page.evaluate(() => { const s = document.querySelector(".pc-scroll"); s.style.height = "auto"; s.style.overflow = "visible"; });
  await page.screenshot({ path: `${OUT}/spm-tab3-look-c-dark.png`, fullPage: true });
  out.darkViolations = (await page.evaluate(() => window.__csp)).length;
  await ctx.close();
} finally { await browser.close(); server.stop(); }
const total = out.pages.reduce((s, p) => s + p.violations, 0);
out.totalViolations = total;
out.summary = out.pages.map((p) => `${p.fixture} tab ${p.tab} look ${p.look}: ${p.cells} cells, ${p.withValidatedHex} with a validated hex, ${p.mismatchedColours} colours different from the file, violations ${p.violations}`);
writeFileSync(new URL("./results/poc2-look.json", import.meta.url), JSON.stringify(out, null, 1));
console.log(out.summary.join("\n"));
console.log(`pages ${out.pages.length}; violations under the strict policy in total: ${total}; dark mode page: ${out.darkViolations}; pictures in ${OUT.split(/[\\/]/).slice(-2).join("/")}`);
