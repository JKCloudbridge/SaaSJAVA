// A stand-in for the platform: one origin that serves a host page and a few API routes, plus a second "other" origin
// that records every request it gets (so a test can prove that nothing reached it). The CORS rules here are the rules
// the real API gets in Sprint 18 (ADR-0127): only the origin "null" of a sandboxed frame, no credentials, and only the
// headers a data call needs. The host-side code under test is the real code of platform-web, served as JavaScript.
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const libDirectory = path.resolve(here, "../../platform-web/src/lib/customPage");

export const APP_PORT = 3100;
export const OTHER_PORT = 3101;
export const APP_HOST = "tenant-a.localhost";
export const APP_ORIGIN = `http://${APP_HOST}:${APP_PORT}`;
export const OTHER_ORIGIN = `http://127.0.0.1:${OTHER_PORT}`;
export const SESSION_COOKIE = "access=SESSION-SECRET";

/** What the server saw, for the test to read. */
export const seen = { api: [], other: [], reports: {}, tokens: 0, staleBefore: 0 };

function json(response, status, body, headers = {}) {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers });
  response.end(JSON.stringify(body));
}

/** The CORS rule of the real API: the origin "null" only, no credentials, a few headers. */
function corsHeaders(request) {
  if (request.headers.origin !== "null") {
    return {};
  }
  return { "Access-Control-Allow-Origin": "null", Vary: "Origin" };
}

function readBody(request) {
  return new Promise((resolve) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

/** TypeScript module of platform-web, as JavaScript, with the `.ts` import paths turned into `.js`. */
function moduleSource(name) {
  const source = readFileSync(path.join(libDirectory, name + ".ts"), "utf8");
  return stripTypeScriptTypes(source).replace(/from "\.\/([A-Za-z]+)\.ts"/g, 'from "/lib/$1.js"');
}

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>host</title></head><body>
<div id="frames"></div>
<script type="module">
  import { buildFrameDocument, FRAME_SANDBOX } from "/lib/frameDocument.js";
  import { openHostChannel } from "/lib/hostChannel.js";
  const events = [];
  window.__events = events;
  let tokenCounter = 0;

  async function requestToken(lifetimeSeconds) {
    const response = await fetch("/api/v1/embedded-tokens", { method: "POST", body: JSON.stringify({ lifetimeSeconds }) });
    if (!response.ok) { throw new Error("no token"); }
    return response.json();
  }

  /** Runs a custom page and settles after the given time; returns what the host saw. */
  window.runCase = (name, code, options = {}) => new Promise((resolve) => {
    const frame = document.createElement("iframe");
    frame.setAttribute("sandbox", FRAME_SANDBOX);
    frame.dataset.case = name;
    frame.style.width = "600px";
    frame.style.height = "300px";
    const seenByHost = { name, connected: false, problems: [], resizes: [], navigations: [], themeSent: false };
    const channel = openHostChannel({
      frame,
      hostWindow: window,
      context: { page: { apiName: "Demo__c", label: "Demo" }, user: { id: "11111111-1111-1111-1111-111111111111" },
        locale: "en", scheme: "light" },
      theme: { "--color-accent": "#0b6e67", "--color-text": "#15202b", "--evil": "red", "--radius-sm": "url(http://x/y)" },
      requestToken: () => requestToken(options.lifetimeSeconds ?? 300),
      onConnected: () => { seenByHost.connected = true; },
      onProblem: (problem) => seenByHost.problems.push(problem),
      onResize: (height) => seenByHost.resizes.push(height),
      onNavigate: (path) => seenByHost.navigations.push(path),
      connectTimeoutMs: options.connectTimeoutMs ?? 4000,
    });
    window["__channel_" + name] = channel;
    window["__seen_" + name] = seenByHost;
    document.getElementById("frames").appendChild(frame);
    frame.srcdoc = options.rawDocument ?? buildFrameDocument({ code, origin: location.origin, autoResize: options.autoResize ?? false });
    setTimeout(() => resolve(seenByHost), options.settleMs ?? 2500);
  });
</script></body></html>`;

export function startServers() {
  const app = createServer(async (request, response) => {
    const url = new URL(request.url, APP_ORIGIN);
    const cookie = request.headers.cookie ?? "";
    if (url.pathname === "/") {
      response.writeHead(200, {
        "Content-Type": "text/html; charset=utf-8",
        "X-Frame-Options": "DENY",
        "X-Content-Type-Options": "nosniff",
        "Set-Cookie": `${SESSION_COOKIE}; Path=/; HttpOnly; SameSite=Strict`,
      });
      response.end(PAGE);
      return;
    }
    if (url.pathname.startsWith("/lib/") && url.pathname.endsWith(".js")) {
      const name = url.pathname.slice(5, -3);
      response.writeHead(200, { "Content-Type": "text/javascript; charset=utf-8" });
      response.end(moduleSource(name));
      return;
    }
    if (url.pathname === "/private") {
      // A page of the platform that is not part of the data interface: no CORS headers, as in the real application.
      response.writeHead(200, { "Content-Type": "text/html" });
      response.end("<h1>private page</h1>");
      return;
    }
    if (!url.pathname.startsWith("/api/v1/")) {
      response.writeHead(404);
      response.end();
      return;
    }

    const entry = {
      method: request.method,
      path: url.pathname,
      origin: request.headers.origin ?? null,
      hasCookie: cookie.includes("SESSION-SECRET"),
      authorization: request.headers.authorization ?? null,
      secFetchSite: request.headers["sec-fetch-site"] ?? null,
    };
    seen.api.push(entry);

    if (request.method === "OPTIONS") {
      if (request.headers.origin !== "null") {
        response.writeHead(403);
        response.end();
        return;
      }
      response.writeHead(204, {
        ...corsHeaders(request),
        "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE",
        "Access-Control-Allow-Headers": "authorization, content-type",
        "Access-Control-Max-Age": "600",
      });
      response.end();
      return;
    }

    if (url.pathname === "/api/v1/embedded-tokens" && request.method === "POST") {
      // Minting is done by the host page with the session cookie, never by the frame.
      if (!cookie.includes("SESSION-SECRET")) {
        json(response, 401, { error: { code: "UNAUTHENTICATED", message: "Sign in." } });
        return;
      }
      const body = JSON.parse((await readBody(request)) || "{}");
      const lifetime = Number(body.lifetimeSeconds ?? 300);
      seen.tokens += 1;
      json(response, 200, {
        data: undefined,
        token: `pcs_${seen.tokens}`,
        expiresAt: new Date(Date.now() + lifetime * 1000).toISOString(),
      });
      return;
    }

    if (url.pathname === "/api/v1/echo") {
      json(response, 200, { data: entry }, corsHeaders(request));
      return;
    }
    if (url.pathname === "/api/v1/expire-all") {
      // Every token issued so far stops working, as when a grant is revoked.
      seen.staleBefore = seen.tokens;
      json(response, 200, { data: "ok" }, corsHeaders(request));
      return;
    }
    if (url.pathname === "/api/v1/expire-once") {
      const number = Number((request.headers.authorization ?? "").replace(/\D/g, ""));
      if (number <= (seen.staleBefore ?? 0)) {
        json(response, 401, { error: { code: "UNAUTHENTICATED", message: "Expired." } }, corsHeaders(request));
        return;
      }
      json(response, 200, { data: entry }, corsHeaders(request));
      return;
    }
    if (url.pathname === "/api/v1/report" && request.method === "POST") {
      const body = JSON.parse((await readBody(request)) || "{}");
      seen.reports[body.case] = body.results;
      json(response, 200, { data: "ok" }, corsHeaders(request));
      return;
    }
    json(response, 404, { error: { code: "NOT_FOUND", message: "No." } }, corsHeaders(request));
  });

  const other = createServer((request, response) => {
    seen.other.push({ method: request.method, url: request.url });
    response.writeHead(200, { "Content-Type": "text/html", "Access-Control-Allow-Origin": "*" });
    // A page that, if a frame ever navigates here, tries to get a channel from the host.
    // It also reports every message it receives, and whether the message carried a channel.
    response.end(`<!doctype html><script>
      addEventListener("message", (e) => fetch("/got?type=" + (e.data && e.data.type) + "&ports=" + (e.ports ? e.ports.length : 0)));
      parent.postMessage({source:"platform-custom-page",v:1,type:"hello"},"*");
    </script>other`);
  });

  return new Promise((resolve) => {
    app.listen(APP_PORT, "127.0.0.1", () => other.listen(OTHER_PORT, "127.0.0.1", () => resolve({ app, other })));
  });
}
