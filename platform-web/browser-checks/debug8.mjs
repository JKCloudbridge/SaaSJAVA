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
const share = await api("PUT", "/api/v1/sharing/objects/Account", {"defaultAccess":"PRIVATE","grantViaHierarchy":false});
console.log("set private", share.status, JSON.stringify(share.body).slice(0,200));
const created = await api("POST", "/api/v1/objects/Account/records", { fields: { name: "Debug account" } });
console.log("created", created.status, JSON.stringify(created.body));
const id = created.body?.data?.id;
if (id) {
  const patched = await api("PATCH", `/api/v1/objects/Account/records/${id}?version=0`, { fields: { name: "patched" } });
  console.log("patch", patched.status, JSON.stringify(patched.body));
}
const revert = await api("PUT", "/api/v1/sharing/objects/Account", {"defaultAccess":"PUBLIC_READ_WRITE","grantViaHierarchy":false});
console.log("revert", revert.status);
await browser.close();
