import { chromium } from "playwright-core";
const BASE = "http://tenant-a.localhost:3000";
function largestGrid() {
  const columns = Array.from({ length: 100 }, (_, i) => ({ id: `c${String(i).padStart(3, "0")}`, label: `Column ${i + 1}`, type: "text" }));
  const rows = Array.from({ length: 500 }, (_, i) => ({ id: `r${String(i).padStart(3, "0")}`, label: `Row ${i + 1}` }));
  const cells = { "h0:c000": { value: "Inspection of installations", style: { bold: true, fontSize: "TITLE" } } };
  for (const row of rows) cells[`${row.id}:c000`] = { value: row.label };
  return { formatVersion: 1, dimension: 2, rowsMode: "FIXED", columns, headerRows: [{ id: "h0" }], rows, cells, merges: [{ from: "h0:c000", to: "h0:c009" }] };
}
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
const created = await api("POST", "/api/v1/objects/InspectionTemplate/records", { fields: { name: `Debug ${Date.now()}` } });
console.log("created", created.status, JSON.stringify(created.body)); const id = created.body.data.id;
const started = await api("POST", `/api/v1/sheets/InspectionTemplate/${id}/versions`, { dimension: 2 });
console.log("started", started.status);
const saved = await api("PUT", `/api/v1/sheets/InspectionTemplate/${id}/versions/1/grid?version=0`, JSON.stringify(largestGrid()));
console.log("saved", saved.status, JSON.stringify(saved.body).slice(0,500));
await browser.close();
