// Starts the production build of the spike app and a small proxy in front of it that sets the content security policy
// of the run. No shell is used, so the process we stop is the server itself; the port is cleared first so a leftover
// server never answers a new run.
//   csp: "none"    no header
//        "relaxed" no eval, inline styles allowed
//        "strict"  no eval, no inline styles (style attributes and style elements are blocked)
import { spawn, execSync } from "node:child_process";
import { createServer, request } from "node:http";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export const POLICIES = {
  none: null,
  relaxed: "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'none'",
  strict: "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'none'",
};

function freePort(port) {
  try {
    execSync(`powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"`, { stdio: "pipe" });
  } catch { /* nothing listening */ }
}

export async function startServer({ port = 3100, csp = "strict" } = {}) {
  const inner = port + 1;
  freePort(port);
  freePort(inner);
  await sleep(500);
  const child = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", String(inner)], { cwd: root, stdio: "ignore", env: process.env });
  const state = { csp };
  const proxy = createServer((req, res) => {
    const up = request({ host: "localhost", port: inner, path: req.url, method: req.method, headers: req.headers }, (r) => {
      const headers = { ...r.headers };
      delete headers["content-security-policy"];
      const policy = POLICIES[state.csp];
      if (policy) headers["content-security-policy"] = policy;
      res.writeHead(r.statusCode, headers);
      r.pipe(res);
    });
    up.on("error", () => { res.writeHead(502); res.end(); });
    req.pipe(up);
  });
  await new Promise((resolve) => proxy.listen(port, resolve));
  const origin = `http://localhost:${port}`;
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(origin); if (r.ok) break; } catch { /* wait */ }
    await sleep(500);
  }
  return {
    origin,
    setPolicy: (name) => { state.csp = name; },
    stop: () => { proxy.close(); child.kill(); freePort(inner); freePort(port); },
  };
}
