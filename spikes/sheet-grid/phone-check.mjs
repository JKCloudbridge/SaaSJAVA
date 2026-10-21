// The own grid on a phone-sized touch screen (390 x 844): does the page fit, can a cell be selected and edited by touch?
import { chromium } from "playwright-core";
import { startServer } from "./lib/serve.mjs";
const server = await startServer({ csp: "relaxed" });
const browser = await chromium.launch({ executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe", headless: true });
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await page.goto(`${server.origin}/own?rows=20&cols=8&mode=fill`);
  await page.waitForFunction(() => window.__spike?.ready === true);
  const fit = await page.evaluate(() => ({ pageScrollsSideways: document.documentElement.scrollWidth > window.innerWidth, gridWidth: Math.round(document.querySelector(".og-scroll").getBoundingClientRect().width), cellsInDom: window.__spike.cellsInDom() }));
  console.log("fit:", JSON.stringify(fit));
  await page.locator('[role=gridcell][data-r="3"][data-c="1"]').tap();
  const sel = await page.evaluate(() => JSON.stringify(window.__spike.getSel()));
  console.log("tap selects a cell:", sel);
  await page.locator('[role=gridcell][data-r="3"][data-c="1"]').dblclick();
  const editor = await page.evaluate(() => document.activeElement?.tagName);
  console.log("double tap opens an editor:", editor);
  await page.screenshot({ path: "results/phone-own.png" });
} finally { await browser.close(); server.stop(); }
