// A quick look: does each candidate start, what does it log, what does it request. Usage: node probe.mjs [path] [query]
import { chromium } from "playwright-core";
import { existsSync } from "node:fs";
import { startServer } from "./lib/serve.mjs";

const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const srv = await startServer({ csp: process.env.SPIKE_CSP ?? "strict" });
const ORIGIN = srv.origin;

const targets = process.argv[2] ? [[process.argv[2], process.argv[3] ?? "rows=20&cols=8"]] : ["baseline", "own", "fortune", "univer", "jspreadsheet"].map((p) => [p, "rows=20&cols=8"]);
const browser = await chromium.launch({ executablePath: CHROME, headless: true });
try {
  for (const [path, query] of targets) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const logs = [], requests = [];
    page.on("console", (m) => { if (["error", "warning"].includes(m.type())) logs.push(`${m.type()}: ${m.text().slice(0, 200)}`); });
    page.on("pageerror", (e) => logs.push(`pageerror: ${String(e.message).slice(0, 200)}`));
    page.on("request", (r) => requests.push(`${r.method()} ${r.resourceType()} ${new URL(r.url()).origin === ORIGIN ? new URL(r.url()).pathname.slice(0, 50) : r.url().slice(0, 80)}`));
    const t0 = Date.now();
    await page.goto(`${ORIGIN}/${path}?${query}`);
    let ready = null;
    try { await page.waitForFunction(() => window.__spike?.ready === true, null, { timeout: 30000 }); ready = await page.evaluate(() => window.__spike.readyAt); } catch { /* not ready */ }
    const info = await page.evaluate(() => ({ failed: window.__spike?.failed, canvases: document.querySelectorAll("canvas").length, roles: { grid: document.querySelectorAll("[role=grid]").length, cell: document.querySelectorAll("[role=gridcell]").length }, nodes: document.querySelectorAll("*").length }));
    console.log(`\n== ${path}  ready=${ready === null ? "NO" : Math.round(ready) + " ms"} (wall ${Date.now() - t0} ms)`, JSON.stringify(info));
    const nonAsset = requests.filter((r) => !/^GET (script|stylesheet|font|document|image) /.test(r));
    console.log("   requests:", requests.length, "non-asset or foreign:", nonAsset.length, nonAsset.slice(0, 6).join(" | "));
    console.log("   logs:", logs.slice(0, 6).join(" | "));
    await page.screenshot({ path: `results/probe-${path}.png` });
    await page.close();
  }
} finally {
  await browser.close();
  srv.stop();
}
