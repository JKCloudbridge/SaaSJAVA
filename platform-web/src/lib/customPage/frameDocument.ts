import { FRAME_SOURCE, HOST_SOURCE, PROTOCOL_VERSION, THEME_VARIABLE_NAMES, MAX_FRAME_HEIGHT, MAX_NAVIGATE_PATH } from "./protocol.ts";

/**
 * The document a tenant's JavaScript runs in (Sprint 18, ADR-0127), built as one string for the `srcdoc` of a sandboxed
 * frame. Three layers keep that script away from everything but the data interface:
 *
 * <ol>
 *   <li>the frame's `sandbox` attribute ({@link FRAME_SANDBOX}): scripts only, so the document has an opaque origin and
 *       can read nothing of the page around it (no cookies, no storage, no DOM), open no windows and submit no forms;</li>
 *   <li>a content security policy inside the document ({@link contentSecurityPolicy}): the script may talk to this
 *       platform's API and to nothing else, cannot use `eval`, and cannot load scripts, frames, workers or forms;</li>
 *   <li>the access token (ADR-0127), which carries no more than the data interface and ends in minutes.</li>
 * </ol>
 *
 * The document also defines `window.platform`, the small interface a tenant's script is written against; its
 * description for people is `docs/custom-pages.md`. Only erasable TypeScript, no path aliases: the real-browser test
 * loads this file with Node.
 */

/** The `sandbox` attribute of the frame. Never add `allow-same-origin`: it would give the script the page's origin. */
export const FRAME_SANDBOX = "allow-scripts";

/** The largest script, in characters, a frame is built with. The server refuses larger uploads before this. */
export const MAX_CODE_LENGTH = 1_000_000;

/**
 * The policy placed in the document. `unsafe-inline` is needed for the bootstrap and the tenant's script (both are
 * inline); `unsafe-eval` is deliberately absent, so `eval` and `new Function` do not work. `connect-src` names only
 * the platform's origin: a request to any other address is refused by the browser.
 */
export function contentSecurityPolicy(origin: string): string {
  return [
    "default-src 'none'",
    "script-src 'unsafe-inline'",
    "style-src 'unsafe-inline'",
    "img-src data: blob:",
    "font-src data:",
    "media-src data: blob:",
    `connect-src ${origin}`,
    "form-action 'none'",
    "base-uri 'none'",
    "frame-src 'none'",
    "object-src 'none'",
    "worker-src 'none'",
  ].join("; ");
}

/** The origin (scheme, host and port) of an address, or an error when it is not a plain http or https origin. */
export function normalizeOrigin(origin: string): string {
  const url = new URL(origin);
  if ((url.protocol !== "https:" && url.protocol !== "http:") || url.origin !== origin) {
    throw new Error("The frame needs the plain origin of the platform, for example https://tenant-a.example.test");
  }
  return url.origin;
}

/** Makes a value safe to place inside a script element: no `</script`, no `<!--`, no line separators. */
function jsonForScript(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replaceAll(String.fromCharCode(0x2028), "\\u2028")
    .replaceAll(String.fromCharCode(0x2029), "\\u2029");
}

/** What the document is built from. */
export interface FrameDocumentOptions {
  /** The tenant's script. */
  code: string;
  /** The origin of the platform, which the page is served from and the API answers on. */
  origin: string;
  /** Whether the document reports its height to the host as its content changes. */
  autoResize: boolean;
}

/** The whole HTML document for the frame's `srcdoc`. */
export function buildFrameDocument(options: FrameDocumentOptions): string {
  if (options.code.length > MAX_CODE_LENGTH) {
    throw new Error("The custom page script is larger than the platform allows.");
  }
  const origin = normalizeOrigin(options.origin);
  const config = jsonForScript({
    origin,
    autoResize: options.autoResize,
    protocol: PROTOCOL_VERSION,
    frameSource: FRAME_SOURCE,
    hostSource: HOST_SOURCE,
    themeNames: THEME_VARIABLE_NAMES,
    maxHeight: MAX_FRAME_HEIGHT,
    maxPath: MAX_NAVIGATE_PATH,
  });
  // The tenant's script is placed in the document as data and started by the bootstrap, so that nothing in it can
  // close the element that holds it.
  return [
    "<!doctype html>",
    '<html lang="en"><head><meta charset="utf-8">',
    `<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(origin)}">`,
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    `<style>${BASE_STYLE}</style>`,
    "</head><body>",
    `<script id="platform-code" type="application/json">${jsonForScript(options.code)}</script>`,
    `<script>${BOOTSTRAP.replace("__CONFIG__", () => config)}</script>`,
    "</body></html>",
  ].join("");
}

/** Until the host has connected the page stays invisible, so nobody sees it unstyled. Values come from the tokens. */
const BASE_STYLE =
  "html,body{margin:0;padding:0}" +
  "html:not([data-platform-ready]) body{visibility:hidden}" +
  "body{font-family:var(--font-sans,system-ui,sans-serif);font-size:var(--font-size-md);line-height:var(--line-height);" +
  "color:var(--color-text);background:transparent}" +
  "a{color:var(--color-accent)}";

/**
 * The script that runs before the tenant's. Plain JavaScript on purpose: it is text in a string, never compiled. It
 * keeps to what the protocol says, checks everything it receives, and exposes `window.platform`.
 */
const BOOTSTRAP = String.raw`
(function () {
  "use strict";
  var CONFIG = __CONFIG__;
  var V = CONFIG.protocol;
  var port = null;
  var token = null;
  var context = null;
  var themeListeners = [];
  var tokenWaiters = [];
  var onInit;
  var ready = new Promise(function (resolve) { onInit = resolve; });

  function post(message) {
    if (port) {
      message.v = V;
      port.postMessage(message);
    }
  }

  function applyTheme(scheme, theme) {
    var root = document.documentElement;
    if (scheme === "light" || scheme === "dark") {
      root.style.colorScheme = scheme;
      root.setAttribute("data-platform-scheme", scheme);
    }
    if (theme && typeof theme === "object") {
      CONFIG.themeNames.forEach(function (name) {
        var value = theme[name];
        if (typeof value === "string" && value.length > 0 && value.length <= 200 &&
            !/[;{}<>\\]|url\s*\(|@import|expression\s*\(/i.test(value)) {
          root.style.setProperty(name, value);
        }
      });
    }
    themeListeners.forEach(function (listener) {
      try { listener({ scheme: scheme }); } catch (ignored) { /* a listener's failure is its own */ }
    });
  }

  function setToken(value, expiresAt) {
    token = value;
    if (context) { context = Object.freeze(Object.assign({}, context, { expiresAt: expiresAt })); }
    var waiters = tokenWaiters;
    tokenWaiters = [];
    waiters.forEach(function (resolve) { resolve(); });
  }

  function onPortMessage(event) {
    var m = event.data;
    if (!m || typeof m !== "object" || m.v !== V) { return; }
    if (m.type === "init" && !context && m.context && typeof m.context.token === "string") {
      var shown = Object.assign({}, m.context);
      delete shown.token;
      context = Object.freeze(shown);
      token = m.context.token;
      applyTheme(m.context.scheme, m.theme);
      document.documentElement.setAttribute("data-platform-ready", "");
      onInit(context);
    } else if (m.type === "token" && typeof m.token === "string") {
      setToken(m.token, m.expiresAt);
    } else if (m.type === "theme") {
      applyTheme(m.scheme, m.theme);
    }
  }

  window.addEventListener("message", function (event) {
    var d = event.data;
    if (port || event.source !== window.parent || !event.ports || event.ports.length !== 1) { return; }
    if (!d || d.source !== CONFIG.hostSource || d.v !== V || d.type !== "connect") { return; }
    port = event.ports[0];
    port.onmessage = onPortMessage;
  });

  function apiUrl(path) {
    if (typeof path !== "string" || path.charAt(0) !== "/" || path.charAt(1) === "/" || path.indexOf("\\") !== -1) {
      throw new TypeError("platform.api needs a path that starts with one slash, for example /objects/Item__c/records");
    }
    var url = new URL(CONFIG.origin + "/api/v1" + path);
    if (url.origin !== CONFIG.origin || url.pathname.indexOf("/api/v1/") !== 0) {
      throw new TypeError("platform.api only reaches the data interface of the platform");
    }
    return url.toString();
  }

  function refreshToken() {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("The platform did not send a new access token.")); }, 10000);
      tokenWaiters.push(function () { clearTimeout(timer); resolve(); });
      post({ type: "token-request" });
    });
  }

  function attempt(url, init, mayRetry) {
    var options = Object.assign({}, init || {});
    var headers = new Headers(options.headers || {});
    if (options.json !== undefined) {
      options.body = JSON.stringify(options.json);
      headers.set("Content-Type", "application/json");
      delete options.json;
    }
    headers.set("Authorization", "Bearer " + token);
    options.headers = headers;
    options.credentials = "omit";
    options.mode = "cors";
    options.referrerPolicy = "no-referrer";
    return fetch(url, options).then(function (response) {
      if (response.status === 401 && mayRetry) {
        return refreshToken().then(function () { return attempt(url, init, false); });
      }
      return response;
    });
  }

  function api(path, init) {
    var url = apiUrl(path);
    return ready.then(function () { return attempt(url, init, true); });
  }

  function json(path, init) {
    return api(path, init).then(function (response) {
      return response.text().then(function (text) {
        var body = null;
        try { body = text ? JSON.parse(text) : null; } catch (ignored) { body = null; }
        if (!response.ok) {
          var failure = body && body.error ? body.error : {};
          var error = new Error(typeof failure.message === "string" ? failure.message : "The request failed.");
          error.status = response.status;
          error.code = failure.code;
          error.fields = failure.fields;
          throw error;
        }
        return body;
      });
    });
  }

  function resize(height) {
    var value = Math.ceil(Number(height));
    if (isFinite(value) && value >= 0) {
      post({ type: "resize", height: Math.min(value, CONFIG.maxHeight) });
    }
  }

  function navigate(path) {
    if (typeof path === "string" && path.length <= CONFIG.maxPath) {
      post({ type: "navigate", path: path });
    }
  }

  Object.defineProperty(window, "platform", {
    value: Object.freeze({
      version: V,
      ready: ready,
      api: api,
      json: json,
      resize: resize,
      navigate: navigate,
      onThemeChange: function (listener) { if (typeof listener === "function") { themeListeners.push(listener); } }
    }),
    writable: false,
    configurable: false
  });

  window.addEventListener("error", function () { post({ type: "error", kind: "script" }); });
  window.addEventListener("unhandledrejection", function () { post({ type: "error", kind: "promise" }); });

  // The height is measured as the content changes, and sent again once the channel exists (a measurement taken
  // before that has nowhere to go).
  var last = -1;
  function measure() {
    var height = Math.ceil(document.documentElement.getBoundingClientRect().height);
    if (height !== last) { last = height; resize(height); }
  }
  if (CONFIG.autoResize && typeof ResizeObserver === "function") {
    new ResizeObserver(measure).observe(document.documentElement);
    ready.then(function () { last = -1; measure(); });
  }

  window.parent.postMessage({ source: CONFIG.frameSource, v: V, type: "hello" }, CONFIG.origin);

  var holder = document.getElementById("platform-code");
  var script = document.createElement("script");
  script.textContent = JSON.parse(holder.textContent);
  document.body.appendChild(script);
})();
`;
