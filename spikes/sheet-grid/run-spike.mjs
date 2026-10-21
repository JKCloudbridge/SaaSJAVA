// FA0 grid spike, browser part. Real Chrome, real production build of the spike app, nothing mocked.
//   node run-spike.mjs                     everything
//   node run-spike.mjs --only own,univer   some candidates
//   node run-spike.mjs --sizes S,M         some sizes (S 20x8, M 60x15, L 150x30, X 500x100, XX 1000x200)
//   node run-spike.mjs --skip-checks       only the size measurements
// Results: results/browser-*.json and screenshots in results/. CHROME_PATH overrides the browser.
import { chromium } from "playwright-core";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import { startServer } from "./lib/serve.mjs";
import { DRIVERS, pasteText } from "./run/drivers.mjs";
import { makeTemplate } from "./lib/model.mjs";
import { displayRows } from "./lib/gridOps.mjs";
import { normalise } from "./adapters/common.mjs";

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const ONLY = (opt("--only") ?? "own,fortune,univer,jspreadsheet").split(",");
const SIZES = { S: [20, 8], M: [60, 15], L: [150, 30], X: [500, 100], XX: [1000, 200] };
const SIZE_NAMES = (opt("--sizes") ?? "S,M,L,X,XX").split(",");
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].filter(Boolean).find(existsSync);
if (!CHROME) { console.error("No Chrome or Edge found. Set CHROME_PATH."); process.exit(2); }
mkdirSync(new URL("./results/", import.meta.url), { recursive: true });
const out = (name, value) => writeFileSync(new URL(`./results/browser-${name}.json`, import.meta.url), JSON.stringify(value, null, 2));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pct = (xs, p) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); return +s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))].toFixed(1); };

const INIT = () => {
  window.__csp = [];
  document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.effectiveDirective}`));
  window.__lat = [];
  window.__long = [];
  window.__gaps = [];
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push(e.duration); }).observe({ type: "longtask", buffered: true }); } catch { /* not supported */ }
  addEventListener("keydown", () => {
    const t = performance.now();
    requestAnimationFrame(() => setTimeout(() => window.__lat.push(performance.now() - t), 0));
  }, true);
  window.__startFrames = () => { let last = performance.now(); const tick = (n) => { window.__gaps.push(n - last); last = n; window.__raf = requestAnimationFrame(tick); }; window.__gaps = []; window.__raf = requestAnimationFrame(tick); };
  window.__stopFrames = () => cancelAnimationFrame(window.__raf);
};

async function open(browser, origin, driver, query, { colorScheme = "light", record = true } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme });
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin });
  await ctx.addInitScript(INIT);
  const page = await ctx.newPage();
  page.dialogs = [];
  page.on("dialog", async (d) => { page.dialogs.push(d.type()); await d.accept(); });
  const net = { js: 0, jsGzip: 0, css: 0, requests: [], foreign: [], dynamic: [], sockets: 0, pending: [] };
  const logs = [];
  if (record) {
    page.on("response", (r) => {
      const url = new URL(r.url());
      const type = r.request().resourceType();
      net.requests.push(`${r.request().method()} ${type}`);
      if (url.origin !== origin) net.foreign.push(`${type} ${url.origin}`);
      if (["fetch", "xhr", "ping", "other", "websocket", "eventsource"].includes(type)) net.dynamic.push(`${r.request().method()} ${type} ${url.pathname.slice(0, 60)}`);
      if (type === "script" || type === "stylesheet") {
        net.pending.push(r.body().then((b) => { if (type === "script") { net.js += b.length; net.jsGzip += gzipSync(b).length; } else net.css += b.length; }).catch(() => {}));
      }
    });
    page.on("websocket", () => { net.sockets++; });
    page.on("console", (m) => { if (["error", "warning"].includes(m.type())) logs.push(`${m.type()}: ${m.text().slice(0, 160)}`); });
    page.on("pageerror", (e) => logs.push(`pageerror: ${String(e.message).slice(0, 160)}`));
  }
  const t0 = Date.now();
  await page.goto(`${origin}/${driver.path ?? driver}?${query}`);
  let ready = null;
  try { await page.waitForFunction(() => window.__spike?.ready === true, null, { timeout: 60000 }); ready = await page.evaluate(() => window.__spike.readyAt); } catch { /* not ready */ }
  await Promise.allSettled(net.pending);
  return { ctx, page, net, logs, ready, wall: Date.now() - t0 };
}

async function heap(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Performance.enable");
  await cdp.send("HeapProfiler.collectGarbage");
  const { metrics } = await cdp.send("Performance.getMetrics");
  const m = Object.fromEntries(metrics.map((x) => [x.name, x.value]));
  await cdp.detach();
  return { heapMb: +(m.JSHeapUsedSize / 1048576).toFixed(1), domNodes: m.Nodes };
}

// The element to scroll: the biggest scrollable element, else the middle of the biggest canvas.
async function scrollTarget(page) {
  return page.evaluate(() => {
    let best = null, area = 0;
    for (const el of document.querySelectorAll("*")) {
      const s = getComputedStyle(el);
      if ((s.overflow === "auto" || s.overflow === "scroll" || s.overflowY === "auto" || s.overflowY === "scroll" || s.overflowX === "auto" || s.overflowX === "scroll") && (el.scrollHeight > el.clientHeight + 4 || el.scrollWidth > el.clientWidth + 4)) {
        const r = el.getBoundingClientRect(); const a = r.width * r.height;
        if (a > area && r.width > 200) { area = a; best = r; }
      }
    }
    if (!best) for (const c of document.querySelectorAll("canvas")) { const r = c.getBoundingClientRect(); const a = r.width * r.height; if (a > area) { area = a; best = r; } }
    return best ? { x: best.x + best.width / 2, y: best.y + best.height / 2 } : null;
  });
}

async function measure(browser, origin, name, driver, sizeName, [rows, cols]) {
  const query = `rows=${rows}&cols=${cols}`;
  const { ctx, page, net, logs, ready, wall } = await open(browser, origin, driver, query);
  const row = { candidate: name, size: `${sizeName} ${rows}x${cols}`, readyMs: ready === null ? null : Math.round(ready), wallMs: wall };
  if (ready === null) { row.failed = (await page.evaluate(() => window.__spike?.failed ?? "did not become ready")) ; await ctx.close(); return { row, net: null }; }
  const dom = await page.evaluate(() => ({ cells: window.__spike?.cellsInDom?.() ?? null, canvases: document.querySelectorAll("canvas").length }));
  Object.assign(row, dom, await heap(page));
  // typing: select a cell with the driver, type, collect time from key press to the next painted frame
  try {
    await driver.select(page, Math.min(6, rows + 1), 5 % cols);
    await page.evaluate(() => { window.__lat = []; });
    await page.keyboard.type("Typing latency probe text.", { delay: 40 });
    await sleep(200);
    const lat = await page.evaluate(() => window.__lat);
    row.typing = { n: lat.length, p50: pct(lat, 50), p95: pct(lat, 95), max: pct(lat, 100) };
    await page.keyboard.press("Escape");
  } catch (e) { row.typing = `error: ${String(e.message).split("\n")[0].slice(0, 100)}`; }
  // scrolling: wheel the grid down and across, record the gaps between frames and long tasks
  try {
    const t = await scrollTarget(page);
    if (t) {
      await page.mouse.move(t.x, t.y);
      await page.evaluate(() => { window.__long = []; window.__startFrames(); });
      for (let i = 0; i < 30; i++) { await page.mouse.wheel(0, 260); await sleep(16); }
      for (let i = 0; i < 20; i++) { await page.mouse.wheel(260, 0); await sleep(16); }
      await sleep(300);
      const s = await page.evaluate(() => { window.__stopFrames(); return { gaps: window.__gaps, long: window.__long }; });
      row.scroll = { frames: s.gaps.length, gapP50: pct(s.gaps, 50), gapP95: pct(s.gaps, 95), gapMax: pct(s.gaps, 100), over50ms: s.gaps.filter((g) => g > 50).length, longTasks: s.long.length, longTaskMs: Math.round(s.long.reduce((a, b) => a + b, 0)) };
      Object.assign(row, { heapAfterScrollMb: (await heap(page)).heapMb, cellsAfterScroll: await page.evaluate(() => window.__spike?.cellsInDom?.() ?? null) });
    } else row.scroll = "no scrollable area found";
  } catch (e) { row.scroll = `error: ${String(e.message).split("\n")[0].slice(0, 100)}`; }
  row.cspViolations = await page.evaluate(() => window.__csp.length);
  const result = { row, net: { jsKb: +(net.js / 1024).toFixed(0), jsGzipKb: +(net.jsGzip / 1024).toFixed(0), cssKb: +(net.css / 1024).toFixed(0), requests: net.requests.length, foreign: [...new Set(net.foreign)], dynamic: [...new Set(net.dynamic)].filter((d) => !/favicon/.test(d)), sockets: net.sockets }, logs: logs.filter((l) => !/404|favicon/.test(l)).slice(0, 4) };
  await ctx.close();
  return result;
}

// ---- scenario -----------------------------------------------------------------------------------------------------
const keyAt = (doc, r, c) => { const rows = displayRows(doc); return `${rows[r].id}:${doc.columns[c].id}`; };
async function scenario(browser, origin, name, driver) {
  const q = "rows=20&cols=8";
  const expected = normalise(makeTemplate({ rows: 20, columns: 8, dimension: 2, fixedRatio: 0.05 }));
  const { ctx, page, ready } = await open(browser, origin, driver, q, { record: false });
  const res = [];
  const T = async (id, fn) => {
    try { const v = await fn(); res.push({ id, ...(typeof v === "object" ? v : { ok: !!v }) }); }
    catch (e) { res.push({ id, ok: false, note: `error: ${String(e.message).split("\n")[0].slice(0, 140)}` }); }
  };
  const getDoc = () => page.evaluate(() => window.__spike.getDoc());
  if (ready === null) { await ctx.close(); return [{ id: "start", ok: false, note: "did not start" }]; }
  await T("adapter: the stored document is read back unchanged", async () => ({ ok: JSON.stringify(normalise(await getDoc())) === JSON.stringify(expected) }));
  await T("typing: click a cell, type text, Enter", async () => {
    await driver.select(page, 8, 5); await driver.typeText(page, "Hello"); await driver.commit(page); await sleep(150);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 8, 5)]?.value === "Hello", note: JSON.stringify(d.cells[keyAt(d, 8, 5)] ?? null).slice(0, 80) };
  });
  for (const what of ["bold", "italic"]) {
    await T(`${what}: keyboard shortcut, then the toolbar`, async () => {
      const has = (d, r) => d.cells[keyAt(d, r, 5)]?.style?.[what] === true;
      await driver.select(page, 8, 5); await driver.shortcut(page, what); await sleep(450);
      const viaKey = has(await getDoc(), 8);
      let viaBar = null;
      if (driver.toolbar) { try { await driver.select(page, 9, 5); await driver.toolbar(page, what); await sleep(450); viaBar = has(await getDoc(), 9); } catch (e) { viaBar = `none (${String(e.message).split("\n")[0].slice(0, 60)})`; } }
      return { ok: viaKey || viaBar === true, note: `shortcut ${viaKey ? "works" : "does nothing"}; toolbar ${viaBar === null ? "none" : viaBar === true ? "works" : viaBar === false ? "does nothing" : viaBar}` };
    });
  }
  await T("keyboard: arrows move, then typing lands in the right cell", async () => {
    await driver.select(page, 14, 3); await sleep(150);
    for (const k of ["ArrowDown", "ArrowDown", "ArrowRight"]) { await page.keyboard.press(k); await sleep(80); }
    await driver.typeText(page, "Z"); await driver.commit(page); await sleep(150);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 16, 4)]?.value === "Z", note: "Z landed at " + (Object.keys(d.cells).filter((k) => d.cells[k].value === "Z").map((k) => { const rows = displayRows(d); const [rid, cid] = k.split(":"); return `row ${rows.findIndex((x) => x.id === rid)} column ${d.columns.findIndex((x) => x.id === cid)}`; }).join("; ") || "nowhere") + ", wanted row 16 column 4" };
  });
  await T(`merge: select 2x2 and merge (${driver.mergeVia})`, async () => {
    await driver.selectRange(page, 10, 1, 11, 2); await driver.merge(page); await sleep(250);
    const d = await getDoc(); const m = d.merges.find((x) => x.from === keyAt(d, 10, 1));
    return { ok: !!m && m.to === keyAt(d, 11, 2), note: (m ? "merged" : "no merge found") + (page.dialogs.length ? `; the library opened a native ${page.dialogs.join(", ")} dialog` : "") };
  });
  await T("unmerge", async () => {
    await driver.select(page, 10, 1); await driver.unmerge(page); await sleep(250);
    const d = await getDoc(); return { ok: !d.merges.some((x) => x.from === keyAt(d, 10, 1)) };
  });
  let pasted = false;
  await T("paste: a block copied from a spreadsheet application (2 x 2)", async () => {
    await driver.select(page, 12, 1); await pasteText(page, "Pass\t12.5\nFail\t7"); await sleep(300);
    const d = await getDoc(); const v = (r, c) => d.cells[keyAt(d, r, c)]?.value;
    pasted = v(12, 1) === "Pass" && v(12, 2) === "12.5" && v(13, 1) === "Fail" && v(13, 2) === "7";
    return { ok: pasted, note: [v(12, 1), v(12, 2), v(13, 1), v(13, 2)].join("|") };
  });
  await T("undo: Ctrl+Z takes the whole paste back in one step", async () => {
    if (!pasted) return { ok: false, note: "not tested: the paste did not work" };
    await page.keyboard.press("Control+z"); await sleep(300);
    const d = await getDoc(); const v = (r, c) => d.cells[keyAt(d, r, c)]?.value;
    const left = [v(12, 1), v(12, 2), v(13, 1), v(13, 2)].filter((x) => x != null).length;
    return { ok: left === 0, note: `${left} of 4 pasted values left` };
  });
  await ctx.close();
  return res;
}

// Fill view of the own grid: fixed cells are read-only and typed values are checked against the column type.
async function fillChecks(browser, origin) {
  const driver = DRIVERS.own;
  const { ctx, page } = await open(browser, origin, driver, "rows=20&cols=8&mode=fill", { record: false });
  const res = [];
  const T = async (id, fn) => { try { const v = await fn(); res.push({ id, ...v }); } catch (e) { res.push({ id, ok: false, note: `error: ${String(e.message).split("\n")[0].slice(0, 140)}` }); } };
  const getDoc = () => page.evaluate(() => window.__spike.getDoc());
  const message = () => page.locator(".og-message").innerText();
  await T("a fixed cell cannot be typed into", async () => {
    await driver.select(page, 2, 0); await page.keyboard.type("zz"); await sleep(100);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 2, 0)]?.value !== "zz", note: await message() };
  });
  await T("a number column refuses letters and accepts digits", async () => {
    await driver.select(page, 3, 2); await page.keyboard.type("abc"); await page.keyboard.press("Enter"); await sleep(100);
    const refused = (await message()).length > 0; await page.keyboard.press("Escape");
    await driver.select(page, 3, 2); await page.keyboard.type("12.5"); await page.keyboard.press("Enter"); await sleep(100);
    const d = await getDoc(); return { ok: refused && d.cells[keyAt(d, 3, 2)]?.value === "12.5", note: `refused=${refused}` };
  });
  await T("a list column refuses a value that is not in the list", async () => {
    await driver.select(page, 4, 1); await page.keyboard.press("Enter"); await sleep(100);
    const kind = await page.evaluate(() => document.activeElement?.tagName);
    await page.keyboard.press("Escape");
    return { ok: kind === "SELECT", note: `the editor is a ${kind}` };
  });
  await T("a yes or no column toggles with Enter", async () => {
    await driver.select(page, 5, 4); await page.keyboard.press("Enter"); await sleep(100);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 5, 4)]?.value === true };
  });
  await T("pasting into fixed and input cells together fills only the input cells", async () => {
    await driver.select(page, 6, 0); await pasteText(page, "a\tPass\t3.5\n\tFail\tabc"); await sleep(200);
    const d = await getDoc(); const v = (r, c) => d.cells[keyAt(d, r, c)]?.value;
    return { ok: v(6, 0) !== "a" && v(6, 1) === "Pass" && v(6, 2) === "3.5" && v(7, 2) !== "abc", note: `${v(6, 1)}|${v(6, 2)}|${v(7, 1)}|${v(7, 2)} ; ${await message()}`.slice(0, 160) };
  });
  await ctx.close();
  return res;
}

// Fill view for the libraries: can fixed cells be protected, and are typed values checked? (the library features, not ours)
async function fillChecksLib(browser, origin, name, driver) {
  const { ctx, page, ready } = await open(browser, origin, driver, "rows=20&cols=8&mode=fill", { record: false });
  const res = [];
  if (ready === null) { await ctx.close(); return [{ id: "start", ok: false, note: "did not start" }]; }
  const T = async (id, fn) => { try { const v = await fn(); res.push({ id, ...v }); } catch (e) { res.push({ id, ok: false, note: `error: ${String(e.message).split("\n")[0].slice(0, 140)}` }); } };
  const getDoc = () => page.evaluate(() => window.__spike.getDoc());
  const before = await getDoc();
  await T("a fixed cell cannot be typed into (library hook)", async () => {
    await driver.select(page, 2, 0); await driver.typeText(page, "zz"); await driver.commit(page); await sleep(300);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 2, 0)]?.value === before.cells[keyAt(before, 2, 0)]?.value };
  });
  await T("an input cell can be typed into", async () => {
    await driver.select(page, 3, 1); await driver.typeText(page, "Pass"); await driver.commit(page); await sleep(300);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 3, 1)]?.value === "Pass" };
  });
  await T("pasting across fixed and input cells changes only the input cells", async () => {
    await driver.select(page, 4, 0); await pasteText(page, "a\tPass"); await sleep(400);
    const d = await getDoc(); const v = (r, c) => d.cells[keyAt(d, r, c)]?.value;
    return { ok: v(4, 0) === before.cells[keyAt(before, 4, 0)]?.value && v(4, 1) === "Pass", note: `fixed cell now ${JSON.stringify(v(4, 0))}, input cell now ${JSON.stringify(v(4, 1))}` };
  });
  await T("a number column refuses letters", async () => {
    await driver.select(page, 5, 2); await driver.typeText(page, "abc"); await driver.commit(page); await sleep(300);
    const d = await getDoc(); return { ok: d.cells[keyAt(d, 5, 2)]?.value !== "abc", note: `the cell holds ${JSON.stringify(d.cells[keyAt(d, 5, 2)]?.value ?? null)}` };
  });
  await ctx.close();
  return res;
}

// ---- checks that are not about size -----------------------------------------------------------------------------------
async function luminanceOfRegion(page, clip) {
  const png = await page.screenshot({ clip });
  const b64 = png.toString("base64");
  return page.evaluate(async (b) => {
    const img = new Image(); img.src = `data:image/png;base64,${b}`; await img.decode();
    const c = document.createElement("canvas"); c.width = img.width; c.height = img.height;
    const g = c.getContext("2d"); g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height).data; let s = 0;
    for (let i = 0; i < d.length; i += 4) s += (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255;
    return s / (d.length / 4);
  }, b64);
}

async function darkMode(browser, origin, name, driver) {
  const lum = {};
  for (const scheme of ["light", "dark"]) {
    const { ctx, page, ready } = await open(browser, origin, driver, "rows=20&cols=8", { colorScheme: scheme, record: false });
    if (ready === null) { await ctx.close(); return { ok: false, note: "did not start" }; }
    const t = await scrollTarget(page);
    const clip = t ? { x: Math.max(0, t.x - 300), y: Math.max(0, t.y - 150), width: 600, height: 300 } : { x: 0, y: 100, width: 600, height: 300 };
    lum[scheme] = +(await luminanceOfRegion(page, clip)).toFixed(2);
    await page.screenshot({ path: fileURLToPath(new URL(`./results/dark-${name}-${scheme}.png`, import.meta.url)) });
    await ctx.close();
  }
  return { light: lum.light, dark: lum.dark, ok: lum.dark < 0.4 && lum.light > 0.6, note: `mean brightness light ${lum.light}, dark ${lum.dark}` };
}

async function accessibility(browser, origin, name, driver) {
  const { ctx, page, ready } = await open(browser, origin, driver, "rows=20&cols=8", { record: false });
  if (ready === null) { await ctx.close(); return { ok: false, note: "did not start" }; }
  const counts = await page.evaluate(() => Object.fromEntries(["grid", "row", "gridcell", "columnheader", "cell", "textbox", "button", "toolbar"].map((r) => [r, document.querySelectorAll(`[role=${r}]`).length + (r === "row" ? document.querySelectorAll("tr").length : 0) + (r === "cell" ? document.querySelectorAll("td").length : 0) + (r === "columnheader" ? document.querySelectorAll("th").length : 0)])));
  const snap = (await page.locator("body").ariaSnapshot()).split("\n");
  const cellLines = snap.filter((l) => /(cell|gridcell|columnheader)/.test(l)).length;
  await page.keyboard.press("Tab"); await page.keyboard.press("Tab");
  const focus = await page.evaluate(() => { const a = document.activeElement; if (!a) return "none"; const s = getComputedStyle(a); return `${a.tagName.toLowerCase()}${a.getAttribute("role") ? `[role=${a.getAttribute("role")}]` : ""} outline=${s.outlineStyle} ${s.outlineWidth}`; });
  await ctx.close();
  return { counts, cellsInAccessibilityTree: cellLines, focusAfterTwoTabs: focus, ok: counts.gridcell > 0 || counts.cell > 0 };
}

async function policies(server, browser, name, driver) {
  const r = {};
  for (const p of ["relaxed", "strict"]) {
    server.setPolicy(p);
    const { ctx, page, ready, logs } = await open(browser, server.origin, driver, "rows=20&cols=8", { record: true });
    const v = ready === null ? ["did not start"] : await page.evaluate(() => window.__csp);
    const evalRefused = logs.some((l) => /unsafe-eval|evaluate a string/.test(l));
    const t = await scrollTarget(page).catch(() => null);
    await page.screenshot({ path: fileURLToPath(new URL(`./results/csp-${name}-${p}.png`, import.meta.url)) });
    r[p] = { started: ready !== null, violations: v.length, directives: [...new Set(v)], evalNeeded: evalRefused, hasScrollArea: !!t };
    await ctx.close();
  }
  server.setPolicy("relaxed");
  return r;
}

// ---- main -------------------------------------------------------------------------------------------------------------
const server = await startServer({ csp: "relaxed" });
const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const all = { measured: [], network: {}, scenario: {}, fill: [], dark: {}, a11y: {}, csp: {}, baseline: null, chrome: browser.version(), when: new Date().toISOString() };
try {
  const base = await open(browser, server.origin, "baseline", "rows=1&cols=1", {});
  all.baseline = { readyMs: Math.round(base.ready), heap: await heap(base.page) };
  await Promise.allSettled(base.net.pending);
  all.baseline.jsKb = +(base.net.js / 1024).toFixed(0); all.baseline.jsGzipKb = +(base.net.jsGzip / 1024).toFixed(0);
  await base.ctx.close();
  console.log("baseline page (no grid):", JSON.stringify(all.baseline));
  for (const name of ONLY) {
    const driver = DRIVERS[name];
    console.log(`\n######## ${name}`);
    for (const sn of SIZE_NAMES) {
      const r = await measure(browser, server.origin, name, driver, sn, SIZES[sn]);
      all.measured.push(r.row);
      if (sn === SIZE_NAMES[0] && r.net) all.network[name] = { ...r.net, logs: r.logs };
      console.log(JSON.stringify(r.row));
    }
    if (!args.includes("--skip-checks")) {
      all.scenario[name] = await scenario(browser, server.origin, name, driver);
      for (const t of all.scenario[name]) console.log(`  ${t.ok ? "PASS" : "FAIL"}  ${t.id}${t.note ? `   (${t.note})` : ""}`);
      if (name === "own") {
        all.fill = await fillChecks(browser, server.origin);
        for (const t of all.fill) console.log(`  ${t.ok ? "PASS" : "FAIL"}  [fill view] ${t.id}${t.note ? `   (${t.note})` : ""}`);
      }
      if (name !== "own") {
        all.fillLib ??= {};
        all.fillLib[name] = await fillChecksLib(browser, server.origin, name, driver);
        for (const t of all.fillLib[name]) console.log(`  ${t.ok ? "PASS" : "FAIL"}  [fill view] ${t.id}${t.note ? `   (${t.note})` : ""}`);
      }
      all.dark[name] = await darkMode(browser, server.origin, name, driver);
      console.log("  dark mode:", JSON.stringify(all.dark[name]));
      all.a11y[name] = await accessibility(browser, server.origin, name, driver);
      console.log("  accessibility:", JSON.stringify(all.a11y[name]));
      all.csp[name] = await policies(server, browser, name, driver);
      console.log("  policy:", JSON.stringify(all.csp[name]));
    }
    out("results", all);
  }
} finally {
  await browser.close();
  server.stop();
  out("results", all);
}
