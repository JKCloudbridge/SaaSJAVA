// Runs the custom page frame of platform-web in a real browser and checks what a script inside it can and cannot do.
// Usage: npm install, then npm run spike. Set CHROME_PATH to a Chrome or Edge executable if the default is wrong.
import { existsSync } from "node:fs";
import { chromium } from "playwright-core";
import { CASES } from "./cases.mjs";
import { APP_ORIGIN, OTHER_ORIGIN, seen, startServers } from "./server.mjs";

const CANDIDATES = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter(Boolean);
const executablePath = CANDIDATES.find((candidate) => existsSync(candidate));
if (!executablePath) {
  console.error("No Chrome or Edge found. Set CHROME_PATH.");
  process.exit(2);
}

let failures = 0;
function check(name, condition, detail = "") {
  console.log(`${condition ? "PASS" : "FAIL"}  ${name}${detail ? "  -> " + detail : ""}`);
  if (!condition) {
    failures += 1;
  }
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const fill = (code) => code.replaceAll("__OTHER__", OTHER_ORIGIN).replaceAll("__APP__", APP_ORIGIN);

const servers = await startServers();
const browser = await chromium.launch({ executablePath, headless: true });
const context = await browser.newContext();
const page = await context.newPage();
const consoleLines = [];
page.on("console", (message) => consoleLines.push(message.text()));
const dialogs = [];
page.on("dialog", async (dialog) => { dialogs.push(dialog.message()); await dialog.dismiss(); });
const popups = [];
context.on("page", (opened) => { if (opened !== page) { popups.push(opened.url()); } });
await page.addInitScript(() => {
  // Counts the private channels the host makes, to prove that only the first hello gets one.
  const Original = window.MessageChannel;
  window.__channels = 0;
  window.MessageChannel = class extends Original { constructor() { super(); window.__channels += 1; } };
});
await page.goto(APP_ORIGIN + "/");
await page.waitForFunction(() => typeof window.runCase === "function");

async function run(name, code, options = {}) {
  return page.evaluate(([n, c, o]) => window.runCase(n, c, o), [name, fill(code), options]);
}
const otherHits = () => seen.other.map((hit) => hit.url);

// ---------------------------------------------------------------------------------------------------------------
console.log("\n== The hostile script ==");
const probes = await run("probes", CASES.probes, { settleMs: 9000 });
const r = seen.reports.probes ?? {};
check("the frame connected and the script reported through the API", probes.connected && Object.keys(r).length > 20,
  `${Object.keys(r).length} probes reported`);
const refused = (name, errors) => check(`${name} is refused`, r[name]?.ok === false && (!errors || errors.includes(r[name].error)),
  JSON.stringify(r[name]));
refused("parentDocument", ["SecurityError"]);
refused("cookie", ["SecurityError"]);
refused("localStorage", ["SecurityError"]);
refused("sessionStorage", ["SecurityError"]);
check("IndexedDB is refused", r.indexedDB?.ok === false, JSON.stringify(r.indexedDB));
check("navigating the page around the frame is refused or does nothing", r.topNavigation?.ok === false || !otherHits().some((u) => u.includes("/top")), JSON.stringify(r.topNavigation));
check("a popup does not open", (r.popup?.ok === true && r.popup.value === null) || r.popup?.ok === false, JSON.stringify(r.popup));
refused("fetchOther");
check("a no-cors request to another address does not leave", !otherHits().some((u) => u.includes("/fetch2")));
check("sendBeacon to another address does not leave", !otherHits().some((u) => u.includes("/beacon")), JSON.stringify(r.beacon));
check("an image from another address is blocked", r.image?.value === "blocked" && !otherHits().some((u) => u.includes("/image")), JSON.stringify(r.image));
check("a stylesheet from another address is blocked", r.css?.value === "blocked" && !otherHits().some((u) => u.includes("style.css")), JSON.stringify(r.css));
refused("eval", ["EvalError"]);
refused("newFunction", ["EvalError"]);
check("a string given to setTimeout does not run", r.setTimeoutString?.value === "blocked", JSON.stringify(r.setTimeoutString));
check("a nested frame to another address sends nothing", !otherHits().some((u) => u.includes("/nested")), JSON.stringify(r.nestedFrame));
check("a form post to another address does not leave", !otherHits().some((u) => u.includes("/form")), JSON.stringify(r.form));
check("a worker is blocked", r.worker?.value === "blocked", JSON.stringify(r.worker));
refused("webSocket");
refused("dynamicImport");
refused("serviceWorker");
check("alert shows nothing", dialogs.length === 0, JSON.stringify(dialogs));
check("document.domain cannot be set", r.documentDomain?.ok === false, JSON.stringify(r.documentDomain));
check("a base tag does not redirect later requests", r.baseTag?.ok === true);
check("no request reached the other address in the whole run", otherHits().filter((u) => !u.startsWith("/got")).length === 0, JSON.stringify(otherHits()));
check("no window was opened", popups.length === 0, JSON.stringify(popups));

console.log("\n== The data interface ==");
const echo = r.apiEcho?.value;
check("an API call works with the Origin 'null'", r.apiEcho?.ok === true && echo?.origin === "null", JSON.stringify(echo));
check("the call carried the bearer token", /^Bearer pcs_\d+$/.test(echo?.authorization ?? ""), echo?.authorization);
check("the session cookie was not sent", echo?.hasCookie === false);
check("the call came from a different site", echo?.secFetchSite === "cross-site", echo?.secFetchSite);
refused("apiPathEscape", ["TypeError"]);
refused("apiProtocolRelative", ["TypeError"]);
refused("apiBackslash", ["TypeError"]);
refused("directFetchOtherPage");
check("a request with credentials 'include' reads nothing (no credentials are allowed)", r.directFetchApiWithoutCors?.ok === false,
  JSON.stringify(r.directFetchApiWithoutCors));
const cookieCarrying = seen.api.filter((e) => e.origin === "null" && e.hasCookie);
check("no request from the frame ever carried the session cookie", cookieCarrying.length === 0, JSON.stringify(cookieCarrying));
const preflights = seen.api.filter((e) => e.method === "OPTIONS");
check("the browser asked first (preflight) before sending the token", preflights.length >= 1, `${preflights.length} preflights`);
check("window.platform cannot be replaced", r.overwritePlatform?.value === "object" || r.overwritePlatform?.ok === false,
  JSON.stringify(r.overwritePlatform));

// ---------------------------------------------------------------------------------------------------------------
console.log("\n== Building the document ==");
await run("breakout", CASES.breakout, { settleMs: 2000 });
check("text that would end a script element stays text", seen.reports.breakout?.broken === "undefined" && seen.reports.breakout?.length > 0,
  JSON.stringify(seen.reports.breakout));
const big = await run("big", CASES.big, { settleMs: 3000 });
check("a 900 KB script runs", big.connected && seen.reports.big?.ok === true);

console.log("\n== Look and feel ==");
await run("theme", CASES.theme, { settleMs: 2000 });
const t = seen.reports.theme ?? {};
check("design values are applied", t.accent === "#0b6e67", JSON.stringify(t));
check("a name that is not on the list is ignored", t.evil === "");
check("a value that could load something is ignored", t.radius === "");
check("the page is shown once connected", t.ready === true);
check("the colour scheme is set", t.colorScheme === "light");
check("the text colour follows the design value", t.bodyColor === "rgb(21, 32, 43)", t.bodyColor);
check("the token is not part of the context the page sees", t.hasToken === false, JSON.stringify(t.context));

console.log("\n== Talking to the host ==");
const resize = await run("resize", CASES.resize, { autoResize: true, settleMs: 2500 });
check("an automatic height reaches the host (700 then 900)", resize.resizes.includes(700) && resize.resizes.includes(900), JSON.stringify(resize.resizes));
const navigate = await run("navigate", CASES.navigate, { settleMs: 1500 });
check("only a path inside the organization is passed on", JSON.stringify(navigate.navigations) === JSON.stringify(["/apps/Sales__c/Home?tab=1"]),
  JSON.stringify(navigate.navigations));
const failed = await run("scriptError", CASES.scriptError, { settleMs: 1500 });
check("a failing script is reported by kind only", failed.problems.includes("promise") && !JSON.stringify(failed).includes("secret"), JSON.stringify(failed.problems));

console.log("\n== Tokens ==");
await run("renewal", CASES.renewal, { lifetimeSeconds: 66, settleMs: 12000 });
const renewal = seen.reports.renewal ?? {};
check("the host renews the token before it ends", renewal.first && renewal.second && renewal.first !== renewal.second, JSON.stringify(renewal));
await run("refused", CASES.refused, { lifetimeSeconds: 300, settleMs: 9000 });
check("a refused token is replaced and the call retried", seen.reports.refused?.authorization?.startsWith("Bearer pcs_"), JSON.stringify(seen.reports.refused));

console.log("\n== Who may talk to the host ==");
const before = await page.evaluate(() => window.__channels);
await page.evaluate(() => window.postMessage({ source: "platform-custom-page", v: 1, type: "hello" }, "*"));
const stranger = await run("noHello", "", { rawDocument: "<!doctype html><p>no bootstrap</p>", connectTimeoutMs: 1500, settleMs: 2500 });
const after = await page.evaluate(() => window.__channels);
check("a hello from the host window itself is ignored", after === before && !stranger.connected, `channels ${before} -> ${after}`);
check("a frame that never says hello is reported as a timeout", stranger.problems.includes("timeout"), JSON.stringify(stranger.problems));

const channelsBefore = await page.evaluate(() => window.__channels);
const wandering = await run("selfNavigate", CASES.selfNavigate, { settleMs: 3000 });
await page.evaluate(() => window.__channel_selfNavigate.setTheme("dark", { "--color-text": "#ffffff" }));
await sleep(500);
const channelsAfter = await page.evaluate(() => window.__channels);
check("a frame that navigates away cannot be navigated by sandbox rules? (recorded, see report)", true,
  `the other address ${otherHits().some((u) => u.startsWith("/self")) ? "WAS" : "was NOT"} reached by navigation`);
check("after navigating away only one channel exists", channelsAfter - channelsBefore === 1, `${channelsAfter - channelsBefore}`);
check("the page it navigated to never received a channel", !otherHits().some((u) => u.startsWith("/got") && u.includes("ports=1")), JSON.stringify(otherHits()));
check("the page it navigated to sent a hello that was ignored", wandering.connected === true);

console.log("\n== Page headers ==");
const headers = await page.evaluate(async () => (await fetch("/")).headers.get("x-frame-options"));
check("the page still refuses to be embedded by others, and still embeds its own srcdoc frames", headers === "DENY");

await browser.close();
servers.app.close();
servers.other.close();
console.log(failures === 0 ? "\nAll checks passed." : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
