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
  const r = await fetch(path, { method, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(csrf) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}, [method, path, body]);

const created = await api("POST", "/api/v1/objects/InspectionTemplate/records", { fields: { name: `Debug ${Date.now()}` } });
console.log("created", created.status, JSON.stringify(created.body));
const id = created.body.data.id;
const got = await api("GET", `/api/v1/objects/InspectionTemplate/records/${id}`);
console.log("got", got.status, JSON.stringify(got.body));
const started = await api("POST", `/api/v1/sheets/InspectionTemplate/${id}/versions`, { dimension: 2 });
console.log("started", started.status, JSON.stringify(started.body));
const small = { columns: [{id:"c1",label:"C1",type:"text"}], rows: [{id:"r1",cells:{c1:{value:"x"}}}] };
const saved = await api("PUT", `/api/v1/sheets/InspectionTemplate/${id}/versions/1/grid?version=0`, small);
console.log("saved small", saved.status, JSON.stringify(saved.body));
await browser.close();
