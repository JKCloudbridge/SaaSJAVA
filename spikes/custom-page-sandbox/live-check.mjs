// A look at the real application: sign in on the local organization, open the launcher, open the sample custom page and
// check the page, the frame and the shell around it in light, dark and a narrow window. Not part of any build.
//
// Needs the local services, the application (`--spring.profiles.active=local`) and `npm run dev` of platform-web.
// Usage: node live-check.mjs [folder for the screenshots]
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

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

const browser = await chromium.launch({ executablePath, headless: true });

// ----------------------------------------------------------------------------------------------- a plain member
{
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const problems = [];
  const refused = [];
  page.on("pageerror", (error) => problems.push("pageerror: " + error.message));
  // The sign-in flow itself probes /auth/me before there is a session and expects 401 back (fetchSession,
  // src/lib/session/SessionProvider.tsx): that 401 is not an application error, so it is not counted as one.
  // Lines from this script's own deliberate probes (a refused call, a blocked cross-origin fetch) are not application
  // errors either: the frame section below causes them on purpose to prove the sandbox refuses them.
  const expected = [/401/, /status of 403/, /127\.0\.0\.1:9/, /Content Security Policy/];
  page.on("console", (message) => {
    if (message.type() === "error" && !expected.some((pattern) => pattern.test(message.text()))) {
      problems.push("console: " + message.text());
    }
  });
  page.on("response", (response) => {
    if (response.status() === 401) {
      refused.push(response.request().method() + " " + new URL(response.url()).pathname);
    }
  });
  await signIn(page, "user-a@example.test");

  console.log("\n== The shell of a signed-in member ==");
  await page.getByTestId("control-bar").waitFor();
  check("the control bar is there", await page.getByTestId("control-bar").isVisible());
  check("it says that no application is open", (await page.getByTestId("control-app").textContent())?.includes("No application open"));
  await page.screenshot({ path: path.join(shots, "1-home.png") });

  console.log("\n== The launcher ==");
  await page.getByRole("button", { name: "Applications" }).click();
  const tile = page.getByRole("link", { name: /Field Service/ });
  await tile.waitFor();
  check("the launcher lists the application the API gives", await tile.isVisible());
  check("and takes the focus", await tile.evaluate((element) => element === document.activeElement));
  await page.screenshot({ path: path.join(shots, "2-launcher.png") });
  await page.keyboard.press("Escape");
  check("Escape closes it", (await page.getByRole("dialog").count()) === 0);

  console.log("\n== Opening the application ==");
  await page.getByRole("button", { name: "Applications" }).click();
  await page.getByRole("link", { name: /Field Service/ }).click();
  await page.waitForURL("**/apps/FieldService__c/c-ObjectsOverviewTab__c", { timeout: 20000 });
  check("it opens on its first page", true, page.url());
  const frame = page.frameLocator('iframe[title="Objects overview"]');
  await frame.getByRole("heading", { name: /Hello, / }).waitFor({ timeout: 20000 });
  check("the custom page drew itself with the member's own data", true, await frame.getByRole("heading").first().textContent());
  const nav = page.getByRole("navigation", { name: "Field Service pages" });
  check("the control bar shows the pages of the application", (await nav.textContent())?.includes("Objects overview"));
  check("and marks the open one", (await nav.getByRole("link", { name: "Objects overview" }).getAttribute("aria-current")) === "page");
  check("a heading that leads nowhere for this member is left out", !(await nav.textContent())?.includes("Records"));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(shots, "3-custom-page.png") });

  console.log("\n== What the page can and cannot do in the real browser ==");
  const frameHandle = await page.locator('iframe[title="Objects overview"]').elementHandle();
  const inside = await (await frameHandle.contentFrame()).evaluate(async () => {
    const out = {};
    try { out.parentDocument = window.parent.document.title; } catch (error) { out.parentDocument = error.name; }
    try { out.cookie = document.cookie; } catch (error) { out.cookie = error.name; }
    try { out.members = (await platform.api("/members")).status; } catch (error) { out.members = error.name; }
    try { out.me = (await platform.api("/auth/me")).status; } catch (error) { out.me = error.name; }
    try { out.eval = eval("1"); } catch (error) { out.eval = error.name; }
    try { out.other = (await fetch("http://127.0.0.1:9/x")).status; } catch (error) { out.other = error.name; }
    return out;
  });
  check("it cannot read the page around it", inside.parentDocument === "SecurityError", inside.parentDocument);
  check("it cannot read the session cookie", inside.cookie === "SecurityError", inside.cookie);
  check("it reaches the data interface", inside.me === 200, String(inside.me));
  check("it is refused where the setup of the organization is", inside.members === 403, String(inside.members));
  check("it cannot use eval", inside.eval === "EvalError", String(inside.eval));
  check("it cannot reach another address", inside.other === "TypeError", String(inside.other));

  console.log("\n== The colour scheme follows the person ==");
  await page.emulateMedia({ colorScheme: "dark" });
  await page.waitForTimeout(800);
  const dark = await (await frameHandle.contentFrame()).evaluate(() => ({
    scheme: document.documentElement.getAttribute("data-platform-scheme"),
    text: getComputedStyle(document.body).color,
  }));
  check("the page is told", dark.scheme === "dark", JSON.stringify(dark));
  await page.screenshot({ path: path.join(shots, "4-custom-page-dark.png") });
  await page.emulateMedia({ colorScheme: "light" });

  console.log("\n== The page asks the platform to open a place ==");
  await frame.getByRole("button", { name: "Back to the start" }).click();
  await page.waitForURL(BASE + "/", { timeout: 10000 });
  check("a place inside the organization is opened", true, page.url());

  console.log("\n== An address that is not there ==");
  await page.goto(BASE + "/apps/FieldService__c/o-Nothing__c");
  await page.getByTestId("entry-unavailable").waitFor({ timeout: 15000 });
  check("it says so, in the same words as for what is out of reach", true);
  await page.goto(BASE + "/apps/Gone__c");
  await page.getByText("This application is not available.").waitFor({ timeout: 15000 });
  check("an application the member does not have is not available", true);

  console.log("\n== A narrow window ==");
  await page.setViewportSize({ width: 390, height: 780 });
  await page.goto(BASE + "/apps/FieldService__c/c-ObjectsOverviewTab__c");
  await page.frameLocator('iframe[title="Objects overview"]').getByRole("heading", { name: /Hello, / }).waitFor({ timeout: 20000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check("the page does not scroll sideways", overflow <= 0, `overflow ${overflow}px`);
  await page.screenshot({ path: path.join(shots, "5-narrow.png") });

  console.log("   401 answers seen:", JSON.stringify(refused));
  check("no error reached the console", problems.length === 0, problems.slice(0, 3).join(" | "));
  await context.close();
}

// ----------------------------------------------------------------------------------------------- an administrator
{
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  await signIn(page, "admin-a@example.test");

  console.log("\n== An administrator ==");
  await page.getByTestId("control-bar").waitFor();
  check("the way to Setup is in the control bar", await page.getByRole("link", { name: "Setup", exact: true }).first().isVisible());
  await page.goto(BASE + "/apps/FieldService__c");
  await page.waitForURL("**/c-ObjectsOverviewTab__c", { timeout: 20000 });
  const nav = page.getByRole("navigation", { name: "Field Service pages" });
  await nav.waitFor();
  const text = await nav.textContent();
  check("sees the object entry the permission allows", text?.includes("Employees"), text);
  await page.screenshot({ path: path.join(shots, "6-admin.png") });
  await page.getByRole("button", { name: "Applications" }).click();
  await page.screenshot({ path: path.join(shots, "7-admin-launcher.png") });
  await page.keyboard.press("Escape");
  await page.getByRole("link", { name: "Employees" }).click();
  await page.getByTestId("entry-not-built").waitFor({ timeout: 15000 });
  check("an object page says it is being prepared", true);
  await context.close();
}

await browser.close();
console.log(failures === 0 ? "\nAll checks passed." : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
