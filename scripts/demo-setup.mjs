#!/usr/bin/env node
// Demo setup for a developer machine (the `local` profile): makes the people, groups, access policies, department
// groups and the approval process that the Audit maintenance demo needs, in `tenant-a`, through the public API only,
// so everything it makes is ordinary data that can be looked at, changed and removed in Setup.
//
//   usage:  node scripts/demo-setup.mjs            (from the repository root; the local services and the app must be up)
//           node scripts/demo-setup.mjs --check    (reads only: says what is there and what is missing)
//
// It can be run again: every step looks for what it would make and leaves it alone when it is there. It never deletes
// anything. The password of the demo people is LOCAL_SEED_PASSWORD from infra/local/.env (never printed). The steps and
// the reasons are written in plain words in the demo guide (docs/solutions/inspection.md, "Setting up a demo").
import { readFileSync } from "node:fs";
import http from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const checkOnly = process.argv.includes("--check");
const API = { host: "localhost", port: Number(process.env.DEMO_API_PORT ?? 8080) };
const MAIL = process.env.DEMO_MAIL_URL ?? "http://localhost:8025";
const ORG_HOST = process.env.DEMO_ORG_HOST ?? "tenant-a.localhost";
const PLATFORM_HOST = process.env.DEMO_PLATFORM_HOST ?? "localhost";

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

// ---- reporting ----

const log = [];
function say(kind, text) {
  log.push(kind);
  console.log(`${kind.padEnd(8)} ${text}`);
}
function must(response, what) {
  if (response.status >= 300) {
    const words = response.json?.error?.message ?? response.text.slice(0, 200);
    const fields = response.json?.error?.fields ? ` ${JSON.stringify(response.json.error.fields)}` : "";
    throw new Error(`${what} failed (${response.status}): ${words}${fields}`);
  }
  return response.json?.data;
}

/** A call nobody is signed in for (accepting an invitation): it only needs the forgery cookie. */
async function anonymousPost(host, path, body) {
  const first = await raw(host, "GET", "/api/v1/auth/csrf");
  const xsrf = cookiesOf(first)["XSRF-TOKEN"];
  return raw(host, "POST", path, { headers: { Cookie: `XSRF-TOKEN=${xsrf}`, "X-XSRF-TOKEN": xsrf }, body });
}

// ---- the demo: who, which group, which policy ----

const REASON = "Demo setup on a developer machine";
const PEOPLE = [
  { email: "corp-qms-1@example.test", name: "Corporate QMS 1", groups: ["Corporate QMS"] },
  { email: "corp-qms-2@example.test", name: "Corporate QMS 2", groups: ["Corporate QMS"] },
  { email: "plant-qms-1@example.test", name: "Plant QMS 1", groups: ["Plant QMS"] },
  { email: "plant-qms-2@example.test", name: "Plant QMS 2", groups: ["Plant QMS"] },
  { email: "plant1-hr@example.test", name: "Plant 1 HR User", groups: ["Plant 1 HR"] },
  { email: "plant1-accounts@example.test", name: "Plant 1 Accounts User", groups: ["Plant 1 Accounts"] },
  { email: "plant2-hr@example.test", name: "Plant 2 HR User", groups: ["Plant 2 HR"] },
  { email: "plant2-accounts@example.test", name: "Plant 2 Accounts User", groups: ["Plant 2 Accounts"] },
];
// group -> the policies it holds ("List editor" is made here: it holds one ability and no right on data)
const GROUP_POLICIES = {
  "Corporate QMS": ["Approver"],
  "Plant QMS": ["Approver", "List editor"],
  "Plant 1 HR": ["Author", "Reader", "Filler"],
  "Plant 1 Accounts": ["Author", "Reader", "Filler"],
  "Plant 2 HR": ["Author", "Reader", "Filler"],
  "Plant 2 Accounts": ["Author", "Reader", "Filler"],
};
// the Department group records: which group a location and a department mean
const DEPARTMENT_GROUPS = [
  { name: "Plant 1 HR", location: "PLANT1", department: "HR" },
  { name: "Plant 1 Accounts", location: "PLANT1", department: "ACC" },
  { name: "Plant 2 HR", location: "PLANT2", department: "HR" },
  { name: "Plant 2 Accounts", location: "PLANT2", department: "ACC" },
];
const LICENCES_WANTED = 30;

async function ensureLicences(platform, orgId) {
  const org = must(await platform.get(`/api/v1/platform/organizations/${orgId}`), "reading the organization");
  const pool = org.pools.find((p) => p.licenceType === "user");
  if (pool && pool.quantity >= LICENCES_WANTED) return say("ok", `the pool of user licences is ${pool.quantity}`);
  if (checkOnly) return say("missing", `the pool of user licences is ${pool?.quantity ?? 0}, ${LICENCES_WANTED} wanted`);
  must(await platform.put(`/api/v1/platform/organizations/${orgId}/pools/user`, { quantity: LICENCES_WANTED, reason: REASON }), "raising the pool");
  say("made", `the pool of user licences is now ${LICENCES_WANTED}`);
}

async function mailToken(address) {
  for (let attempt = 0; attempt < 45; attempt++) {
    const found = await (await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent(`to:${address}`)}`)).json();
    const id = found.messages?.[0]?.ID;
    if (id) {
      const message = await (await fetch(`${MAIL}/api/v1/message/${id}`)).json();
      const link = `${message.Text ?? ""} ${message.HTML ?? ""}`.match(/https?:\/\/[A-Za-z0-9./:_-]*#token=([A-Za-z0-9_-]*)/);
      if (link) return link[1];
    }
    await new Promise((done) => setTimeout(done, 2000));
  }
  throw new Error(`no invitation mail reached ${address} (is the mail relay on and the catcher up?)`);
}

async function ensurePeople(admin, password) {
  const profiles = must(await admin.get("/api/v1/profiles"), "reading profiles");
  const memberProfile = profiles.find((p) => p.defaultProfile);
  let members = must(await admin.get("/api/v1/members"), "reading members");
  for (const person of PEOPLE) {
    let found = members.find((m) => m.email === person.email);
    if (found) {
      say("ok", `person ${person.email}`);
    } else if (checkOnly) {
      say("missing", `person ${person.email}`);
      continue;
    } else {
      const invited = await admin.post("/api/v1/invitations", { email: person.email, displayName: person.name, profileId: memberProfile.id, active: true });
      // a run that stopped half way left an invitation and its mail: the newest mail is used
      if (invited.status >= 300 && invited.status !== 409) must(invited, `inviting ${person.email}`);
      const token = await mailToken(person.email);
      must(await anonymousPost(PLATFORM_HOST, "/api/v1/auth/invitations/accept-new", { token, displayName: person.name, password }), `accepting the invitation of ${person.email}`);
      members = must(await admin.get("/api/v1/members"), "reading members");
      found = members.find((m) => m.email === person.email);
      say("made", `person ${person.email} (${person.name})`);
    }
    if (found && !found.licensed) {
      if (checkOnly) {
        say("missing", `licence for ${person.email}`);
      } else {
        must(await admin.put(`/api/v1/members/${found.id}/licence`), `licence for ${person.email}`);
        say("made", `licence for ${person.email}`);
      }
    }
  }
  return members;
}

async function ensureGroups(admin, members) {
  let groups = must(await admin.get("/api/v1/groups"), "reading groups");
  const ids = {};
  for (const name of Object.keys(GROUP_POLICIES)) {
    let group = groups.find((g) => g.name === name);
    if (group) {
      say("ok", `group ${name}`);
    } else if (checkOnly) {
      say("missing", `group ${name}`);
      continue;
    } else {
      group = must(await admin.post("/api/v1/groups", { name, description: "Demo group" }), `making group ${name}`);
      say("made", `group ${name}`);
    }
    ids[name] = group.id;
  }
  const policies = must(await admin.get("/api/v1/access-policies"), "reading access policies");
  let listEditor = policies.find((p) => p.name === "List editor");
  if (listEditor) {
    say("ok", "access policy List editor");
  } else if (checkOnly) {
    say("missing", "access policy List editor");
  } else {
    listEditor = must(
      await admin.post("/api/v1/access-policies", { name: "List editor", description: "May add a value to a picklist while filling in a record.", abilities: ["field-values.add"] }),
      "making the List editor policy",
    );
    say("made", "access policy List editor (one ability: add values to picklists)");
  }
  const byName = Object.fromEntries(policies.map((p) => [p.name, p]));
  if (listEditor) byName["List editor"] = listEditor;
  groups = must(await admin.get("/api/v1/groups"), "reading groups");
  for (const [groupName, wanted] of Object.entries(GROUP_POLICIES)) {
    const group = groups.find((g) => g.name === groupName);
    if (!group) continue;
    for (const policyName of wanted) {
      const policy = byName[policyName];
      if (!policy) {
        say("missing", `access policy ${policyName} (open Audit maintenance once as an administrator, then run again)`);
      } else if (group.policies.some((p) => p.id === policy.id)) {
        say("ok", `${groupName} holds ${policyName}`);
      } else if (checkOnly) {
        say("missing", `${groupName} holds ${policyName}`);
      } else {
        must(await admin.post(`/api/v1/groups/${group.id}/policies`, { policyId: policy.id }), `giving ${policyName} to ${groupName}`);
        say("made", `${groupName} holds ${policyName}`);
      }
    }
    for (const person of PEOPLE.filter((p) => p.groups.includes(groupName))) {
      const membership = members.find((m) => m.email === person.email);
      if (!membership) continue;
      if (group.people.includes(membership.id)) {
        say("ok", `${person.email} is in ${groupName}`);
      } else if (checkOnly) {
        say("missing", `${person.email} is in ${groupName}`);
      } else {
        must(await admin.post(`/api/v1/groups/${group.id}/members`, { membershipId: membership.id }), `adding ${person.email} to ${groupName}`);
        say("made", `${person.email} is in ${groupName}`);
      }
    }
  }
  return ids;
}

/** The lists of Location and Department belong to the organization: add the Accounts department under both plants. */
async function ensureLists(admin) {
  const object = must(await admin.get("/api/v1/metadata/objects/DepartmentGroup"), "reading the Department group object");
  const department = object.fields.find((f) => f.apiName === "department");
  const allowed = department.settings.dependency.allowed;
  const hasValue = department.settings.values.some((v) => v.value === "ACC");
  const withBoth = (list = []) => [...new Set([...list, "HR", "ACC"])];
  const missingPair = (plant) => !["HR", "ACC"].every((d) => (allowed[plant] ?? []).includes(d));
  if (hasValue && !missingPair("PLANT1") && !missingPair("PLANT2")) return say("ok", "the Department list has HR and Accounts under Plant 1 and Plant 2");
  if (checkOnly) return say("missing", "the Department list needs Accounts (ACC), with HR and ACC under Plant 1 and Plant 2");
  const values = hasValue ? department.settings.values : [...department.settings.values, { value: "ACC", label: "ACC - Accounts", active: true }];
  must(
    await admin.put("/api/v1/metadata/objects/DepartmentGroup/fields/department", {
      label: department.label,
      description: department.description,
      required: department.required,
      version: department.version,
      settings: { values, dependency: { controllingField: "location", allowed: { ...allowed, PLANT1: withBoth(allowed.PLANT1), PLANT2: withBoth(allowed.PLANT2) } } },
    }),
    "saving the Department list",
  );
  say("made", "the Department list: Accounts (ACC); HR and ACC under Plant 1 and Plant 2");
}

async function ensureDepartmentGroups(admin, groupIds) {
  const listed = must(await admin.get("/api/v1/objects/DepartmentGroup/records"), "reading the Department groups");
  for (const row of DEPARTMENT_GROUPS) {
    const label = `Department group ${row.name} (${row.location}, ${row.department})`;
    if (listed.some((r) => r.fields.location === row.location && r.fields.department === row.department)) {
      say("ok", label);
    } else if (checkOnly || !groupIds[row.name]) {
      say("missing", label);
    } else {
      must(await admin.post("/api/v1/objects/DepartmentGroup/records", { fields: { name: row.name, location: row.location, department: row.department, groupId: groupIds[row.name] } }), label);
      say("made", label);
    }
  }
}

async function ensureProcess(admin, groupIds) {
  const existing = must(await admin.get("/api/v1/metadata/approvals?object=InspectionTemplate"), "reading approval processes");
  if (existing.length > 0) return say("ok", `approval process for templates: ${existing.map((p) => p.label).join(", ")} (left as it is)`);
  if (checkOnly || !groupIds["Plant QMS"] || !groupIds["Corporate QMS"]) return say("missing", "the approval process for templates");
  must(
    await admin.post("/api/v1/metadata/approvals/InspectionTemplate", {
      name: "TemplateReview",
      label: "Template review",
      description: "Plant QMS checks and numbers the template; Corporate QMS approves it. Corporate QMS is skipped when its group is empty.",
      active: true,
      allowSelfApproval: false,
      steps: [
        { name: "PlantQms", label: "Plant QMS", completion: "ANY", skipWhenNobody: false, approvers: [{ kind: "GROUP", value: groupIds["Plant QMS"] }] },
        { name: "CorporateQms", label: "Corporate QMS", completion: "ANY", skipWhenNobody: true, approvers: [{ kind: "GROUP", value: groupIds["Corporate QMS"] }] },
      ],
    }),
    "making the approval process",
  );
  say("made", "approval process Template review (step 1 Plant QMS, step 2 Corporate QMS, skipped when empty)");
}

async function main() {
  const seed = seedPassword();
  // The password rules of a new account ask for a symbol, which the seed password has none of: the demo people sign in
  // with LOCAL_SEED_PASSWORD followed by an exclamation mark, so no second secret is written anywhere.
  const password = `${seed}!`;
  console.log(checkOnly ? "Checking the demo setup (nothing is changed)\n" : "Setting up the demo in tenant-a (what exists is left alone)\n");
  const platform = await signIn(PLATFORM_HOST, "platform-a@example.test", seed);
  const orgs = must(await platform.get("/api/v1/platform/organizations?search=tenant-a"), "finding the organization");
  const org = orgs.find((o) => o.slug === "tenant-a");
  if (!org) throw new Error("the organization tenant-a was not found");
  const admin = await signIn(ORG_HOST, "admin-a@example.test", seed);
  await admin.get("/api/v1/applications"); // opening the application makes the shipped access policies on first use
  await ensureLicences(platform, org.id);
  const members = await ensurePeople(admin, password);
  const groupIds = await ensureGroups(admin, members);
  await ensureLists(admin);
  await ensureDepartmentGroups(admin, groupIds);
  await ensureProcess(admin, groupIds);
  const counts = {};
  for (const kind of log) counts[kind] = (counts[kind] ?? 0) + 1;
  console.log(`\nDone: ${JSON.stringify(counts)}`);
  if (log.includes("missing")) process.exitCode = 1;
}

// run by hand (not when another script imports the helpers below)
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((problem) => {
    console.error(`\nStopped: ${problem.message}`);
    process.exitCode = 2;
  });
}

export { signIn, must, seedPassword, raw, ORG_HOST, PLATFORM_HOST };
