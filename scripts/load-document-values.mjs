#!/usr/bin/env node
// Loads the Document values of the template and their mapping to the Levels, in `tenant-a`, through the public API only
// (the same call as "Edit the values" in Setup), so everything it makes can be looked at and changed there.
//
//   usage:  node scripts/load-document-values.mjs --check   (reads only: shows the Levels and Documents there now)
//           node scripts/load-document-values.mjs           (adds the Documents and the mapping)
//
// It can be run again: a Document that is there is left alone, the mapping is made equal to the list below for the Levels
// named in it, and a Level or Document an administrator added by hand is never removed.
import { readFileSync } from "node:fs";
import http from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const checkOnly = process.argv.includes("--check");
const API = { host: "localhost", port: Number(process.env.DEMO_API_PORT ?? 8080) };
const ORG_HOST = process.env.DEMO_ORG_HOST ?? "tenant-a.localhost";

function seedPassword() {
  const text = readFileSync(join(root, "infra", "local", ".env"), "utf8");
  const line = text.split(/\r?\n/).find((l) => l.startsWith("LOCAL_SEED_PASSWORD="));
  if (!line) throw new Error("LOCAL_SEED_PASSWORD is not in infra/local/.env (run infra/local/init-env.ps1)");
  return line.slice("LOCAL_SEED_PASSWORD=".length).trim();
}

// ---- a very small HTTP client with a cookie jar (Host header set by hand: *.localhost is the organization) ----

function raw(host, method, path, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body);
    const request = http.request(
      { ...API, method, path, headers: { Host: host, ...(data ? { "Content-Type": "application/json" } : {}), ...headers } },
      (response) => {
        const chunks = [];
        response.on("data", (c) => chunks.push(c));
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let json;
          try {
            json = text ? JSON.parse(text) : undefined;
          } catch {
            json = undefined;
          }
          resolve({ status: response.statusCode, headers: response.headers, text, json });
        });
      },
    );
    request.on("error", reject);
    if (data) request.write(data);
    request.end();
  });
}

function cookiesOf(response) {
  const jar = {};
  for (const line of response.headers["set-cookie"] ?? []) {
    const [pair] = line.split(";");
    const at = pair.indexOf("=");
    jar[pair.slice(0, at)] = pair.slice(at + 1);
  }
  return jar;
}

function cookieHeader(jar) {
  return Object.entries(jar)
    .map(([k, v]) => `${k}=${v}`)
    .join("; ");
}

function pathOf(response) {
  return String(response.headers.location ?? "").replace(/^https?:\/\/[^/]+/, "");
}

/** The browser's whole sign-in on a host; answers a session that calls the API as that person. */
async function signIn(host, email, password) {
  const jar = {};
  let r = await raw(host, "GET", "/api/v1/auth/csrf");
  Object.assign(jar, cookiesOf(r));
  const xsrf = jar["XSRF-TOKEN"];
  r = await raw(host, "POST", "/api/v1/auth/sign-in", {
    headers: { Cookie: cookieHeader(jar), "X-XSRF-TOKEN": xsrf },
    body: { email, password },
  });
  if (r.status !== 204) throw new Error(`sign-in as ${email} on ${host} answered ${r.status}`);
  Object.assign(jar, cookiesOf(r));
  r = await raw(host, "GET", "/api/v1/auth/start?continue=/", { headers: { Cookie: cookieHeader(jar) } });
  Object.assign(jar, cookiesOf(r));
  for (let hop = 0; hop < 2; hop++) {
    if (r.status !== 302) throw new Error(`sign-in as ${email}: a redirect was expected, got ${r.status}`);
    r = await raw(host, "GET", pathOf(r), { headers: { Cookie: cookieHeader(jar) } });
    Object.assign(jar, cookiesOf(r));
  }
  const token = jar.platform_at;
  if (!token) throw new Error(`sign-in as ${email} on ${host} gave no session`);
  const call = async (method, path, body) => {
    const headers = { Authorization: `Bearer ${token}`, ...(method === "GET" ? {} : { "X-XSRF-TOKEN": xsrf, Cookie: `XSRF-TOKEN=${xsrf}` }) };
    return raw(host, method, path, { headers, body });
  };
  return {
    get: (path) => call("GET", path),
    post: (path, body) => call("POST", path, body ?? {}),
    put: (path, body) => call("PUT", path, body ?? {}),
    patch: (path, body) => call("PATCH", path, body ?? {}),
  };
}


// ---- the data: Level -> its Documents (label; the code is made from it) ----
// Codes are short (letters and digits, at most 10) because the lists are "code lists" (a code can end up in a number).
const DOCUMENTS = {
  1: ["Quality", "EHS Policy & objectives", "IMS Manual"],
  2: ["Operating Procedures (QSP/ESSP)"],
  3: [
    "Engineering documents",
    "Work Instruction",
    "Work Standards",
    "Inspection Standards",
    "Packing Standards",
    "Manufacturing Instructions",
    "Sub-standards",
    "Purchasing Specifications",
    "PF",
    "CP",
    "PFMEA",
    "PPAP",
    "External origin documents",
  ],
  4: ["Formats", "Lists", "Register"],
};

const OBJECT = "InspectionTemplate";
const base = `/api/v1/metadata/objects/${OBJECT}`;

/** A short code from a label: the capital letters and digits of its words, made unique among the codes used. */
function codeOf(label, used) {
  const words = label.replace(/\(.*?\)/g, " ").split(/[^A-Za-z0-9]+/).filter(Boolean);
  let code = words.length === 1 ? words[0].slice(0, 10) : words.map((w) => w[0].toUpperCase()).join("").slice(0, 10);
  if (code.length < 2) code = (words[0] ?? "DOC").slice(0, 4);
  let candidate = code.toUpperCase();
  for (let n = 2; used.has(candidate); n++) candidate = `${code.toUpperCase().slice(0, 8)}${n}`;
  used.add(candidate);
  return candidate;
}

const admin = await signIn(ORG_HOST, "admin-a@example.test", seedPassword());
const read = await admin.get(`${base}`);
if (read.status !== 200) throw new Error(`reading the template object answered ${read.status}`);
const fields = read.json.data.fields;
const level = fields.find((f) => f.apiName === "level");
const document = fields.find((f) => f.apiName === "documentType");
if (!level || !document) throw new Error("the template has no Level or Document field");
const levelValues = level.configuration?.values ?? level.settings?.values ?? [];
const documentValues = document.configuration?.values ?? document.settings?.values ?? [];
console.log("Levels now:   ", levelValues.map((v) => `${v.value}=${v.label}${v.active === false ? " (off)" : ""}`).join(" | "));
console.log("Documents now:", documentValues.map((v) => `${v.value}=${v.label}`).join(" | "));
console.log("Mapping now:  ", JSON.stringify((document.configuration ?? document.settings)?.dependency?.allowed ?? {}));
if (checkOnly) process.exit(0);

// Which Level is "1", "2", "3", "4": by its code (L1 ...) or by the number in its label.
const levelFor = (n) => levelValues.find((v) => new RegExp(`^L0*${n}$`, "i").test(v.value)) ?? levelValues.find((v) => new RegExp(`\b${n}\b`).test(v.label));
const used = new Set(documentValues.map((v) => v.value));
const values = documentValues.map((v) => ({ value: v.value, label: v.label, active: v.active !== false }));
const allowed = { ...((document.configuration ?? document.settings)?.dependency?.allowed ?? {}) };
for (const [n, labels] of Object.entries(DOCUMENTS)) {
  const lv = levelFor(Number(n));
  if (!lv) {
    console.log(`missing  Level ${n} is not in the Level list: create it in Setup first, then run this again`);
    continue;
  }
  const codes = [];
  for (const label of labels) {
    let found = values.find((v) => v.label.toLowerCase() === label.toLowerCase());
    if (!found) {
      found = { value: codeOf(label, used), label, active: true };
      values.push(found);
      console.log(`made     ${found.value} = ${label}`);
    }
    codes.push(found.value);
  }
  allowed[lv.value] = codes;
  console.log(`mapped   ${lv.value} (${lv.label}) -> ${codes.join(", ")}`);
}
const body = {
  label: document.label,
  description: document.description ?? "",
  settings: { values, dependency: { controllingField: "level", allowed } },
  version: document.version,
};
const done = await admin.put(`${base}/fields/documentType`, body);
if (done.status >= 300) {
  throw new Error(`saving the Document values failed (${done.status}): ${done.json?.error?.message ?? done.text.slice(0, 200)} ${JSON.stringify(done.json?.error?.fields ?? "")}`);
}
console.log("done     Document values saved");
