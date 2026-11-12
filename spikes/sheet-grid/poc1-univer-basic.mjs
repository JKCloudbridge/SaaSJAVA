// POC 1, WP2 items 1 and 2: Univer 1.4.0 against both real workbooks (through the fixtures the Java spike wrote from POI's reading).
// Content policy (none / relaxed / strict), network after load, eval, weight, ready time, typing latency, scrolling of the 59-column
// supplier sheet with its frozen pane, memory. Prints and stores COUNTS and TIMES only.
//   POC1_DATA=<folder with plant.json and spm.json>  node poc1-univer-basic.mjs [--runs 5]
import { chromium } from "playwright-core";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { startServer } from "./lib/serve.mjs";

const DATA = process.env.POC1_DATA;
if (!DATA) { console.error("Set POC1_DATA to the folder that holds plant.json and spm.json."); process.exit(2); }
const RUNS = Number(process.argv.includes("--runs") ? process.argv[process.argv.indexOf("--runs") + 1] : 5);
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pct = (xs, p) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); return +s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))].toFixed(1); };
mkdirSync(new URL("./results/", import.meta.url), { recursive: true });

const INIT = () => {
  window.__csp = [];
  document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.effectiveDirective}:${String(e.blockedURI).startsWith("http") ? "url" : e.blockedURI}`));
  window.__lat = []; window.__long = []; window.__gaps = [];
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push(e.duration); }).observe({ type: "longtask", buffered: true }); } catch { /* not supported */ }
  addEventListener("keydown", () => { const t = performance.now(); requestAnimationFrame(() => setTimeout(() => window.__lat.push(performance.now() - t), 0)); }, true);
  window.__startFrames = () => { let last = performance.now(); const tick = (n) => { window.__gaps.push(n - last); last = n; window.__raf = requestAnimationFrame(tick); }; window.__gaps = []; window.__raf = requestAnimationFrame(tick); };
  window.__stopFrames = () => cancelAnimationFrame(window.__raf);
};

async function open(browser, origin, query) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin });
  await ctx.addInitScript(INIT);
  await ctx.route("**/poc1-fixture/*.json", (route) => {
    const name = new URL(route.request().url()).pathname.split("/").pop();
    route.fulfill({ contentType: "application/json", body: readFileSync(`${DATA}/${name}`) });
  });
  const page = await ctx.newPage();
  const net = { js: 0, jsGzip: 0, css: 0, foreign: new Set(), afterReady: [], ready: false, pending: [] };
  page.on("response", (r) => {
    const url = new URL(r.url()); const type = r.request().resourceType();
    if (url.origin !== origin) net.foreign.add(`${type} ${url.origin}`);
    if (net.ready && !/favicon/.test(url.pathname)) net.afterReady.push(`${r.request().method()} ${type}`);
    if (type === "script") net.pending.push(r.body().then((b) => { net.js += b.length; net.jsGzip += gzipSync(b).length; }).catch(() => {}));
    if (type === "stylesheet") net.pending.push(r.body().then((b) => { net.css += b.length; }).catch(() => {}));
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e.message).slice(0, 120)));
  await page.goto(`${origin}/poc1/univer?${query}`);
  let ready = null;
  try { await page.waitForFunction(() => window.__poc?.ready === true, null, { timeout: 60000 }); ready = await page.evaluate(() => window.__poc.readyAt); } catch { /* not ready */ }
  net.ready = true;
  await Promise.allSettled(net.pending);
  return { ctx, page, net, ready, errors };
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const server = await startServer({ port: 3100, csp: "relaxed" });
const result = { univer: "1.4.0", runs: RUNS };
try {
  // ---- content policies: which exceptions does Univer need? (data sheet of the supplier workbook) ----
  result.policies = {};
  for (const policy of ["none", "relaxed", "strict"]) {
    for (const full of [0, 1]) {
      server.setPolicy(policy);
      const o = await open(browser, server.origin, `fixture=spm&sheet=2&full=${full}`);
      const violations = o.ready === null ? ["did not start"] : await o.page.evaluate(() => window.__csp);
      const counts = {};
      for (const v of violations) counts[v] = (counts[v] ?? 0) + 1;
      const canvases = o.ready === null ? 0 : await o.page.evaluate(() => document.querySelectorAll("canvas").length);
      result.policies[`${policy}${full ? "+presets" : ""}`] = { started: o.ready !== null, violations: counts, canvases, foreign: [...o.net.foreign], afterReady: [...new Set(o.net.afterReady)], pageErrors: o.errors.slice(0, 3) };
      await o.ctx.close();
    }
  }
  console.log("POLICIES", JSON.stringify(result.policies, null, 1));
  server.setPolicy("relaxed");

  // ---- weight, ready, typing, scrolling, memory (supplier data sheet, 59 columns, frozen pane) ----
  for (const full of [0, 1]) {
    const ready = []; let weight = null; const typing = []; const scroll = []; let heapMb = null;
    for (let i = 0; i < RUNS; i++) {
      const o = await open(browser, server.origin, `fixture=spm&sheet=2&full=${full}`);
      if (o.ready === null) { await o.ctx.close(); continue; }
      ready.push(Math.round(o.ready));
      weight = { jsKb: Math.round(o.net.js / 1024), jsGzipKb: Math.round(o.net.jsGzip / 1024), cssKb: Math.round(o.net.css / 1024) };
      // typing into a data cell
      await o.page.evaluate(() => { window.__poc.api.getActiveWorkbook().getActiveSheet().getRange(3, 7).activate(); });
      await sleep(400);
      await o.page.evaluate(() => { window.__lat = []; });
      await o.page.keyboard.type("Typing latency probe text.", { delay: 40 });
      await sleep(200);
      const lat = await o.page.evaluate(() => window.__lat);
      typing.push({ n: lat.length, p50: pct(lat, 50), p95: pct(lat, 95), max: pct(lat, 100) });
      await o.page.keyboard.press("Escape");
      // scrolling across the 59 columns and down
      const t = await o.page.evaluate(() => { let best = null, area = 0; for (const c of document.querySelectorAll("canvas")) { const r = c.getBoundingClientRect(); const a = r.width * r.height; if (a > area) { area = a; best = r; } } return best ? { x: best.x + best.width / 2, y: best.y + best.height / 2 } : null; });
      await o.page.mouse.move(t.x, t.y);
      await o.page.evaluate(() => { window.__long = []; window.__startFrames(); });
      for (let k = 0; k < 40; k++) { await o.page.mouse.wheel(300, 0); await sleep(16); }
      for (let k = 0; k < 20; k++) { await o.page.mouse.wheel(0, 200); await sleep(16); }
      await sleep(300);
      const s = await o.page.evaluate(() => { window.__stopFrames(); return { gaps: window.__gaps, long: window.__long }; });
      scroll.push({ frames: s.gaps.length, over50ms: s.gaps.filter((g) => g > 50).length, gapP95: pct(s.gaps, 95), longTasks: s.long.length });
      if (i === 0) {
        const cdp = await o.ctx.newCDPSession(o.page); await cdp.send("Performance.enable"); await cdp.send("HeapProfiler.collectGarbage");
        const { metrics } = await cdp.send("Performance.getMetrics"); heapMb = +(metrics.find((m) => m.name === "JSHeapUsedSize").value / 1048576).toFixed(1);
      }
      await o.ctx.close();
    }
    result[full ? "withPresets" : "core"] = {
      readyMs: ready, readyMedian: pct(ready, 50), readyMax: Math.max(...ready), weight,
      typingP95: typing.map((x) => x.p95), typingP50: typing.map((x) => x.p50), typingKeys: typing[0]?.n,
      scrollOver50msPerRun: scroll.map((x) => x.over50ms), scrollLongTasksPerRun: scroll.map((x) => x.longTasks), heapMb,
    };
    console.log(full ? "WITH PRESETS" : "CORE ONLY", JSON.stringify(result[full ? "withPresets" : "core"]));
  }
} finally {
  writeFileSync(new URL("./results/poc1-univer-basic.json", import.meta.url), JSON.stringify(result, null, 2));
  await browser.close();
  server.stop();
}
