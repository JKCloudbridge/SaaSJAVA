// The kept browser script of the sheet grid (Functional track FA3, ADR-0172). It drives the PRODUCTION BUILD of the web
// application in a real Chromium-based browser against a RUNNING backend, the way a person would, and checks what a
// jsdom test cannot: that only the rows and columns on the screen are drawn at the largest size, that the grid works under
// a strict content policy, that it follows dark mode, that it fits a phone, and that the keyboard and the clipboard work in
// a real browser. It is not part of CI (it needs the services, the backend, a build and a browser). See README.md.
//
// It prints one line per check ("ok" or "FAIL"), writes screenshots to ./results, and exits with 1 if any check failed.
// Nothing here is a name of a person or a product: the organization is the local one (tenant-a) and the person its seeded
// administrator.
import { existsSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const BASE = (process.env.BASE_URL ?? "http://tenant-a.localhost:3000").replace(/\/$/, "");
const EMAIL = process.env.ADMIN_EMAIL ?? "admin-a@example.test";
const PASSWORD = process.env.LOCAL_SEED_PASSWORD;
const CHROME =
  process.env.CHROME_PATH ??
  [
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/google-chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ].find((path) => existsSync(path));

if (!PASSWORD) {
  console.error("Set LOCAL_SEED_PASSWORD to the password of the seeded administrator (infra/local/.env).");
  process.exit(2);
}

mkdirSync(new URL("./results/", import.meta.url), { recursive: true });
const shot = (page, name) => page.screenshot({ path: fileURLToPath(new URL(`./results/${name}.png`, import.meta.url)) });

let failed = 0;
const check = (name, ok, detail = "") => {
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
};

// ---- the API, called from the browser's own session (cookies and the forgery header), to make the data to look at ----
//
// The server rotates the forgery cookie after every state-changing request, exactly as the real page's own client
// does (`ensureForgeryCookie` in `lib/session/SessionProvider.tsx`): a GET of `/api/v1/auth/csrf` refreshes it when
// it is missing. A script that reused a cookie read before an earlier write would be refused for no reason of its
// own; this follows the page's own rule so a check never reports a failure the real page would not have.

async function ensureForgeryCookie(page) {
  const hasToken = await page.evaluate(() => document.cookie.includes("XSRF-TOKEN="));
  if (!hasToken) {
    await page.evaluate(() => fetch("/api/v1/auth/csrf"));
  }
}

async function api(page, method, path, body) {
  if (method !== "GET") {
    await ensureForgeryCookie(page);
  }
  return page.evaluate(
    async ([method, path, body]) => {
      const csrf = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length) ?? "";
      const response = await fetch(path, {
        method,
        headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(csrf) },
        body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, body: text ? JSON.parse(text) : null };
    },
    [method, path, body],
  );
}

/** The largest grid the product allows, stored sparsely: 500 rows by 100 columns, a title band, a label in each row. */
function largestGrid() {
  const columns = Array.from({ length: 100 }, (_, i) => ({ id: `c${String(i).padStart(3, "0")}`, label: `Column ${i + 1}`, type: "text" }));
  const rows = Array.from({ length: 500 }, (_, i) => ({ id: `r${String(i).padStart(3, "0")}`, label: `Row ${i + 1}` }));
  const cells = { "h0:c000": { value: "Inspection of installations", style: { bold: true, fontSize: "TITLE" } } };
  for (const row of rows) cells[`${row.id}:c000`] = { value: row.label };
  return {
    formatVersion: 1,
    dimension: 2,
    rowsMode: "FIXED",
    columns,
    headerRows: [{ id: "h0" }],
    rows,
    cells,
    merges: [{ from: "h0:c000", to: "h0:c009" }],
  };
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: "light" });
  const page = await context.newPage();
  await page.addInitScript(() => {
    window.__violations = [];
    document.addEventListener("securitypolicyviolation", (event) => window.__violations.push(`${event.violatedDirective}`));
  });

  // ---- sign in as a person does ----
  await page.goto(`${BASE}/sign-in`);
  await page.getByLabel("Email address").fill(EMAIL);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });

  // ---- make a template with the largest sheet ----
  const created = await api(page, "POST", "/api/v1/objects/InspectionTemplate/records", { fields: { name: `Browser check ${Date.now()}` } });
  check("a template is made", created.status === 201, `status ${created.status}`);
  const id = created.body?.data?.id;
  const started = await api(page, "POST", `/api/v1/sheets/InspectionTemplate/${id}/versions`, { dimension: 2 });
  check("the first version is started", started.status === 201, `status ${started.status}`);
  const saved = await api(page, "PUT", `/api/v1/sheets/InspectionTemplate/${id}/versions/1/grid?version=0`, JSON.stringify(largestGrid()));
  check("the largest sheet (500 x 100) is saved", saved.status === 200, `status ${saved.status} ${JSON.stringify(saved.body?.error ?? "")}`.slice(0, 200));

  // ---- the page, with the grid (the Sheet tab lists the versions first; one is opened) ----
  await page.goto(`${BASE}/apps/inspection/templates?open=${id}&tab=sheet`);
  await page.getByRole("button", { name: "Open version 1" }).click();
  const grid = page.getByRole("grid");
  await grid.waitFor({ timeout: 20000 });
  check("the grid counts its 502 rows (the labels, a header row, 500 rows) and 100 columns for a reader", (await grid.getAttribute("aria-rowcount")) === "502" && (await grid.getAttribute("aria-colcount")) === "100",
    `rowcount ${await grid.getAttribute("aria-rowcount")}, colcount ${await grid.getAttribute("aria-colcount")}`);

  // 1. only what is on the screen is drawn
  const drawn = await page.locator('[role="gridcell"]').count();
  check("only the cells on the screen are drawn at 500 x 100", drawn > 0 && drawn < 800, `${drawn} of 50,000 cells in the page`);
  const heap = await page.evaluate(() => (performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : -1));
  console.log(`info heap after load: ${heap} MB`);

  // 2. scrolling reaches the far corner and draws it
  await grid.evaluate((el) => { el.scrollTop = el.scrollHeight; el.scrollLeft = el.scrollWidth; });
  await page.waitForTimeout(400);
  check("scrolling to the far corner draws the last column and the last row",
    (await page.getByRole("columnheader", { name: "Column 100" }).count()) === 1 && (await page.getByRole("row", { name: "Row 500" }).count()) === 1);
  const afterScroll = await page.locator('[role="gridcell"]').count();
  check("and still only the cells on the screen", afterScroll < 800, `${afterScroll} cells`);
  await grid.evaluate((el) => { el.scrollTop = 0; el.scrollLeft = 0; });
  await page.waitForTimeout(300);

  // 3. the keyboard, in a real browser: type into a cell, bold, undo
  await grid.focus();
  await page.keyboard.press("Control+Home");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.type("Limit 5", { delay: 20 });
  await page.keyboard.press("Enter");
  check("typing writes fixed text into a cell", (await page.locator('[role="gridcell"]', { hasText: "Limit 5" }).count()) === 1);
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Control+b");
  check("Ctrl+B makes the text bold", (await page.locator(".sheet-bold", { hasText: "Limit 5" }).count()) === 1);
  await page.keyboard.press("Control+z");
  await page.keyboard.press("Control+z");
  check("Ctrl+Z undoes twice", (await page.locator('[role="gridcell"]', { hasText: "Limit 5" }).count()) === 0);

  // 4. paste a block copied from a spreadsheet application
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: BASE });
  await page.evaluate(() => navigator.clipboard.writeText("a\tb\nc\td"));
  await grid.focus();
  await page.keyboard.press("Control+Home");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Control+v");
  await page.waitForTimeout(200);
  check("a pasted block is written, one step", (await page.getByRole("status").filter({ hasText: "Pasted 4 cells" }).count()) >= 1);
  await page.keyboard.press("Control+z");

  // 5. no request goes out because of the grid (no library fetching anything), and no policy is violated
  const requests = [];
  page.on("request", (request) => requests.push(request.url()));
  await grid.focus();
  for (let i = 0; i < 10; i++) await page.keyboard.press("ArrowDown");
  await page.waitForTimeout(500);
  check("moving around the grid makes no request", requests.length === 0, requests.slice(0, 3).join(", "));

  // 6. a strict policy: inline styles refused, scripts only from the page's own origin
  await context.route("**/*", async (route) => {
    if (route.request().resourceType() !== "document") return route.continue();
    const response = await route.fetch();
    await route.fulfill({
      response,
      headers: { ...response.headers(), "content-security-policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self' data:; connect-src 'self'" },
    });
  });
  await page.goto(`${BASE}/apps/inspection/templates?open=${id}&tab=sheet`);
  await page.getByRole("button", { name: "Open version 1" }).click();
  await grid.waitFor({ timeout: 20000 });
  await page.waitForTimeout(500);
  const strict = await page.evaluate(() => window.__violations);
  const styleViolations = strict.filter((v) => v.startsWith("style-src"));
  check("under a strict policy the grid is drawn and no style is refused", (await page.locator('[role="gridcell"]').count()) > 0 && styleViolations.length === 0,
    `violations: ${[...new Set(strict)].join(", ") || "none"}`);
  await context.unroute("**/*");
  await shot(page, "light");

  // 7. dark mode through the tokens
  const lightBackground = await page.evaluate(() => getComputedStyle(document.querySelector(".sheet-grid")).backgroundColor);
  await page.emulateMedia({ colorScheme: "dark" });
  await page.waitForTimeout(300);
  const darkBackground = await page.evaluate(() => getComputedStyle(document.querySelector(".sheet-grid")).backgroundColor);
  const luminance = (rgb) => { const [r, g, b] = rgb.match(/\d+/g).map(Number); return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255; };
  check("dark mode darkens the grid", luminance(darkBackground) < 0.35 && luminance(lightBackground) > 0.65, `light ${lightBackground}, dark ${darkBackground}`);
  await shot(page, "dark");
  await page.emulateMedia({ colorScheme: "light" });

  // 8. a phone: the page fits, the grid is for reading and says so, the form view is the way to build
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  const small = await phone.newPage();
  await small.goto(`${BASE}/sign-in`);
  await small.getByLabel("Email address").fill(EMAIL);
  await small.getByLabel("Password").fill(PASSWORD);
  await small.getByRole("button", { name: /sign in/i }).click();
  await small.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });
  await small.goto(`${BASE}/apps/inspection/templates?open=${id}&tab=sheet`);
  await small.getByRole("button", { name: "Open version 1" }).click();
  await small.getByTestId("form-view").waitFor({ timeout: 20000 });
  check("on a phone the form view opens first", true);
  const fits = await small.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  check("on a phone the page does not scroll sideways", fits);
  await small.getByRole("tab", { name: "Grid view" }).click();
  const readOnly = (await small.getByRole("grid").getAttribute("aria-readonly")) === "true";
  check("on a phone the grid is read-only", readOnly);
  check("on a phone the page says how to build", (await small.getByText(/narrow screen the grid is for reading/).count()) >= 1);
  await small.locator('[role="gridcell"][data-r="1"][data-c="0"]').tap();
  check("a tap selects a cell", (await small.locator(".sheet-active").count()) === 1);
  await shot(small, "phone");
  await phone.close();

  // 9. the master list and the publish dialog (Functional track FA4): a template is made, sent for approval, numbered and
  // approved through the API (the same calls the buttons make), then the list and the dialog are used as a person does.
  const stamp = Date.now();
  const members = await api(page, "GET", "/api/v1/members?limit=200");
  const membership = (members.body?.data ?? []).find((member) => member.email === "user-a@example.test")?.membershipId
    ?? (members.body?.data ?? [])[0]?.membershipId;
  const group = await api(page, "POST", "/api/v1/groups", { name: `Browser check group ${stamp}`, description: "" });
  check("a group is made for a department", group.status === 201, `status ${group.status}`);
  if (membership) await api(page, "POST", `/api/v1/groups/${group.body?.data?.id}/members`, { membershipId: membership });
  const mapping = await api(page, "POST", "/api/v1/objects/DepartmentGroup/records", {
    fields: { name: `Browser check ${stamp}`, location: "PLANT1", department: "PRD", groupId: group.body?.data?.id },
  });
  check("the group is set up for Plant 1, Production", mapping.status === 201, `status ${mapping.status} ${JSON.stringify(mapping.body?.error ?? "")}`.slice(0, 200));
  const processName = `Check${stamp}`;
  const process = await api(page, "POST", "/api/v1/metadata/approvals/InspectionTemplate", {
    name: processName, label: processName, description: "", active: true, entryCriteria: null, allowSelfApproval: true,
    steps: [{ name: "Only", label: "Only", completion: "ANY", approvers: [{ kind: "OWNER" }] }], outcomes: null, fallback: null,
  });
  check("an approval process is made", process.status === 201, `status ${process.status} ${JSON.stringify(process.body?.error ?? "")}`.slice(0, 200));
  const lifecycle = await api(page, "POST", "/api/v1/objects/InspectionTemplate/records", {
    fields: { name: `Lifecycle check ${stamp}`, category: "WI", subCategory: "PRD" },
  });
  const lifeId = lifecycle.body?.data?.id;
  await api(page, "POST", `/api/v1/sheets/InspectionTemplate/${lifeId}/versions`, { dimension: 2 });
  const lifeGrid = largestGrid();
  lifeGrid.columns = lifeGrid.columns.slice(0, 3);
  lifeGrid.rows = lifeGrid.rows.slice(0, 5);
  lifeGrid.cells = { "r000:c000": { value: "First" } };
  lifeGrid.merges = [];
  await api(page, "PUT", `/api/v1/sheets/InspectionTemplate/${lifeId}/versions/1/grid?version=0`, JSON.stringify(lifeGrid));
  const submitted = await api(page, "POST", `/api/v1/objects/InspectionTemplate/records/${lifeId}/approval`, { process: processName, comment: null });
  check("the template is sent for approval", submitted.status === 201, `status ${submitted.status} ${JSON.stringify(submitted.body?.error ?? "")}`.slice(0, 200));
  const requestId = submitted.body?.data?.summary?.id;
  const requestVersion = submitted.body?.data?.summary?.version ?? 0;
  const decided = await api(page, "POST", `/api/v1/approvals/instances/${requestId}/approve`, { version: requestVersion, comment: null });
  check("the template is approved and takes its number", decided.status === 200, `status ${decided.status} ${JSON.stringify(decided.body?.error ?? "")}`.slice(0, 200));

  await page.goto(`${BASE}/apps/inspection/templates`);
  const list = page.getByTestId("templates-list");
  await list.waitFor({ timeout: 20000 });
  const row = list.getByRole("row").filter({ hasText: `Lifecycle check ${stamp}` });
  check("the master list shows the template with its number and 'approved, not published'",
    (await row.count()) === 1 && /WI\/PRD\/\d+/.test(await row.innerText()) && /Approved, not published/.test(await row.innerText()), (await row.innerText().catch(() => "")).replace(/\s+/g, " "));
  await page.getByRole("searchbox").fill(`Lifecycle check ${stamp}`);
  await page.waitForTimeout(300);
  check("the search narrows the list to the template", (await list.getByRole("row").count()) === 2, `${await list.getByRole("row").count()} rows with the header`);
  await shot(page, "templates-list");

  await row.getByRole("button", { name: `Lifecycle check ${stamp}` }).click();
  await page.getByTestId("template-detail").waitFor({ timeout: 20000 });
  await page.getByRole("button", { name: "Publish" }).click();
  const dialog = page.getByTestId("publish-dialog");
  await dialog.waitFor({ timeout: 10000 });
  check("the department list waits for a location", await dialog.getByLabel("Department").isDisabled());
  await dialog.getByLabel("Location").selectOption("PLANT1");
  const offered = await dialog.getByLabel("Department").locator("option").allInnerTexts();
  check("the departments narrow to the location", offered.some((text) => /Production/.test(text)) && !offered.some((text) => /Human resources/.test(text)), offered.join(" | "));
  await dialog.getByLabel("Department").selectOption("PRD");
  await dialog.getByRole("button", { name: "Add this department" }).click();
  await shot(page, "publish-dialog");
  await dialog.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByText(/published to the people of the departments you chose/).waitFor({ timeout: 15000 });
  check("the template is published and the page lists its audience", (await page.getByTestId("audience").count()) === 1 && /Plant 1/.test(await page.getByTestId("audience").innerText()));
  check("and its status is Active", /Active/.test(await page.getByTestId("lifecycle-text").innerText()));
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Unpublish" }).click();
  await page.getByText(/was taken back from the people/).waitFor({ timeout: 15000 });
  check("Unpublish takes it back", (await page.getByTestId("audience-none").count()) === 1);

  // 10. change requests (Functional track FA5, ADR-0191): the template is published again, and a person points at a column of
  // the version in use, writes a comment, sends the request, sees it in the list with the column marked, and the owner
  // accepts it. The grid stays read-only throughout; the keyboard reaches the box of a comment from the grid.
  await page.getByRole("button", { name: "Publish" }).click();
  const again = page.getByTestId("publish-dialog");
  await again.waitFor({ timeout: 10000 });
  await again.getByLabel("Location").selectOption("PLANT1");
  await again.getByLabel("Department").selectOption("PRD");
  await again.getByRole("button", { name: "Add this department" }).click();
  await again.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByText(/published to the people of the departments you chose/).waitFor({ timeout: 15000 });
  await page.goto(`${BASE}/apps/inspection/templates?open=${lifeId}&tab=sheet`);
  await page.getByRole("button", { name: "Open version 1" }).click();
  const panel = page.getByTestId("annotate-panel");
  await panel.waitFor({ timeout: 15000 });
  check("a reader of the version in use is offered the panel to ask for a change", (await panel.count()) === 1);
  check("and the grid is read-only", (await page.getByRole("grid").getAttribute("aria-readonly")) === "true");
  await page.getByRole("columnheader", { name: /Column 2/ }).click();
  check("a click on a column label stands on that column",
    (await page.locator(".sheet-active").first().getAttribute("data-c")) === "1");
  await panel.getByRole("button", { name: "Comment on this column" }).click();
  await panel.getByRole("textbox", { name: /Your comment/ }).fill("This column is too narrow for the readings");
  await panel.getByRole("button", { name: "Add to my request" }).click();
  await panel.getByLabel("What is the request about?").fill(`Widen the column ${stamp}`);
  await panel.getByRole("button", { name: "Send the request" }).click();
  await page.getByText("Your request was sent to the people who look after this template.").waitFor({ timeout: 15000 });
  const changeList = page.getByTestId("change-requests");
  await changeList.getByRole("heading", { name: `Widen the column ${stamp}` }).waitFor({ timeout: 15000 });
  check("the request is listed with its comment and the place it points at",
    /Column “Column 2”/.test(await changeList.innerText()) && /too narrow for the readings/.test(await changeList.innerText()));
  check("the column is marked in the grid", (await page.locator(".sheet-label.sheet-noted", { hasText: "Column 2" }).count()) === 1);
  await page.getByRole("grid").focus();
  await page.keyboard.press("Enter");
  check("Enter on a cell takes the keyboard to the box of a comment",
    (await page.evaluate(() => document.activeElement?.tagName)) === "TEXTAREA");
  await shot(page, "change-request");
  await changeList.getByRole("button", { name: "Accept" }).click();
  await page.getByText("The request was accepted.").waitFor({ timeout: 15000 });
  check("the owner accepts the request", /Accepted/.test(await changeList.innerText()));
  // 11. a fill-in (Functional track FF1, ADR-0192): started from the template that section 10 left published, filled in
  // as a person does (a cell the template fixes is not opened, an input cell takes the typed value), saved, and read back
  // through the API against the version it was started on; then opened on a phone, where the form view comes first.
  await page.goto(`${BASE}/apps/inspection/s-templates?open=${lifeId}&tab=fill-ins`);
  await page.getByRole("button", { name: "Start a fill-in" }).click();
  await page.waitForURL(/s-fill-ins\?open=/, { timeout: 20000 });
  const fillGrid = page.getByRole("grid");
  await fillGrid.waitFor({ timeout: 20000 });
  const fillId = new URL(page.url()).searchParams.get("open");
  check("a fill-in is started on the version in use and opens in the fill-ins page", Boolean(fillId));
  await page.locator('[data-r="1"][data-c="0"]').dblclick();
  check("a cell the template fixes is not opened",
    (await page.getByText("This cell is fixed text and cannot be typed into.").count()) >= 1 && (await page.locator(".sheet-input").count()) === 0);
  await page.locator('[data-r="1"][data-c="1"]').dblclick();
  await page.keyboard.type("Looks fine");
  await page.keyboard.press("Enter");
  check("a typed value stays in an input cell", (await page.locator('[data-r="1"][data-c="1"]').innerText()).includes("Looks fine"));
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByText("All changes are saved.").waitFor({ timeout: 15000 });
  const stored = await api(page, "GET", `/api/v1/sheet-fills/InspectionFill/${fillId}`);
  check("the value is stored against the version the fill-in was started on",
    stored.body?.data?.cells?.rows?.[0]?.cells?.c001 === "Looks fine" && stored.body?.data?.fill?.versionNo === 1,
    JSON.stringify(stored.body?.data?.cells ?? stored.body?.error ?? "").slice(0, 160));
  await shot(page, "fill-in");
  const fillPhone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  const fillSmall = await fillPhone.newPage();
  await fillSmall.goto(`${BASE}/sign-in`);
  await fillSmall.getByLabel("Email address").fill(EMAIL);
  await fillSmall.getByLabel("Password").fill(PASSWORD);
  await fillSmall.getByRole("button", { name: /sign in/i }).click();
  await fillSmall.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });
  await fillSmall.goto(`${BASE}/apps/inspection/s-fill-ins?open=${fillId}`);
  await fillSmall.getByTestId("fill-form").waitFor({ timeout: 20000 });
  check("on a phone the fill-in opens in the form view, the way to fill", (await fillSmall.getByTestId("fill-form").count()) === 1);
  check("on a phone the page does not scroll sideways",
    await fillSmall.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await shot(fillSmall, "fill-in-phone");
  await fillPhone.close();
} catch (error) {
  failed++;
  console.log(`FAIL the script stopped: ${String(error).split("\n")[0]}`);
} finally {
  await browser.close();
}
console.log(failed === 0 ? "\nall checks passed" : `\n${failed} check(s) FAILED`);
process.exit(failed === 0 ? 0 : 1);
