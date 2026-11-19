#!/usr/bin/env node
// Demo setup for a developer machine (the `local` profile): makes the people, groups, access policies, department
// groups and the approval processes (templates, change requests and fill-ins) that the Document Management demo needs, in
// `tenant-a`, through the public API only, so everything it makes is ordinary data that can be looked at, changed and
// removed in Setup. For an organization that has Management Core it also makes the roles of the tree, the five levels and
// the rule that sends documents numbered F with Part A MC to Management Core (MC1.1, ADR-0259).
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
// group -> the policies it holds ("List editor" is made here: it holds one ability and no right on data).
// Publisher is the group with the last word: Plant QMS (FA6G). Corporate QMS approves and suggests where the template is
// meant to be published; Plant QMS publishes, with that suggestion ready in the popup.
const GROUP_POLICIES = {
  "Corporate QMS": ["Approver"],
  "Plant QMS": ["Approver", "List editor", "Publisher"],
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
const LICENCES_WANTED = 40;

// ---- Management Core (MC1.1, ADR-0259): only when the organization has the application ----
// Every document is made in Document Management; the number Plant QMS gives it decides which application it also belongs
// to (the rule below). People hold roles (the tree is drawn here); groups are filled with people, never by role; the five
// levels are groups. Roles alone give no sight of a document: the groups' access policies do.
const ROLES = [
  { name: "CEO", parent: null },
  { name: "Corporate QMS", parent: "CEO" },
  { name: "Reviewers", parent: "CEO" },
  { name: "Plant QMS", parent: "Corporate QMS" },
  { name: "Department users", parent: "Plant QMS" },
  { name: "Management users", parent: "Plant QMS" },
  { name: "Plant head", parent: "Management users" },
];
const MANAGEMENT_PEOPLE = [
  { email: "ceo-1@example.test", name: "CEO 1", groups: ["Level 5"], role: "CEO" },
  { email: "top-management-1@example.test", name: "Top management 1", groups: ["Level 5"], role: "CEO" },
  { email: "department-qms-1@example.test", name: "Department QMS 1", groups: ["Level 2"], role: "Department users" },
  { email: "management-user-1@example.test", name: "Management user 1", groups: ["Level 2"], role: "Management users" },
  // MC2A: each plant has its own group of plant heads (the people who receive a document published to the plants)
  { email: "plant-head-1@example.test", name: "Plant head 1", groups: ["Level 1", "Plant 1 plant heads"], role: "Plant head" },
  { email: "plant-head-2@example.test", name: "Plant head 2", groups: ["Level 1", "Plant 2 plant heads"], role: "Plant head" },
  { email: "plant-head-3@example.test", name: "Plant head 3", groups: ["Level 1", "Plant 1 plant heads"], role: "Plant head" },
  // The people a creator can choose as reviewers of a plant (one by one, or through the group of reviewers)
  { email: "reviewer-1@example.test", name: "Reviewer 1", groups: ["Reviewers"], role: "Reviewers" },
  { email: "reviewer-2@example.test", name: "Reviewer 2", groups: ["Review group", "Reviewers"], role: "Reviewers" },
  { email: "reviewer-3@example.test", name: "Reviewer 3", groups: ["Review group", "Reviewers"], role: "Reviewers" },
];
// the people of the Document Management demo who also stand at a level, and the role they hold
const LEVEL_OF = {
  "corp-qms-1@example.test": { group: "Level 4", role: "Corporate QMS" },
  "corp-qms-2@example.test": { group: "Level 4", role: "Corporate QMS" },
  "plant-qms-1@example.test": { group: "Level 3", role: "Plant QMS" },
  "plant-qms-2@example.test": { group: "Level 3", role: "Plant QMS" },
};
// Real addresses never live in the repository: DEMO_EMAIL_OVERRIDES is a JSON object that maps a demo address to the one
// to use on this machine, for example {"plant-qms-1@example.test":"someone@company.example"}.
const OVERRIDES = JSON.parse(process.env.DEMO_EMAIL_OVERRIDES ?? "{}");
for (const person of [...PEOPLE, ...MANAGEMENT_PEOPLE]) person.email = OVERRIDES[person.email] ?? person.email;
for (const [from, to] of Object.entries(OVERRIDES)) {
  if (LEVEL_OF[from]) {
    LEVEL_OF[to] = LEVEL_OF[from];
    delete LEVEL_OF[from];
  }
}
const LEVEL_GROUP_POLICIES = {
  "Level 1": ["Filler", "Reader"],
  "Level 2": ["Author", "Filler", "Reader"],
  "Level 3": ["Approver", "Publisher", "Reader"],
  "Level 4": ["Approver", "Publisher", "Reader"],
  "Level 5": ["Observer"],
  // MC2A: the groups of plant heads that the Department group records of the fixed department point at, one per plant
  "Plant 1 plant heads": ["Filler", "Reader"],
  "Plant 2 plant heads": ["Filler", "Reader"],
  // a group of reviewers a creator can choose for a plant; it holds no access policy of its own
  "Review group": [],
  // Every person who may be chosen as a reviewer (2026-11-17): Reader lets them open the one fill-in they are asked
  // to review (the platform shares it with them when it is sent); it shows them no other plant's fill-ins.
  "Reviewers": ["Reader"],
};
// Plant QMS publishes a document to Level 2 (and Level 1) of a plant: a department of that name and a Department group
// record for each plant that points at the level's group
const LEVEL_DEPARTMENTS = [
  { value: "LEVEL1", label: "LEVEL1 - Level 1", group: "Level 1" },
  { value: "LEVEL2", label: "LEVEL2 - Level 2", group: "Level 2" },
];
// MC2A: the department that receives a document published to the plants; each plant points at its own group
const PLANT_HEAD_DEPARTMENT = { value: "PLANTHEAD", label: "PLANTHEAD - Plant head" };
const MANAGEMENT_DEPARTMENT_GROUPS = [
  ...LEVEL_DEPARTMENTS.flatMap((level) => ["PLANT1", "PLANT2"]
    .map((location) => ({ name: level.group, location, department: level.value }))),
  { name: "Plant 1 plant heads", location: "PLANT1", department: PLANT_HEAD_DEPARTMENT.value },
  { name: "Plant 2 plant heads", location: "PLANT2", department: PLANT_HEAD_DEPARTMENT.value },
];
// "Identification F with Part A MC belongs to Management Core"
const ROUTING_RULE = { identification: "F", partA: "MC", application: "management-core" };

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
      if (invited.status === 409) {
        // An open invitation exists whose mail may be gone (the mail catcher keeps nothing across a restart): send it again.
        const open = must(await admin.get("/api/v1/invitations"), "reading invitations").find((i) => i.email === person.email && i.status === "OPEN");
        if (open) must(await admin.post(`/api/v1/invitations/${open.id}/resend`), `resending the invitation of ${person.email}`);
      }
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
        say("missing", `access policy ${policyName} (open Quality Management System once as an administrator, then run again)`);
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
      description: "Plant QMS checks and numbers the template; Corporate QMS approves it and suggests where it is published (skipped when its group is empty); Plant QMS approves again and then publishes.",
      active: true,
      allowSelfApproval: false,
      steps: [
        { name: "PlantQms", label: "Plant QMS", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [{ kind: "GROUP", value: groupIds["Plant QMS"] }] },
        { name: "CorporateQms", label: "Corporate QMS", completion: "ANY", skipWhenNobody: true, asksForChoice: true, approvers: [{ kind: "GROUP", value: groupIds["Corporate QMS"] }] },
        { name: "Publish", label: "Publish", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [{ kind: "GROUP", value: groupIds["Plant QMS"] }] },
      ],
    }),
    "making the approval process",
  );
  say("made", "approval process Template review (step 1 Plant QMS, step 2 Corporate QMS asks for a location and is skipped when empty, step 3 Publish)");
}

// The second process (FA6E, ADR-0199; FA8, ADR-0202): a change request goes through its own review, on the small record the
// platform makes for each request. The person who asks is the department person; the steps are Plant QMS, Corporate QMS (which
// suggests where the revised sheet is published) and Plant QMS again, whose confirmation is the last approval and publishes the
// revised sheet as the next revision. The last step cannot be skipped. It names the same groups as the template review, but its
// steps are its own: changing one process in Setup, Approval processes, changes nothing in the other.
async function ensureRequestProcess(admin, groupIds) {
  const existing = must(await admin.get("/api/v1/metadata/approvals?object=InspectionChangeRequest"), "reading approval processes");
  if (existing.length > 0) return say("ok", `approval process for change requests: ${existing.map((p) => p.label).join(", ")} (left as it is)`);
  if (checkOnly || !groupIds["Plant QMS"] || !groupIds["Corporate QMS"]) return say("missing", "the approval process for change requests");
  must(
    await admin.post("/api/v1/metadata/approvals/InspectionChangeRequest", {
      name: "ChangeRequestReview",
      label: "Change request review",
      description: "Plant QMS reviews the request and makes the revised copy; Corporate QMS approves and suggests where it is published; Plant QMS confirms the last step, which publishes the revised sheet as the next revision.",
      active: true,
      allowSelfApproval: false,
      steps: [
        { name: "PlantQms", label: "Plant QMS", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [{ kind: "GROUP", value: groupIds["Plant QMS"] }] },
        { name: "CorporateQms", label: "Corporate QMS", completion: "ANY", skipWhenNobody: true, asksForChoice: true, approvers: [{ kind: "GROUP", value: groupIds["Corporate QMS"] }] },
        { name: "PublishStep", label: "Plant QMS publishes", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [{ kind: "GROUP", value: groupIds["Plant QMS"] }] },
      ],
    }),
    "making the approval process for change requests",
  );
  say("made", "approval process Change request review (step 1 Plant QMS, step 2 Corporate QMS asks for a location, step 3 Plant QMS publishes)");
}

/** A value of the Department list (a department the organization publishes to), under both plants. */
async function ensureDepartmentValue(admin, wanted) {
  const object = must(await admin.get("/api/v1/metadata/objects/DepartmentGroup"), "reading the Department group object");
  const department = object.fields.find((f) => f.apiName === "department");
  const allowed = department.settings.dependency.allowed;
  const hasValue = department.settings.values.some((v) => v.value === wanted.value);
  const missingPair = (plant) => !(allowed[plant] ?? []).includes(wanted.value);
  if (hasValue && !missingPair("PLANT1") && !missingPair("PLANT2")) return say("ok", `the Department list has ${wanted.label} under Plant 1 and Plant 2`);
  if (checkOnly) return say("missing", `the Department list needs ${wanted.label} under Plant 1 and Plant 2`);
  const values = hasValue ? department.settings.values : [...department.settings.values, { value: wanted.value, label: wanted.label, active: true }];
  const withValue = (list = []) => [...new Set([...list, wanted.value])];
  must(
    await admin.put("/api/v1/metadata/objects/DepartmentGroup/fields/department", {
      label: department.label,
      description: department.description,
      required: department.required,
      version: department.version,
      settings: { values, dependency: { controllingField: "location", allowed: { ...allowed, PLANT1: withValue(allowed.PLANT1), PLANT2: withValue(allowed.PLANT2) } } },
    }),
    "saving the Department list",
  );
  say("made", `the Department list: ${wanted.label} under Plant 1 and Plant 2`);
}

/** The roles of the tree, and the role of each person who has one. Roles decide nothing about who sees a document. */
async function ensureRoles(admin, people) {
  let roles = must(await admin.get("/api/v1/roles"), "reading roles");
  for (const role of ROLES) {
    if (roles.some((r) => r.name === role.name)) {
      say("ok", `role ${role.name}`);
    } else if (checkOnly) {
      say("missing", `role ${role.name}`);
    } else {
      const parent = role.parent ? roles.find((r) => r.name === role.parent) : null;
      must(await admin.post("/api/v1/roles", { name: role.name, description: "Demo role", parentId: parent?.id }), `making role ${role.name}`);
      roles = must(await admin.get("/api/v1/roles"), "reading roles");
      say("made", `role ${role.name}${role.parent ? ` (under ${role.parent})` : ""}`);
    }
  }
  const current = must(await admin.get("/api/v1/members"), "reading members");
  for (const person of people.filter((p) => p.role)) {
    const member = current.find((m) => m.email === person.email);
    const role = roles.find((r) => r.name === person.role);
    if (!member || !role) continue;
    if (member.roleName === person.role) {
      say("ok", `${person.email} holds the role ${person.role}`);
    } else if (checkOnly) {
      say("missing", `${person.email} holds the role ${person.role}`);
    } else {
      must(await admin.put(`/api/v1/members/${member.id}/role`, { roleId: role.id }), `the role of ${person.email}`);
      say("made", `${person.email} holds the role ${person.role}`);
    }
  }
}

/** The rule that sends a document numbered F with Part A MC to Management Core (Setup, Numbering, Application routing). */
async function ensureRouting(admin) {
  const view = await admin.get("/api/v1/document-routing");
  if (view.status >= 300) return say("missing", "the routing rule (the application routing endpoint did not answer; make the rule in Setup, Numbering, Application routing)");
  const found = view.json.data.rules.some((r) => r.identification === ROUTING_RULE.identification && r.partA === ROUTING_RULE.partA);
  if (found) return say("ok", "routing rule: F with Part A MC belongs to Management Core");
  if (checkOnly) return say("missing", "routing rule: F with Part A MC belongs to Management Core");
  must(await admin.put("/api/v1/document-routing", { ...ROUTING_RULE }), "making the routing rule");
  say("made", "routing rule: F with Part A MC belongs to Management Core");
}

/**
 * The approval processes of fill-ins. The engine takes the one active process whose entry criteria the fill-in meets and
 * refuses when two fit, so the two below never fit together: one for documents that belong to Document Management alone
 * (application empty), one for documents routed to Management Core (MC2A: the reviewers chosen for the plant, then top
 * management; the score and the Action plan are MC2B). A process that exists is left as it is unless the caller asks for
 * it to be brought up to date (`upgrade`), which saves the same process with the steps wanted.
 */
async function ensureFillProcess(admin, wanted, steps) {
  const existing = must(await admin.get("/api/v1/metadata/approvals?object=InspectionFill"), "reading approval processes");
  const found = existing.find((p) => p.apiName === wanted.name || p.apiName === `${wanted.name}__c`);
  // a rule of the kind PUBLICATION_REVIEWERS (MC2A) names nobody: the reviewers are chosen for each document and plant
  const missingGroup = steps.some((step) => step.approvers.some((rule) => rule.kind !== "PUBLICATION_REVIEWERS" && !rule.value));
  const shapeOf = (list) => list.map((step) => step.approvers.map((rule) => `${rule.kind}:${rule.value ?? ""}`).join(",")).join("|");
  const sameCriteria = (found?.entryCriteria ?? "") === (wanted.criteria ?? "");
  if (found && (!wanted.upgrade || (shapeOf(found.steps) === shapeOf(steps) && sameCriteria))) return say("ok", `approval process for fill-ins: ${wanted.label} (left as it is)`);
  if (checkOnly || missingGroup) return say("missing", `the approval process for fill-ins: ${wanted.label}`);
  if (found) {
    must(
      await admin.put(`/api/v1/metadata/approvals/InspectionFill/${found.apiName}`, {
        label: wanted.label,
        description: wanted.description,
        active: true,
        entryCriteria: wanted.criteria,
        allowSelfApproval: false,
        steps,
        version: found.version,
      }),
      `bringing the approval process ${wanted.label} up to date`,
    );
    return say("made", `approval process ${wanted.label} brought up to date (${steps.map((s) => s.label).join(", then ")})`);
  }
  must(
    await admin.post("/api/v1/metadata/approvals/InspectionFill", {
      name: wanted.name,
      label: wanted.label,
      description: wanted.description,
      entryCriteria: wanted.criteria,
      active: true,
      allowSelfApproval: false,
      steps,
    }),
    `making the approval process ${wanted.label}`,
  );
  say("made", `approval process ${wanted.label} (applies when ${wanted.criteria})`);
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
  const applications = must(await admin.get("/api/v1/applications"), "reading the applications"); // opening them makes the shipped access policies on first use
  // Management Core is set up only for an organization that has it (give the plan feature in the console first).
  const hasManagementCore = applications.some((a) => a.apiName === "management-core");
  if (hasManagementCore) {
    for (const person of PEOPLE) {
      const level = LEVEL_OF[person.email];
      if (level) {
        person.groups.push(level.group);
        person.role = level.role;
      }
    }
    PEOPLE.push(...MANAGEMENT_PEOPLE);
    Object.assign(GROUP_POLICIES, LEVEL_GROUP_POLICIES);
    DEPARTMENT_GROUPS.push(...MANAGEMENT_DEPARTMENT_GROUPS);
  } else {
    say("skipped", "Management Core: the organization does not have the feature (give it in the console, then run again)");
  }
  await ensureLicences(platform, org.id);
  const members = await ensurePeople(admin, password);
  const groupIds = await ensureGroups(admin, members);
  await ensureLists(admin);
  if (hasManagementCore) for (const level of [...LEVEL_DEPARTMENTS, PLANT_HEAD_DEPARTMENT]) await ensureDepartmentValue(admin, level);
  await ensureDepartmentGroups(admin, groupIds);
  await ensureProcess(admin, groupIds);
  await ensureRequestProcess(admin, groupIds);
  // The approval of a fill-in is chosen by the application of the fill-in (the field is copied from the document).
  const plantQms = { kind: "GROUP", value: groupIds["Plant QMS"] };
  await ensureFillProcess(admin, { name: "FillInReview", label: "Fill-in review",
    description: "Fill-ins of documents that belong to Document Management alone: Plant QMS reviews them.", criteria: "ISBLANK(application)" },
  [{ name: "PlantQms", label: "Plant QMS", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [plantQms] }]);
  if (hasManagementCore) {
    await ensureRoles(admin, PEOPLE);
    await ensureRouting(admin);
    // One step (owner's decision, 2026-11-17): the reviewers the creator chose for the plant of the fill-in (one
    // process for every document); any one of them Submits and the fill-in is finished. Level 5 takes no part in
    // the review; top management reads the dashboard. A process made with the earlier Level 5 step is upgraded.
    const plantReviewers = [
      { name: "PlantReviewers", label: "Plant reviewers", completion: "ANY", skipWhenNobody: false, asksForChoice: false, approvers: [{ kind: "PUBLICATION_REVIEWERS" }] },
    ];
    await ensureFillProcess(admin, { name: "ManagementCoreFillInReview", label: "Management Core fill-in review",
      description: "Fill-ins of documents routed to Management Core: the reviewers chosen for the plant review them and Submit.",
      criteria: "application = 'management-core' AND ISBLANK(parentFill)", upgrade: true }, plantReviewers);
    // MC2B: the Action plan is a fill-in too, told apart by its link (parentFill). Its reviewers are those of the plant
    // of the fill-in it answers (the same rule PUBLICATION_REVIEWERS reads through the link); one step as well.
    await ensureFillProcess(admin, { name: "ManagementCoreActionPlanReview", label: "Management Core Action plan review",
      description: "Action plans of Management Core: the reviewers of the plant review them and Submit.",
      criteria: "application = 'management-core' AND NOT(ISBLANK(parentFill))", upgrade: true }, plantReviewers);
  }
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
