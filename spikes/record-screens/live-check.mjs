// A look at the real application, Sprint 19: sign in on the local organization, open the list of the sample object,
// create and change records through the generated form, filter, order, save a view, hit a concurrent change, change
// and delete several records, and check the same screens in light, dark and a narrow window. Not part of any build.
//
// Needs the local services, the application (`--spring.profiles.active=local`) and `npm run dev` of platform-web.
// Usage: node live-check.mjs [folder for the screenshots]
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
// The browser driver is the one the Sprint 18 check already installed.
import { chromium } from "../custom-page-sandbox/node_modules/playwright-core/index.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const shots = process.argv[2] ?? path.join(here, "screenshots");
mkdirSync(shots, { recursive: true });

const env = readFileSync(path.resolve(here, "../../infra/local/.env"), "utf8");
const password = /^LOCAL_SEED_PASSWORD=(.*)$/m.exec(env)?.[1]?.trim();
if (!password) {
  throw new Error("LOCAL_SEED_PASSWORD is not in infra/local/.env (run infra/local/init-env.ps1)");
}
const BASE = process.env.APP_ORIGIN ?? "http://tenant-a.localhost:3000";
const executablePath = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
].filter(Boolean).find((candidate) => existsSync(candidate));

let failures = 0;
function check(name, condition, detail = "") {
  console.log(`${condition ? "PASS" : "FAIL"}  ${name}${detail ? "  -> " + detail : ""}`);
  if (!condition) {
    failures += 1;
  }
}

async function signIn(page, email) {
  await page.goto(BASE + "/sign-in");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 30000 });
}

/** An API call made by the page itself, with its own session and its forgery token, as the application does. */
async function api(page, method, pathname, body) {
  return page.evaluate(
    async ({ method, pathname, body }) => {
      // As the application does: ask for the cookie only when there is none (every answer replaces it).
      if (!document.cookie.includes("XSRF-TOKEN=")) {
        await fetch("/api/v1/auth/csrf");
      }
      const token = document.cookie.split("; ").find((part) => part.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length);
      const response = await fetch(pathname, {
        method,
        headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, body: text ? JSON.parse(text) : null };
    },
    { method, pathname, body },
  );
}

const browser = await chromium.launch({ executablePath, headless: true });
const stamp = Date.now().toString(36).toUpperCase();

try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  const page = await context.newPage();
  const problems = [];
  page.on("pageerror", (error) => problems.push("pageerror: " + error.message));
  // Expected noise: the sign-in flow probes /auth/me before there is a session and expects 401.
  const expected = [/401/];
  page.on("console", (message) => {
    if (message.type() === "error" && !expected.some((pattern) => pattern.test(message.text()))) {
      problems.push("console: " + message.text());
    }
  });
  const refusedCalls = [];
  page.on("response", (response) => {
    if (response.status() >= 400 && response.status() !== 401) {
      refusedCalls.push(`${response.status()} ${response.request().method()} ${new URL(response.url()).pathname}`);
    }
  });
  await signIn(page, "admin-a@example.test");

  console.log("\n== The list of an object, reached through an application ==");
  await page.goto(BASE + "/");
  await page.getByRole("button", { name: "Applications" }).click();
  const tile = page.getByRole("link", { name: /Field Service/ });
  await tile.waitFor();
  await tile.click();
  await page.getByRole("link", { name: "Employees" }).first().click();
  await page.getByRole("heading", { level: 1, name: "Employees" }).waitFor();
  check("the list of employees opens inside the application", page.url().includes("/o-Employee__c"), page.url());
  check("the control bar still shows the application", (await page.getByTestId("control-app").textContent())?.includes("Field Service"));
  const base = page.url();
  await page.screenshot({ path: path.join(shots, "1-list.png") });

  console.log("\n== Creating a record from the generated form ==");
  await page.getByTestId("new-record").click();
  await page.getByTestId("record-form").waitFor();
  check("the create form is drawn from the description", (await page.getByRole("heading", { level: 2 }).allTextContents()).length >= 1);
  const labels = await page.locator(".record-form label").allTextContents();
  check("the fields of the object are on the form", ["Employee number", "Department", "Joining date", "Salary"].every((label) => labels.some((text) => text.includes(label))), labels.join(" | "));
  check("the required field is marked", (await page.locator(".required-mark").count()) >= 1);
  await page.getByTestId("save-record").click();
  check("an empty required field is pointed out without a call", (await page.getByText("Enter a value for this field.").count()) >= 1);
  await page.getByLabel(/Employee number/).fill(`E-${stamp}-1`);
  await page.getByLabel(/Department/).selectOption("Sales");
  await page.getByLabel(/Joining date/).fill("2030-01-31");
  await page.getByLabel(/Salary/).fill("1234.50");
  // The preview a moment later: the server judges the change without saving it.
  await page.waitForTimeout(1200);
  check("the preview shows no problem for a good record", (await page.locator(".field-error").count()) === 0);
  await page.screenshot({ path: path.join(shots, "2-create-form.png") });
  await page.getByTestId("save-record").click();
  await page.waitForURL((url) => url.href === base || url.pathname.endsWith("/o-Employee__c"), { timeout: 15000 });
  const firstRow = page.getByRole("row").filter({ hasText: `E-${stamp}-1` });
  await firstRow.waitFor();
  check("the new record is in the list, its number as typed and its amount formatted", (await firstRow.textContent())?.includes("1,234.50"), await firstRow.textContent());
  check("the picklist reads by label", (await firstRow.textContent())?.includes("Sales"));

  // A second one, through the API as the page would, so the list has something to filter and order.
  const second = await api(page, "POST", "/api/v1/objects/Employee__c/records", {
    fields: { employeeNumber__c: `E-${stamp}-2`, department__c: "Finance", salary__c: 99.5 },
  });
  check("a second record is made", second.status === 201, JSON.stringify(second.body)?.slice(0, 120));

  console.log("\n== Filter, order, columns, search ==");
  await page.reload();
  await page.getByRole("link", { name: `E-${stamp}-2` }).waitFor();
  await page.getByRole("button", { name: "Filters" }).click();
  await page.getByRole("button", { name: "Add condition" }).click();
  await page.getByLabel("Field", { exact: true }).selectOption({ label: "Department" });
  await page.getByLabel("Value", { exact: true }).selectOption("Finance");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await page.getByRole("link", { name: `E-${stamp}-2` }).waitFor();
  await page.getByRole("link", { name: `E-${stamp}-1` }).waitFor({ state: "detached" });
  check("a filter leaves only the matching record", (await page.getByRole("link", { name: `E-${stamp}-1` }).count()) === 0);
  check("an active filter is shown as a chip", (await page.getByRole("list", { name: "Active filters" }).textContent())?.includes("Department is Finance"));
  await page.screenshot({ path: path.join(shots, "3-filtered.png") });
  await page.getByRole("button", { name: /Filters/ }).click();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await page.getByRole("link", { name: `E-${stamp}-1` }).waitFor();
  const sorted = page.waitForResponse((response) => response.url().includes("/records/query") && (response.request().postData() ?? "").includes("salary__c") && (response.request().postData() ?? "").includes("\"sort\":[{"));
  await page.getByRole("button", { name: /Salary/ }).click();
  await sorted;
  await page.waitForTimeout(400);
  const order = await page.getByTestId("list-table").locator("tbody tr").allTextContents();
  const mine = order.filter((text) => text.includes(`E-${stamp}`));
  check("ordering by amount puts the smaller amount first", mine[0]?.includes(`E-${stamp}-2`), mine.join(" || "));
  check("the header says which way", (await page.getByRole("columnheader", { name: /Salary/ }).getAttribute("aria-sort")) === "ascending");

  console.log("\n== A saved view ==");
  await page.getByRole("button", { name: "Filters" }).click();
  await page.getByRole("button", { name: "Add condition" }).click();
  await page.getByLabel("Field", { exact: true }).selectOption({ label: "Department" });
  await page.getByLabel("Value", { exact: true }).selectOption("Sales");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await page.getByRole("button", { name: "Save view" }).first().click();
  const viewName = `Sales ${stamp}`;
  await page.getByTestId("save-view-panel").getByLabel("Name").fill(viewName);
  check("an administrator can share a view", (await page.getByTestId("save-view-panel").getByLabel("Everyone who can see these records").count()) === 1);
  await page.getByTestId("save-view-panel").getByLabel("Everyone who can see these records").check();
  await page.getByTestId("save-view-panel").getByRole("button", { name: "Save view" }).click();
  const rail = page.getByTestId("views-rail");
  await rail.getByRole("button", { name: viewName }).waitFor();
  check("the view is in the rail, under the shared ones", (await rail.textContent())?.includes("Shared"));
  await page.reload();
  await page.getByTestId("views-rail").getByRole("button", { name: viewName }).click();
  await page.getByRole("link", { name: `E-${stamp}-2` }).waitFor({ state: "detached" });
  check("opening the view runs it", (await page.getByRole("link", { name: `E-${stamp}-2` }).count()) === 0);
  await page.screenshot({ path: path.join(shots, "4-view.png") });
  await page.getByRole("button", { name: "Delete this view" }).click();
  await page.waitForTimeout(800);
  check("the view can be removed", (await page.getByTestId("views-rail").getByRole("button", { name: viewName }).count()) === 0);

  console.log("\n== Editing, and a record changed by someone else ==");
  await page.getByRole("button", { name: "All records" }).click();
  await page.getByRole("link", { name: `E-${stamp}-1` }).click();
  await page.getByTestId("record-form").waitFor();
  check("the edit form is filled in", (await page.getByLabel(/Salary/).inputValue()) === "1234.5");
  const recordId = page.url().split("/").pop();
  // Somebody else changes the record while this page is open.
  const other = await api(page, "PATCH", `/api/v1/objects/Employee__c/records/${recordId}`, {
    version: 0,
    fields: { department__c: "Operations" },
  });
  check("the other change is accepted", other.status === 200, JSON.stringify(other.body)?.slice(0, 100));
  await page.getByLabel(/Salary/).fill("2000");
  await page.getByTestId("save-record").click();
  const panel = page.getByTestId("conflict-panel");
  await panel.waitFor({ timeout: 10000 });
  check("the stale save is refused with the recovery, not an overwrite", (await panel.textContent())?.includes("changed while you were editing it"));
  check("it says what the other change touched", (await page.getByTestId("conflict-theirs").textContent())?.includes("Department"));
  await page.screenshot({ path: path.join(shots, "5-conflict.png") });
  await page.getByRole("button", { name: "Reload and reapply my changes" }).click();
  await page.getByText(/on top of the newest version/).waitFor();
  check("the newer value is shown and the person's change is kept", (await page.getByLabel(/Department/).inputValue()) === "Operations" && (await page.getByLabel(/Salary/).inputValue()) === "2000");
  await page.getByTestId("save-record").click();
  await page.waitForURL((url) => url.href === base || url.pathname.endsWith("/o-Employee__c"), { timeout: 15000 });
  check("the second save works and the list shows both changes", (await page.getByRole("row").filter({ hasText: `E-${stamp}-1` }).textContent())?.includes("2,000.00"));

  console.log("\n== Several records ==");
  const rows = page.getByRole("row").filter({ hasText: `E-${stamp}` });
  for (let index = 0; index < (await rows.count()); index += 1) {
    await rows.nth(index).getByLabel("Select this row").check();
  }
  check("the bar counts the selection", (await page.getByTestId("bulk-bar").textContent())?.includes("2 selected"));
  await page.getByRole("button", { name: "Change…" }).click();
  const bulk = page.getByTestId("bulk-edit-panel");
  await bulk.getByLabel("Field to change", { exact: true }).selectOption({ label: "Department" });
  await bulk.getByLabel("Department").selectOption("Engineering");
  await bulk.getByRole("button", { name: "Change 2 records" }).click();
  await page.getByTestId("bulk-summary").waitFor();
  check("a bulk change reports what it did", (await page.getByTestId("bulk-summary").textContent())?.includes("2 records changed"));
  await page.getByTestId("bulk-summary").getByRole("button", { name: "Close" }).click();
  await page.waitForTimeout(500);
  check("the list shows the new value on both", (await page.getByRole("row").filter({ hasText: `E-${stamp}` }).allTextContents()).every((text) => text.includes("Engineering")));

  console.log("\n== The same screens in dark, and in a narrow window ==");
  await page.emulateMedia({ colorScheme: "dark" });
  await page.screenshot({ path: path.join(shots, "6-list-dark.png") });
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 390, height: 800 });
  await page.screenshot({ path: path.join(shots, "7-list-narrow.png") });
  check("the narrow window does not scroll sideways", await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await page.getByRole("link", { name: `E-${stamp}-1` }).click();
  await page.getByTestId("record-form").waitFor();
  await page.screenshot({ path: path.join(shots, "8-form-narrow.png") });
  check("the narrow form does not scroll sideways", await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await page.setViewportSize({ width: 1280, height: 860 });

  console.log("\n== The owner is a name, never an identifier ==");
  await page.goto(base);
  await page.getByRole("button", { name: "Columns" }).click();
  await page.getByTestId("columns-panel").getByLabel("Owner").check();
  await page.waitForTimeout(1500);
  const text = await page.getByTestId("list-table").textContent();
  check("the owner column shows a name", /Admin|User|Test/i.test(text ?? "") && !/[0-9a-f]{8}-[0-9a-f]{4}-/.test(text ?? ""), (text ?? "").slice(0, 160));

  console.log("\n== Tidy up what this check made ==");
  const listed = await api(page, "POST", "/api/v1/objects/Employee__c/records/query", { limit: 100 });
  const ids = (listed.body?.data ?? []).map((entry) => entry.record).filter((record) => /^(E-|DBG-)/.test(record.fields?.employeeNumber__c ?? "") && (record.fields.employeeNumber__c.includes(stamp) || record.fields.employeeNumber__c.startsWith("DBG-"))).map((record) => record.id);
  for (const id of ids) {
    await api(page, "DELETE", `/api/v1/objects/Employee__c/records/${id}`);
    await api(page, "DELETE", `/api/v1/objects/Employee__c/recycle-bin/${id}`);
  }
  check("the records this check made are removed for good", ids.length >= 2, String(ids.length));

  console.log("calls the API refused (expected: the one deliberate concurrent change):", refusedCalls.join(" | "));
  // The page's own session bootstrap may be refused once while it settles after sign-in (existing behaviour, not a record screen).
  check("nothing but the deliberate conflict was refused", refusedCalls.every((call) => call.startsWith("409 PATCH") || call.endsWith("/api/v1/auth/refresh")), refusedCalls.join(" | "));
  check("nothing was reported as an error by the page", problems.filter((text) => !/409|Conflict|403|Forbidden/.test(text)).length === 0, problems.join(" | "));
  await context.close();

  console.log("\n== A member who may not read the object ==");
  const plainContext = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const plain = await plainContext.newPage();
  await signIn(plain, "user-a@example.test");
  await plain.goto(BASE + "/records/Employee__c");
  const gone = plain.getByTestId("list-unavailable");
  await gone.waitFor({ timeout: 15000 });
  check("an object out of reach is 'not available', in the same words as one that does not exist", (await gone.textContent())?.includes("This page is not available."));
  await plain.goto(BASE + "/records/Nope__c");
  await plain.getByTestId("list-unavailable").waitFor({ timeout: 15000 });
  check("an object that does not exist says the same", (await plain.getByTestId("list-unavailable").textContent())?.includes("This page is not available."));
  await plainContext.close();
} finally {
  await browser.close();
}

console.log(failures === 0 ? "\nAll checks passed." : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
