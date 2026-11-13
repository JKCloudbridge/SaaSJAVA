// The kept browser script of the look of a sheet (Functional track SH4, ADR-0251). It drives the PRODUCTION BUILD of the web
// application in a real Chromium-based browser against a RUNNING backend with the switch for format version 2 on (the `local`
// profile has it), the way a person would. It checks what a jsdom test cannot, against the pass thresholds written in the plan
// of SH4 BEFORE any number was seen (the rule of ADR-0082: a threshold is never moved after a number is seen; the code is fixed
// instead):
//
//   T1  first paint of the largest sheet (500 x 100, every cell with a look)        <= 1500 ms
//   T2  scroll the largest sheet in 60 steps; time to drawn cells                    p95 <= 100 ms, worst <= 250 ms
//   T3  cells in the page at any moment                                              <= 1500
//   T4  moving the selection with an arrow key, key to next paint                    p95 <= 50 ms, worst <= 150 ms
//   T5  JS memory after three scroll passes <= 250 MB, growth from pass 1 to 3       <= 50 MB
//   T7  the data sheet shaped like the supplier form (59 columns, frozen rows)       first paint <= 800 ms
//   T8  frozen rows and columns after a scroll of 5,000 px down and 2,000 px across  within 1 px of the expected place
//   T9  content-policy violations on every page, light and dark, print view included 0
//   T11 the focus ring and the selection over a dark fill, a light fill and white    visible (two tones, 3:1 on one of them)
//
// With --workbooks it also uploads the two real workbooks (read from BYTES, never copied, never quoted) and checks T12 (the
// colours of every drawn cell equal the stored ones) and saves pictures OUTSIDE the repository. With no flag only the synthetic
// checks run. It prints one line per check ("ok" or "FAIL"), and exits with 1 if any failed.
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { chromium } from "playwright-core";

const BASE = (process.env.BASE_URL ?? "http://tenant-a.localhost:3000").replace(/\/$/, "");
const EMAIL = process.env.ADMIN_EMAIL ?? "admin-a@example.test";
const PASSWORD = process.env.LOCAL_SEED_PASSWORD;
const WORKBOOKS = process.argv.includes("--workbooks");
const PICTURES =
  process.env.PICTURES_DIR ??
  path.join(homedir(), "Desktop", "SaaS", "Shree Ganeshay Namah", "Project 2", "Shree Ganeshay Namah", "SH4 pictures");
const PLANT = process.env.PLANT_WORKBOOK ?? path.join(homedir(), "Downloads", "Plant activity Dec-25 (1).xlsx");
const SPM = process.env.SPM_WORKBOOK ?? path.join(homedir(), "Downloads", "SPM (1).xlsx");
const CHROME =
  process.env.CHROME_PATH ??
  [
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/google-chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ].find((candidate) => existsSync(candidate));

if (!PASSWORD) {
  console.error("Set LOCAL_SEED_PASSWORD to the password of the seeded administrator (infra/local/.env).");
  process.exit(2);
}

let failed = 0;
const check = (name, ok, detail = "") => {
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
};
const percentile = (numbers, p) => {
  const sorted = [...numbers].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] ?? 0;
};

async function ensureForgeryCookie(page) {
  // asked for before every write: the server rotates the cookie after each one, and a stale token is refused
  await page.evaluate(() => fetch("/api/v1/auth/csrf"));
}

async function api(page, method, address, body) {
  if (method !== "GET") await ensureForgeryCookie(page);
  return page.evaluate(
    async ([method, address, body]) => {
      const csrf = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length) ?? "";
      const response = await fetch(address, {
        method,
        headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(csrf) },
        body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, body: text ? JSON.parse(text) : null };
    },
    [method, address, body],
  );
}

/** One of forty looks: fills, text colours, fonts, sizes, borders and number formats, in a repeating pattern. */
function lookOf(n) {
  const fills = ["#ffe699", "#c6e0b4", "#bdd7ee", "#f8cbad", "#1f3864", "#ffffff", "#d9d9d9", "#7030a0"];
  const colours = [undefined, "#ffffff", "#c00000", "#1f3864", undefined];
  const fonts = ["SANS_HUMANIST", "SANS_NEUTRAL", "SERIF_TEXT", "MONO_CODE", "SANS_WIDE"];
  const formats = ["GENERAL", "TWO_DECIMALS", "PERCENT", "THOUSANDS", "WHOLE"];
  const sides = ["THIN", "MEDIUM", "DOTTED", "DASHED", "THICK", "DOUBLE"];
  const look = {
    font: fonts[n % 5],
    size: [9, 11, 12, 14, 18][n % 5],
    fill: fills[n % 8],
    numberFormat: formats[n % 5],
    borders: { right: sides[n % 6], bottom: sides[(n + 1) % 6] },
    verticalAlignment: ["TOP", "MIDDLE", "BOTTOM"][n % 3],
  };
  if (colours[n % 5]) look.colour = colours[n % 5];
  if (n % 7 === 0) look.wrap = true;
  return look;
}

/** The largest sheet the limits allow in format version 2: 500 rows by 100 columns, a look on every cell, frozen panes, rules. */
function largestLookedSheet(columnCount = 100, rowCount = 500) {
  const columns = Array.from({ length: columnCount }, (_, i) => ({
    id: `c${String(i).padStart(3, "0")}`,
    label: `Column ${i + 1}`,
    type: "text",
    width: 80 + (i % 5) * 20,
  }));
  const rows = Array.from({ length: rowCount }, (_, i) => ({ id: `r${String(i).padStart(3, "0")}`, label: `Row ${i + 1}` }));
  const cells = {};
  for (const row of rows) {
    columns.forEach((column, j) => {
      // The whole sheet cannot carry a look on every cell inside the 1,000,000 byte limit of a stored sheet (about 5 MB), so
      // one cell in twelve carries one (about 4,000 looks, 40 kinds); the size 500 x 100 is the stress.
      const n = (Number(row.id.slice(1)) * 7 + j) % 40;
      if ((Number(row.id.slice(1)) + j) % 12 === 0) cells[`${row.id}:${column.id}`] = { value: String((n + 1) * 1.5), look: lookOf(n), kind: "fixed" };
    });
  }
  cells["h0:c000"] = { value: "Looks", look: { size: 16, fill: "#1f3864", colour: "#ffffff" } };
  return {
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        dimension: 2,
        rowsMode: "FIXED",
        columns,
        headerRows: [{ id: "h0", height: 28 }, { id: "h1", height: 24 }],
        rows,
        cells,
        merges: [{ from: "h0:c001", to: "h0:c004" }],
        freeze: { rows: 2, columns: 1 },
        conditions: [{ from: "r000:c001", to: "r099:c010", operator: "GT", value: "40", fill: "#ff9999" }],
      },
    ],
  };
}

/** The supplier-form shape without its text: a data sheet of 59 columns with frozen rows, 14 merged areas and conditions. */
function dataSheetShape() {
  const columns = Array.from({ length: 59 }, (_, i) => ({ id: `c${String(i).padStart(2, "0")}`, label: `Field ${i + 1}`, type: "text", width: 90 }));
  const rows = Array.from({ length: 40 }, (_, i) => ({ id: `r${String(i).padStart(2, "0")}`, label: `Row ${i + 1}` }));
  const cells = {};
  for (const row of rows) {
    columns.forEach((column, j) => {
      cells[`${row.id}:${column.id}`] = { value: String(j + 1), look: lookOf((j * 3 + Number(row.id.slice(1))) % 40) };
    });
  }
  const merges = Array.from({ length: 14 }, (_, i) => ({ from: `h0:c${String(i * 4).padStart(2, "0")}`, to: `h0:c${String(i * 4 + 1).padStart(2, "0")}` }));
  return {
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        dimension: 2,
        rowsMode: "FIXED",
        columns,
        headerRows: [{ id: "h0", height: 40 }, { id: "h1", height: 30 }],
        rows,
        cells,
        merges,
        freeze: { rows: 2, columns: 0 },
        conditions: [{ from: "r00:c05", to: "r39:c12", operator: "LT", value: "5", colour: "#c00000" }],
      },
    ],
  };
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ["--enable-precise-memory-info"] });
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: "light" });
  const page = await context.newPage();
  await page.addInitScript(() => {
    window.__violations = [];
    document.addEventListener("securitypolicyviolation", (event) => window.__violations.push(`${event.violatedDirective}`));
  });
  mkdirSync(PICTURES, { recursive: true });
  const shot = (target, name) => target.screenshot({ path: path.join(PICTURES, `${name}.png`) });

  // ---- sign in as a person does ----
  await page.goto(`${BASE}/sign-in`);
  await page.getByLabel("Email address").fill(EMAIL);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });

  /** Makes a template with one version holding the document; returns its id. */
  async function makeTemplate(label, document) {
    const created = await api(page, "POST", "/api/v1/objects/InspectionTemplate/records", { fields: { name: `${label} ${Date.now()}` } });
    const id = created.body?.data?.id;
    check(`${label}: a template is made`, created.status === 201, `status ${created.status}`);
    const started = await api(page, "POST", `/api/v1/sheets/InspectionTemplate/${id}/versions`, { dimension: 2 });
    check(`${label}: the first version is started`, started.status === 201, `status ${started.status}`);
    const saved = await api(page, "PUT", `/api/v1/sheets/InspectionTemplate/${id}/versions/1/grid?version=0`, JSON.stringify(document));
    check(`${label}: the sheet in format version 2 is saved`, saved.status === 200, `status ${saved.status} ${JSON.stringify(saved.body?.error ?? "")}`.slice(0, 200));
    return id;
  }

  /** Opens version 1 of a template's sheet and returns the grid. Gives the time from the click to the first cells drawn. */
  async function open(id, target = page) {
    await target.goto(`${BASE}/apps/inspection/s-templates?open=${id}&tab=sheet`);
    const opener = target.getByRole("button", { name: /^Open (draft|version 1|revision)/i }).first();
    await opener.waitFor({ timeout: 20000 });
    await target.evaluate(() => {
      window.__painted = undefined;
      const started = performance.now();
      const watch = new MutationObserver(() => {
        if (window.__painted === undefined && document.querySelector(".sheet-paper [role='gridcell']")) {
          window.__painted = performance.now() - started;
          watch.disconnect();
        }
      });
      watch.observe(document.body, { childList: true, subtree: true });
    });
    await opener.click();
    const grid = target.getByRole("grid");
    // a narrow window opens the form view first: the Grid tab shows the grid
    if (!(await grid.waitFor({ timeout: 4000 }).then(() => true, () => false))) {
      await target.getByRole("tab", { name: /^Grid/ }).click();
    }
    await grid.waitFor({ timeout: 30000 });
    await target.waitForFunction(() => window.__painted !== undefined, null, { timeout: 30000 });
    return { grid, paintedMs: await target.evaluate(() => window.__painted) };
  }

  const nextPaint = (target) => target.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));

  // ---- the largest sheet: T1 to T5, T8 ----
  const bigId = await makeTemplate("Look check (largest)", largestLookedSheet());
  const big = await open(bigId);
  const paintedMs = Math.round(big.paintedMs);
  check("T1 first paint of the largest looked sheet <= 1500 ms", paintedMs <= 1500, `${paintedMs} ms from the click`);
  check("the grid is a paper grid with its own look", (await big.grid.getAttribute("class")).includes("sheet-paper"));
  check("the grid counts its rows (labels, 2 header rows, 500 rows, the footer) and 100 columns for a reader", (await big.grid.getAttribute("aria-rowcount")) === "504" && (await big.grid.getAttribute("aria-colcount")) === "100",
    `rowcount ${await big.grid.getAttribute("aria-rowcount")}, colcount ${await big.grid.getAttribute("aria-colcount")}`);
  const drawn = await page.locator('[role="gridcell"]').count();
  check("T3 only the cells on the screen are drawn", drawn > 0 && drawn <= 1500, `${drawn} cells`);
  await shot(page, "synthetic-light");

  // T2: 60 steps down and across, time to drawn cells after each
  const steps = [];
  let heap1 = 0;
  for (let pass = 0; pass < 3; pass++) {
    for (let i = 0; i < 20; i++) {
      const spent = await page.evaluate(
        ([dy, dx]) =>
          new Promise((resolve) => {
            const el = document.querySelector('[role="grid"]');
            const t = performance.now();
            el.scrollBy(dx, dy);
            requestAnimationFrame(() => requestAnimationFrame(() => resolve(performance.now() - t)));
          }),
        [i % 2 === 0 ? 700 : -300, i % 3 === 0 ? 600 : 0],
      );
      steps.push(spent);
    }
    await big.grid.evaluate((el) => { el.scrollTop = 0; el.scrollLeft = 0; });
    await nextPaint(page);
    if (pass === 0) heap1 = await page.evaluate(() => Math.round(performance.memory.usedJSHeapSize / 1048576));
  }
  const p95 = Math.round(percentile(steps, 95));
  const worst = Math.round(Math.max(...steps));
  check("T2 scrolling: p95 <= 100 ms, worst <= 250 ms", p95 <= 100 && worst <= 250, `p95 ${p95} ms, worst ${worst} ms over ${steps.length} steps`);

  // T4: the selection moves with an arrow key
  await big.grid.focus();
  const keys = [];
  for (let i = 0; i < 40; i++) {
    const spent = await page.evaluate(
      (key) =>
        new Promise((resolve) => {
          const el = document.querySelector('[role="grid"]');
          const t = performance.now();
          el.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true }));
          requestAnimationFrame(() => requestAnimationFrame(() => resolve(performance.now() - t)));
        }),
      i % 2 === 0 ? "ArrowDown" : "ArrowRight",
    );
    keys.push(spent);
  }
  const keyP95 = Math.round(percentile(keys, 95));
  const keyWorst = Math.round(Math.max(...keys));
  check("T4 an arrow key to the next paint: p95 <= 50 ms, worst <= 150 ms", keyP95 <= 50 && keyWorst <= 150, `p95 ${keyP95} ms, worst ${keyWorst} ms`);

  // T5: memory after three passes
  const heap3 = await page.evaluate(() => Math.round(performance.memory.usedJSHeapSize / 1048576));
  check("T5 memory <= 250 MB and growth <= 50 MB", heap3 <= 250 && heap3 - heap1 <= 50, `after pass 1: ${heap1} MB, after pass 3: ${heap3} MB`);

  // T8: the frozen header stays where it belongs after a long scroll
  await big.grid.evaluate((el) => { el.scrollTop = 5000; el.scrollLeft = 2000; });
  await nextPaint(page);
  const places = await page.evaluate(() => {
    const grid = document.querySelector('[role="grid"]').getBoundingClientRect();
    const fixedRow = document.querySelector('.sheet-row-fixed [data-r="0"]');
    const secondRow = document.querySelector('.sheet-row-fixed [data-r="1"]');
    const fixedColumn = document.querySelector('.sheet-pane-left [data-c="0"][data-r]:not([data-r="0"]):not([data-r="1"])');
    const label = document.querySelector('.sheet-labels .sheet-label');
    return {
      grid: { top: grid.top, left: grid.left },
      first: fixedRow ? fixedRow.getBoundingClientRect().top : null,
      second: secondRow ? secondRow.getBoundingClientRect().top : null,
      column: fixedColumn ? fixedColumn.getBoundingClientRect().left : null,
      label: label ? label.getBoundingClientRect().top : null,
    };
  });
  const border = 1;
  const labelBand = 28;
  check("T8 the first fixed row stays right under the labels", places.first !== null && Math.abs(places.first - (places.grid.top + border + labelBand)) <= 1, `top ${places.first}, expected ${places.grid.top + border + labelBand}`);
  check("T8 the second fixed row follows it", places.second !== null && Math.abs(places.second - (places.first + 28)) <= 1, `top ${places.second}`);
  check("T8 the fixed column stays at the left edge", places.column !== null && Math.abs(places.column - (places.grid.left + border)) <= 1, `left ${places.column}, expected ${places.grid.left + border}`);
  check("T8 the labels stay at the top", places.label !== null && Math.abs(places.label - (places.grid.top + border)) <= 1, `top ${places.label}`);
  await shot(page, "synthetic-scrolled");
  await big.grid.evaluate((el) => { el.scrollTop = 0; el.scrollLeft = 0; });

  // T11: the ring and the wash over a dark fill, a light fill and a white cell
  const rings = await page.evaluate(() => {
    const luminance = (rgb) => {
      const [r, g, b] = rgb.match(/\d+/g).slice(0, 3).map((v) => { const c = Number(v) / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const ratio = (a, b) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    const out = [];
    for (const fill of ["rgb(31, 56, 100)", "rgb(255, 230, 153)", "rgb(255, 255, 255)"]) {
      const cell = [...document.querySelectorAll(".sheet-cell")].find((el) => getComputedStyle(el).backgroundColor === fill);
      if (!cell) { out.push({ fill, found: false }); continue; }
      cell.classList.add("sheet-active");
      const style = getComputedStyle(cell);
      const outline = style.outlineColor;
      const inner = style.boxShadow.match(/rgb\([^)]*\)/)?.[0] ?? "";
      const lum = luminance(style.backgroundColor);
      out.push({ fill, found: true, outline, inner, best: Math.max(ratio(lum, luminance(outline)), inner ? ratio(lum, luminance(inner)) : 0) });
      cell.classList.remove("sheet-active");
    }
    return out;
  });
  for (const ring of rings) {
    check(`T11 the active cell reads over ${ring.fill}`, ring.found && ring.best >= 3, ring.found ? `ring ${ring.outline} inside ${ring.inner}: ${ring.best.toFixed(1)}:1` : "no such cell drawn");
  }
  await big.grid.focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Shift+ArrowRight");
  await shot(page, "synthetic-focus");
  const kept = await page.evaluate(() => {
    const rgb = (hex) => { const n = parseInt(hex.slice(1), 16); return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`; };
    const cells = [...document.querySelectorAll(".sheet-cell.sheet-has-fill")].slice(0, 30);
    for (const el of cells) el.classList.add("sheet-selected");
    const wrong = cells.filter((el) => getComputedStyle(el).backgroundColor !== rgb(el.style.getPropertyValue("--sheet-fill"))).length;
    for (const el of cells) el.classList.remove("sheet-selected");
    return { found: cells.length, wrong };
  });
  check("a selected cell keeps its own fill (the selection is a wash over it, not a background)", kept.found > 0 && kept.wrong === 0, `${kept.found} selected cells with a fill, ${kept.wrong} changed`);

  // T9: a strict policy, light and dark, with the print view open
  await context.route("**/*", async (route) => {
    if (route.request().resourceType() !== "document") return route.continue();
    const response = await route.fetch({ url: route.request().url().replace("tenant-a.localhost", "127.0.0.1"), headers: { ...route.request().headers(), host: new URL(BASE).host } });
    await route.fulfill({
      response,
      headers: { ...response.headers(), "content-security-policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self' data:; connect-src 'self'" },
    });
  });
  for (const scheme of ["light", "dark"]) {
    await page.emulateMedia({ colorScheme: scheme });
    await open(bigId);
    await page.getByTestId("sheet-view").click();
    await page.getByTestId("sheet-print-view").waitFor({ timeout: 20000 });
    await page.waitForTimeout(500);
    const violations = await page.evaluate(() => window.__violations);
    check(`T9 under a strict policy (${scheme}) the grid and the print view are drawn and no style is refused`,
      (await page.locator("[role='gridcell'], .sheet-print-cell").count()) > 0 && violations.length === 0, `violations: ${[...new Set(violations)].join(", ") || "none"}`);
    await shot(page, `synthetic-print-${scheme}`);
    await page.keyboard.press("Escape");
  }
  await context.unroute("**/*");
  await page.emulateMedia({ colorScheme: "dark" });
  await open(bigId);
  const darkPage = await page.evaluate(() => ({
    page: getComputedStyle(document.body).backgroundColor,
    paper: getComputedStyle(document.querySelector(".sheet-paper")).backgroundColor,
  }));
  check("in the dark theme the sheet stays on white paper while the page is dark", darkPage.paper === "rgb(255, 255, 255)" && darkPage.page !== "rgb(255, 255, 255)", `page ${darkPage.page}, paper ${darkPage.paper}`);
  await shot(page, "synthetic-dark");
  await page.emulateMedia({ colorScheme: "light" });

  // 200 percent zoom keeps working (the width of the window halves)
  await page.setViewportSize({ width: 640, height: 400 });
  await open(bigId);
  check("at 200 percent zoom (a window half as wide) the grid is drawn and the page does not scroll sideways",
    (await page.locator("[role='gridcell']").count()) > 0 && (await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)));
  await page.setViewportSize({ width: 1280, height: 800 });

  // ---- T7: the shape of the data sheet ----
  const dataId = await makeTemplate("Look check (data sheet)", dataSheetShape());
  const data = await open(dataId);
  const dataMs = Math.round(data.paintedMs);
  check("T7 first paint of the data sheet <= 800 ms", dataMs <= 800, `${dataMs} ms from the click`);
  check("the fixed rows of the data sheet are kept", (await page.locator(".sheet-row-fixed").count()) === 2);

  // a phone: the grid is for reading
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  const small = await phone.newPage();
  await small.goto(`${BASE}/sign-in`);
  await small.getByLabel("Email address").fill(EMAIL);
  await small.getByLabel("Password").fill(PASSWORD);
  await small.getByRole("button", { name: /sign in/i }).click();
  await small.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });
  await small.goto(`${BASE}/apps/inspection/s-templates?open=${dataId}&tab=sheet`);
  await small.getByRole("button", { name: /^Open (draft|version 1|revision)/i }).first().click();
  await small.getByTestId("form-view").waitFor({ timeout: 20000 });
  await small.getByRole("tab", { name: "Grid view" }).click();
  check("on a phone the looked grid is read-only", (await small.getByRole("grid").getAttribute("aria-readonly")) === "true");
  check("on a phone the page does not scroll sideways", await small.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await shot(small, "synthetic-phone");
  await phone.close();

  // ---- the two real workbooks (--workbooks): pictures and T12 ----
  if (WORKBOOKS) {
    for (const [name, file, worksheets] of [["plant", PLANT, [0, 1]], ["spm", SPM, [2]]]) {
      if (!existsSync(file)) {
        check(`the ${name} workbook is found`, false, "not found: set PLANT_WORKBOOK / SPM_WORKBOOK");
        continue;
      }
      const bytes = readFileSync(file).toString("base64");
      await ensureForgeryCookie(page);
      for (const worksheet of worksheets) {
        const created = await api(page, "POST", "/api/v1/objects/InspectionTemplate/records", { fields: { name: `Look check ${name} ${worksheet} ${Date.now()}` } });
        const id = created.body?.data?.id;
        await ensureForgeryCookie(page); // the server rotates the forgery cookie after every write
        const imported = await page.evaluate(
          async ([id, base64, worksheet]) => {
            const csrf = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length) ?? "";
            const body = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
            const response = await fetch(`/api/v1/sheets/InspectionTemplate/${id}/versions/import?format=xlsx&preview=false&worksheet=${worksheet}`, {
              method: "POST",
              headers: { "Content-Type": "application/octet-stream", "X-XSRF-TOKEN": decodeURIComponent(csrf) },
              body,
            });
            return { status: response.status, text: (await response.text()).slice(0, 160) };
          },
          [id, bytes, worksheet],
        );
        check(`${name} worksheet ${worksheet}: the workbook is uploaded and saved as a draft`, imported.status === 200 || imported.status === 201, `status ${imported.status} ${imported.text ?? ""}`);
        await open(id);
        const stored = await api(page, "GET", `/api/v1/sheets/InspectionTemplate/${id}/versions/1`);
        const sheet = stored.body?.data?.grid?.sheets?.[0] ?? stored.body?.data?.grid;
        // T12: every cell drawn takes the fill and the text colour the sheet states (a cell a rule paints is left out)
        const report = await page.evaluate(
          async ([sheet]) => {
            const rgb = (hex) => { const n = parseInt(hex.slice(1), 16); return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`; };
            const gridEl = document.querySelector('[role="grid"]');
            const order = [...(sheet.headerRows ?? []), ...(sheet.rows ?? [])].map((line) => line.id);
            const columnIds = sheet.columns.map((c) => c.id);
            const conditioned = new Set();
            let compared = 0;
            let different = 0;
            const checkDrawn = () => {
              for (const el of document.querySelectorAll(".sheet-cell[data-r]")) {
                const key = `${order[Number(el.dataset.r)]}:${columnIds[Number(el.dataset.c)]}`;
                if (conditioned.has(key) || el.classList.contains("sheet-selected")) continue;
                const look = sheet.cells?.[key]?.look ?? {};
                const style = getComputedStyle(el);
                const wantFill = look.fill ? rgb(look.fill) : "rgb(255, 255, 255)";
                compared++;
                if (style.backgroundColor !== wantFill) different++;
                if (look.colour && style.color !== rgb(look.colour)) different++;
              }
            };
            const height = gridEl.scrollHeight;
            const width = gridEl.scrollWidth;
            for (let y = 0; y < height; y += Math.max(200, gridEl.clientHeight - 100)) {
              for (let x = 0; x < width; x += Math.max(300, gridEl.clientWidth - 100)) {
                gridEl.scrollTo(x, y);
                await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                checkDrawn();
              }
            }
            gridEl.scrollTo(0, 0);
            return { compared, different };
          },
          [sheet],
        );
        check(`T12 ${name} worksheet ${worksheet}: the fills and text colours drawn equal the stored ones`, report.compared > 0 && report.different === 0, `${report.compared} cells compared, ${report.different} different`);
        await shot(page, `${name}-${worksheet}-light`);
        await page.emulateMedia({ colorScheme: "dark" });
        await nextPaint(page);
        await shot(page, `${name}-${worksheet}-dark`);
        await page.emulateMedia({ colorScheme: "light" });
        await page.getByTestId("sheet-view").click();
        await page.getByTestId("sheet-print-view").waitFor({ timeout: 20000 });
        await shot(page, `${name}-${worksheet}-print`);
        await page.keyboard.press("Escape");
      }
    }
  }
} finally {
  await browser.close();
}

console.log(failed === 0 ? "\nAll checks passed." : `\n${failed} check(s) FAILED.`);
process.exit(failed === 0 ? 0 : 1);
