// The kept browser script of the application grid (Functional track FA9, ADR-0203). It drives the PRODUCTION BUILD of the
// web application in a real Chromium-based browser against a RUNNING backend, the way a person would, and checks what a
// jsdom test cannot: that the grid is drawn at the start address, that a placeholder tab says it is being prepared, that
// the dashboard draws its tiles, and that the strip with its eight tabs does not push the page sideways at 1280 pixels.
// It is not part of CI (it needs the services, the backend, a build and a browser). See README.md.
//
// It prints one line per check ("ok" or "FAIL"), writes screenshots to ./results, and exits with 1 if any check failed.
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
const shot = (page, name) =>
  page.screenshot({ path: fileURLToPath(new URL(`./results/${name}.png`, import.meta.url)), fullPage: false });

let failed = 0;
const check = (name, ok, detail = "") => {
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
};

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: "light" });
  const page = await context.newPage();

  // ---- sign in as a person does ----
  await page.goto(`${BASE}/sign-in`);
  await page.getByLabel("Email address").fill(EMAIL);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"), { timeout: 20000 });

  // ---- the grid is the start page ----
  await page.goto(`${BASE}/`);
  await page.getByTestId("application-grid").waitFor({ timeout: 20000 });
  const tiles = await page.locator("[data-testid='application-grid'] a[href^='/apps/']").count();
  check("the start address draws the grid with the applications", tiles >= 16, `${tiles} tiles`);
  check("the address stays /", new URL(page.url()).pathname === "/", page.url());
  await shot(page, "application-grid");

  // search with the slash key
  await page.keyboard.press("/");
  const focused = await page.evaluate(() => document.activeElement?.getAttribute("aria-label"));
  check("the slash key focuses the search", focused === "Search applications", String(focused));
  await page.getByLabel("Search applications").fill("zzzz-nothing");
  check("a search without a match says so", await page.getByTestId("grid-no-match").isVisible());
  await page.getByLabel("Search applications").fill("");

  // a placeholder application says it is coming soon
  const soon = await page.getByText("Coming soon").count();
  check("placeholder applications carry the tag", soon >= 1, `${soon} tags`);

  // ---- the Quality Management System opens at Home, the dashboard ----
  await page.getByRole("link", { name: /Quality Management System/ }).first().click();
  await page.getByTestId("quality-home").waitFor({ timeout: 20000 });
  await page.getByTestId("home-tiles").waitFor({ timeout: 20000 });
  const tileCount = await page.locator("[data-testid='home-tiles'] > li").count();
  check("the dashboard draws its nine tiles", tileCount === 9, `${tileCount} tiles`);
  check("the dashboard did not fail", (await page.getByTestId("home-failed").count()) === 0);
  await shot(page, "quality-home");

  // the strip with its tabs fits at 1280 pixels (it may scroll inside itself, never the page)
  const sideways = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  check("the page does not scroll sideways at 1280 pixels", !sideways);
  const stripTabs = await page.locator(".control-tabs a").count();
  check("the strip lists Home and the tabs", stripTabs >= 6, `${stripTabs} tabs`);

  // a placeholder tab says it is being prepared
  await page.locator(".control-tabs a", { hasText: "Management Review" }).first().click();
  await page.getByTestId("placeholder-page").waitFor({ timeout: 20000 });
  check("a placeholder tab says it is being prepared", (await page.getByText(/being prepared/i).count()) >= 1);
  await shot(page, "placeholder-tab");

  // the three lines lead back to the grid
  await page.getByRole("link", { name: /applications/i }).first().click();
  await page.getByTestId("application-grid").waitFor({ timeout: 20000 });
  check("the three lines lead back to the grid", new URL(page.url()).pathname === "/");

  // a phone
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto(`${BASE}/`);
  await page.getByTestId("application-grid").waitFor({ timeout: 20000 });
  const phoneSideways = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  check("the grid fits a phone (390 pixels)", !phoneSideways);
  await shot(page, "application-grid-phone");
} finally {
  await browser.close();
}

console.log(failed === 0 ? "all checks passed" : `${failed} check(s) failed`);
process.exit(failed === 0 ? 0 : 1);
