import { chromium } from "playwright-core";
const BASE = "http://tenant-a.localhost:3000";
const browser = await chromium.launch({ executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe" });
const page = await browser.newPage();
await page.goto(`${BASE}/sign-in`);
await page.getByLabel("Email address").fill("admin-a@example.test");
await page.getByLabel("Password").fill(process.env.LOCAL_SEED_PASSWORD);
await page.getByRole("button", { name: /sign in/i }).click();
await page.waitForURL((u) => !u.pathname.startsWith("/sign-in"), { timeout: 20000 });
const api = (method, path, body) => page.evaluate(async ([method, path, body]) => {
  const csrf = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length) ?? "";
  const r = await fetch(path, { method, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(csrf) }, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}, [method, path, body]);
const id = "01a16416-9647-733c-89ea-1e6bfe2e1acd";
for (let i = 0; i < 3; i++) {
  const got = await api("GET", `/api/v1/objects/Account/records/${id}`);
  const patched = await api("PATCH", `/api/v1/objects/Account/records/${id}?version=${got.body.data.version}`, { fields: { name: "patched " + i } });
  console.log(i, "patch", patched.status);
}
await browser.close();
