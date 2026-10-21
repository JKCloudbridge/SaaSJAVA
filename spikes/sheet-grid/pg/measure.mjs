// PostgreSQL measurements for FA0 (B3 and B4 of the prompt). Throwaway schema `fa0_spike` in the LOCAL database,
// dropped at the end (`node pg/measure.mjs cleanup`). Measured operations run as the application role `platform_app`
// with the real tenant setting inside a transaction, so forced row level security applies; the owner role only makes
// and drops the schema and reads sizes. Usage: node pg/measure.mjs <phase> [phase...]
//   phases: setup templates history fills compress cleanup      (all = setup templates history fills compress)
import pg from "pg";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { gzipSync } from "node:zlib";
import { makeTemplate, bytesOf, seeded, makeIds, cellKey } from "../lib/model.mjs";

const env = Object.fromEntries(
  readFileSync(new URL("../../../infra/local/.env", import.meta.url), "utf8")
    .split(/\r?\n/).filter((l) => l && !l.startsWith("#") && l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const base = { host: "localhost", port: Number(env.POSTGRES_PORT ?? 5432), database: env.POSTGRES_DB, max: 4 };
const owner = new pg.Pool({ ...base, user: env.POSTGRES_USER, password: env.POSTGRES_PASSWORD });
const app = new pg.Pool({ ...base, user: env.APP_DB_USER, password: env.APP_DB_PASSWORD });
const S = "fa0_spike";
const TENANT_A = "0a0a0a0a-0000-4000-8000-00000000000a";
const TENANT_B = "0b0b0b0b-0000-4000-8000-00000000000b";
mkdirSync(new URL("../results/", import.meta.url), { recursive: true });
const results = {};
const save = (name, value) => { results[name] = value; writeFileSync(new URL(`../results/pg-${name}.json`, import.meta.url), JSON.stringify(value, null, 2)); };

// ---- helpers --------------------------------------------------------------------------------------------------
async function inTenant(tenant, fn) {
  const c = await app.connect();
  try {
    await c.query("begin");
    await c.query("select set_config('app.current_tenant', $1, true)", [tenant]);
    const r = await fn(c);
    await c.query("commit");
    return r;
  } catch (e) { await c.query("rollback").catch(() => {}); throw e; } finally { c.release(); }
}
async function timed(tenant, fn) {
  const t0 = performance.now();
  await inTenant(tenant, fn);
  return performance.now() - t0;
}
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))]; };
const stat = (xs) => ({ n: xs.length, p50: +pct(xs, 50).toFixed(2), p95: +pct(xs, 95).toFixed(2), max: +Math.max(...xs).toFixed(2) });
const mb = (b) => +(Number(b) / 1048576).toFixed(2);
const one = async (sql, args) => (await owner.query(sql, args)).rows[0];

function docOfSize(target, rows, columns, seed) {
  let lo = 0, hi = 1, best = makeTemplate({ rows, columns, fixedRatio: 0, seed });
  for (let i = 0; i < 14; i++) {
    const mid = (lo + hi) / 2;
    const d = makeTemplate({ rows, columns, fixedRatio: mid, seed });
    if (bytesOf(d) <= target) { best = d; lo = mid; } else hi = mid;
  }
  return best;
}
const SHAPES = [["20 KB (typical)", 20_000, 100, 10], ["100 KB", 100_000, 300, 30], ["500 KB", 500_000, 500, 100], ["1 MB (the cap)", 990_000, 500, 100]];

// ---- phases -----------------------------------------------------------------------------------------------------
async function setup() {
  await owner.query(`drop schema if exists ${S} cascade`);
  await owner.query(`create schema ${S}`);
  for (const [t, comp] of [["tv_pglz", "pglz"], ["tv_lz4", "lz4"]]) {
    await owner.query(`
      create table ${S}.${t} (
        id uuid primary key default uuidv7(),
        tenant_id uuid not null default platform_current_tenant(),
        template_id uuid not null,
        version_no int not null,
        status text not null default 'DRAFT',
        grid jsonb compression ${comp} not null,
        grid_hash bytea not null,
        created_at timestamptz not null default now(),
        version bigint not null default 0
      )`);
    await owner.query(`create unique index ${t}_u on ${S}.${t} (tenant_id, template_id, version_no)`);
    await owner.query(`alter table ${S}.${t} enable row level security, force row level security`);
    await owner.query(`create policy ${t}_tenant on ${S}.${t} using (tenant_id = (select platform_current_tenant())) with check (tenant_id = (select platform_current_tenant()))`);
  }
  for (const [t, comp] of [["fill_pglz", "pglz"], ["fill_lz4", "lz4"], ["fill_gz", "pglz"]]) {
    await owner.query(`
      create table ${S}.${t} (
        id uuid primary key default uuidv7(),
        tenant_id uuid not null default platform_current_tenant(),
        template_id uuid not null,
        template_version int not null,
        filled_by uuid not null,
        status text not null default 'DRAFT',
        ${t === "fill_gz" ? "cells bytea not null" : `cells jsonb compression ${comp} not null`},
        created_at timestamptz not null default now(),
        version bigint not null default 0
      )`);
    await owner.query(`alter table ${S}.${t} alter column cells set storage external`).catch(() => {});
    if (t !== "fill_gz") await owner.query(`alter table ${S}.${t} alter column cells set storage extended`);
    await owner.query(`create index ${t}_list on ${S}.${t} (tenant_id, template_id, created_at desc, id desc)`);
    await owner.query(`alter table ${S}.${t} enable row level security, force row level security`);
    await owner.query(`create policy ${t}_tenant on ${S}.${t} using (tenant_id = (select platform_current_tenant())) with check (tenant_id = (select platform_current_tenant()))`);
  }
  await owner.query(`grant usage on schema ${S} to ${env.APP_DB_USER}`);
  await owner.query(`grant select, insert, update on all tables in schema ${S} to ${env.APP_DB_USER}`);
  const disk = await one("select pg_size_pretty(pg_database_size(current_database())) as db");
  console.log("setup done; database size now", disk.db);
}

async function baseline() {
  const xs = [];
  for (let i = 0; i < 200; i++) xs.push(await timed(TENANT_A, (c) => c.query("select 1")));
  save("baseline", stat(xs));
  console.log("baseline (empty transaction with the tenant setting)", JSON.stringify(stat(xs)));
}

async function templates() {
  const out = [];
  for (const [label, target, rows, cols] of SHAPES) {
    for (const t of ["tv_pglz", "tv_lz4"]) {
      const N = target >= 500_000 ? 15 : 40;
      const docs = Array.from({ length: N }, (_, i) => docOfSize(target, rows, cols, 100 + i));
      const raws = docs.map((d) => JSON.stringify(d));
      const tpl = randomUUID();
      const ins = [], rd = [], rdList = [];
      const ids = [];
      for (let i = 0; i < N; i++) {
        let id;
        ins.push(await timed(TENANT_A, async (c) => {
          id = (await c.query(`insert into ${S}.${t}(template_id, version_no, grid, grid_hash) values ($1,$2,$3::jsonb,decode('00','hex')) returning id`, [tpl, i + 1, raws[i]])).rows[0].id;
        }));
        ids.push(id);
      }
      for (let rep = 0; rep < 3; rep++) for (const id of ids) rd.push(await timed(TENANT_A, async (c) => { await c.query(`select grid from ${S}.${t} where id = $1`, [id]); }));
      for (let rep = 0; rep < 3; rep++) rdList.push(await timed(TENANT_A, async (c) => { await c.query(`select id, version_no, status, created_at from ${S}.${t} where template_id = $1 order by version_no desc`, [tpl]); }));
      // a reader of the other organization must see nothing
      const other = await timed(TENANT_B, async (c) => { const r = await c.query(`select count(*)::int n from ${S}.${t} where template_id = $1`, [tpl]); if (r.rows[0].n !== 0) throw new Error("ROW LEVEL SECURITY LEAK"); });
      const sz = await one(`select avg(octet_length(grid::text))::int raw_json, avg(pg_column_size(grid))::int stored, pg_relation_size('${S}.${t}') heap, pg_total_relation_size('${S}.${t}') total,
        coalesce((select pg_total_relation_size(reltoastrelid) from pg_class where oid='${S}.${t}'::regclass),0) toast from ${S}.${t} where template_id = $1`, [tpl]);
      const row = { size: label, table: t, docs: N, avgBytesAsSerialised: Math.round(raws.reduce((a, r) => a + Buffer.byteLength(r), 0) / N), avgJsonbTextBytes: sz.raw_json, avgStoredBytes: sz.stored, insert: stat(ins), readOne: stat(rd), listVersionsNoGrid: stat(rdList), otherTenantCheckMs: +other.toFixed(2) };
      out.push(row);
      console.log(label, t, JSON.stringify({ raw: row.avgBytesAsSerialised, stored: row.avgStoredBytes, insert: row.insert, readOne: row.readOne }));
    }
  }
  save("templates", out);
}

async function history() {
  const res = {};
  for (const [label, target, rows, cols] of [["typical 20 KB", 20_000, 100, 10], ["100 KB", 100_000, 300, 30]]) {
    const tpl = randomUUID();
    const raw = docOfSize(target, rows, cols, 5);
    for (let v = 1; v <= 50; v++) {
      // each version changes a little: one more fixed cell, like a real edit
      const d = JSON.parse(JSON.stringify(raw)); d.cells[cellKey(d.rows[v % d.rows.length].id, d.columns[1].id)] = { value: "edit " + v, kind: "fixed" };
      await inTenant(TENANT_A, (c) => c.query(`insert into ${S}.tv_pglz(template_id, version_no, grid, grid_hash) values ($1,$2,$3::jsonb,decode('00','hex'))`, [tpl, v, JSON.stringify(d)]));
    }
    const noGrid = [], withGrid = [], newest = [], count = [];
    for (let i = 0; i < 20; i++) {
      noGrid.push(await timed(TENANT_A, (c) => c.query(`select id, version_no, status, created_at from ${S}.tv_pglz where template_id = $1 order by version_no desc`, [tpl])));
      withGrid.push(await timed(TENANT_A, (c) => c.query(`select * from ${S}.tv_pglz where template_id = $1 order by version_no desc`, [tpl])));
      newest.push(await timed(TENANT_A, (c) => c.query(`select grid from ${S}.tv_pglz where template_id = $1 order by version_no desc limit 1`, [tpl])));
    }
    const sz = await one(`select sum(pg_column_size(grid))::bigint stored, sum(octet_length(grid::text))::bigint raw from ${S}.tv_pglz where template_id = $1`, [tpl]);
    res[label] = { versions: 50, listWithoutGrid: stat(noGrid), listWithGrid: stat(withGrid), newestVersionGrid: stat(newest), storedBytesFor50: Number(sz.stored), rawJsonBytesFor50: Number(sz.raw) };
    console.log(label, JSON.stringify(res[label]));
  }
  // saving a draft over and over (the draft row is updated in place until it is submitted): table growth and vacuum
  const tpl = randomUUID();
  const d = docOfSize(100_000, 300, 30, 9);
  const id = (await inTenant(TENANT_A, (c) => c.query(`insert into ${S}.tv_pglz(template_id, version_no, grid, grid_hash) values ($1,1,$2::jsonb,decode('00','hex')) returning id`, [tpl, JSON.stringify(d)]))).rows[0].id;
  const before = await one(`select pg_total_relation_size('${S}.tv_pglz') t`);
  const upd = [];
  for (let i = 0; i < 100; i++) {
    d.cells[cellKey(d.rows[i % d.rows.length].id, d.columns[1].id)] = { value: "save " + i, kind: "fixed" };
    upd.push(await timed(TENANT_A, (c) => c.query(`update ${S}.tv_pglz set grid = $2::jsonb, version = version + 1 where id = $1`, [id, JSON.stringify(d)])));
  }
  const grown = await one(`select pg_total_relation_size('${S}.tv_pglz') t`);
  await owner.query(`vacuum ${S}.tv_pglz`);
  const after = await one(`select pg_total_relation_size('${S}.tv_pglz') t`);
  res.draftSaves = { saves: 100, docBytes: 100_000, update: stat(upd), tableMbBefore: mb(before.t), afterSavesMb: mb(grown.t), afterVacuumMb: mb(after.t) };
  console.log("draft saves", JSON.stringify(res.draftSaves));
  save("history", res);
}

// ---- fill-in documents ---------------------------------------------------------------------------------------------
const PICK = ["Pass", "Fail", "Not applicable"];
const TXT = ["No issues found", "Guard missing on the left side", "Checked and valid until next quarter", "Label faded, to be replaced", "OK", "Valve replaced, see attached", "Needs repair"];
function fillDoc({ rows, filled, seed }) {
  const rnd = seeded(seed);
  const rowIds = makeIds(rows, "r", seeded(1)), colIds = makeIds(Math.max(filled, 1), "c", seeded(2));
  const A = {}, B = [], C = [];
  rowIds.forEach((rid, ri) => {
    const rowB = {}, rowC = [];
    colIds.forEach((cid, ci) => {
      const t = ci % 5;
      const v = t === 0 ? TXT[Math.floor(rnd() * TXT.length)] : t === 1 ? PICK[Math.floor(rnd() * 3)] : t === 2 ? String(Math.floor(rnd() * 100000) / 100) : t === 3 ? `2026-${String(1 + Math.floor(rnd() * 12)).padStart(2, "0")}-${String(1 + Math.floor(rnd() * 28)).padStart(2, "0")}` : rnd() < 0.5;
      A[cellKey(rid, cid)] = v; rowB[cid] = v; rowC.push(v);
    });
    B.push({ id: rid, cells: rowB }); C.push(rowC);
  });
  return { A: { template: 1, cells: A }, B: { template: 1, rows: B }, C: { template: 1, order: "columns-then-rows of the template", rows: C } };
}
const DOC_SHAPES = [["50 rows x 10 filled columns (typical)", 50, 10], ["50 rows x 20", 50, 20], ["50 rows x 50", 50, 50], ["50 rows x 100 (rare, full)", 50, 100]];

async function compress() {
  const out = [];
  for (const [label, rows, filled] of DOC_SHAPES) {
    const d = fillDoc({ rows, filled, seed: 11 });
    const row = { shape: label, cellsFilled: rows * filled };
    for (const k of ["A", "B", "C"]) {
      const raw = JSON.stringify(d[k]);
      row[`shape${k}_json_bytes`] = Buffer.byteLength(raw);
      row[`shape${k}_gzip_bytes`] = gzipSync(raw).length;
    }
    // stored size in PostgreSQL by method and by shape (30 different documents each)
    for (const [t, comp] of [["fill_pglz", "pglz"], ["fill_lz4", "lz4"]]) {
      for (const k of ["A", "B", "C"]) {
        const tpl = randomUUID();
        const times = [];
        for (let i = 0; i < 30; i++) {
          const doc = fillDoc({ rows, filled, seed: 200 + i })[k];
          times.push(await timed(TENANT_A, (c) => c.query(`insert into ${S}.${t}(template_id, template_version, filled_by, cells) values ($1,1,$2,$3::jsonb)`, [tpl, randomUUID(), JSON.stringify(doc)])));
        }
        const sz = await one(`select avg(pg_column_size(cells))::int stored from ${S}.${t} where template_id = $1`, [tpl]);
        row[`${comp}_shape${k}_stored_bytes`] = sz.stored;
        row[`${comp}_shape${k}_insert_ms`] = stat(times);
      }
    }
    out.push(row);
    console.log(JSON.stringify(row));
  }
  save("compress", out);
}

async function fills() {
  const out = {};
  // sizes of the three shapes at the four fill sizes are in `compress`; here the volume: load, then measure.
  const [label, rows, filled] = DOC_SHAPES[0];
  await owner.query(`truncate ${S}.fill_pglz`);
  const V = 400; // distinct real documents, cycled by the bulk insert (every row is still compressed on its own)
  await owner.query(`drop table if exists ${S}.fill_variants`);
  await owner.query(`create table ${S}.fill_variants (n int primary key, doc jsonb not null)`);
  for (let i = 0; i < V; i++) await owner.query(`insert into ${S}.fill_variants values ($1, $2::jsonb)`, [i, JSON.stringify(fillDoc({ rows, filled, seed: 1000 + i }).A)]);
  await owner.query(`grant select on ${S}.fill_variants to ${env.APP_DB_USER}`);
  const perYear = 365_000;
  const templatesCount = Number(process.env.FILL_TEMPLATES ?? 1);
  const tplIds = Array.from({ length: templatesCount }, () => randomUUID());
  const loadTimes = [];
  const t0 = performance.now();
  for (const tpl of tplIds) {
    for (let done = 0; done < perYear; done += 20_000) {
      const n = Math.min(20_000, perYear - done);
      const ms = await timed(TENANT_A, (c) => c.query(
        `insert into ${S}.fill_pglz(template_id, template_version, filled_by, cells, created_at)
         select $1, 1, $2, v.doc, now() - ((($3::int + g) * 86.4) * interval '1 second')
         from generate_series(1, $4::int) g join ${S}.fill_variants v on v.n = g % ${V}`, [tpl, randomUUID(), done, n]));
      loadTimes.push(ms / n * 1000);
    }
  }
  const loadSeconds = (performance.now() - t0) / 1000;
  await owner.query(`vacuum analyze ${S}.fill_pglz`);
  const rowsInTable = (await one(`select count(*)::bigint n from ${S}.fill_pglz`)).n;
  const sz = await one(`select pg_relation_size('${S}.fill_pglz') heap, pg_indexes_size('${S}.fill_pglz') idx,
     coalesce((select pg_relation_size(reltoastrelid) from pg_class where oid='${S}.fill_pglz'::regclass),0) toast, avg(pg_column_size(cells))::int stored from ${S}.fill_pglz`);
  out.load = { shape: label, rowsLoaded: Number(rowsInTable), seconds: +loadSeconds.toFixed(1), msPerThousandRows: +(loadTimes.reduce((a, b) => a + b, 0) / loadTimes.length).toFixed(1), heapMb: mb(sz.heap), indexMb: mb(sz.idx), toastMb: mb(sz.toast), storedBytesPerFill: sz.stored };
  console.log("load", JSON.stringify(out.load));
  // the list: newest first, 50 per page, one template, with and without the cells
  const tpl = tplIds[0];
  const first = [], firstCells = [], deep = [], deepCells = [], keyset = [];
  for (let i = 0; i < 30; i++) {
    first.push(await timed(TENANT_A, (c) => c.query(`select id, filled_by, status, created_at, version from ${S}.fill_pglz where template_id=$1 order by created_at desc, id desc limit 50`, [tpl])));
    firstCells.push(await timed(TENANT_A, (c) => c.query(`select * from ${S}.fill_pglz where template_id=$1 order by created_at desc, id desc limit 50`, [tpl])));
    const off = 50 * (1 + Math.floor(Math.random() * 7000));
    deep.push(await timed(TENANT_A, (c) => c.query(`select id, filled_by, status, created_at from ${S}.fill_pglz where template_id=$1 order by created_at desc, id desc limit 50 offset ${off}`, [tpl])));
    deepCells.push(await timed(TENANT_A, (c) => c.query(`select * from ${S}.fill_pglz where template_id=$1 order by created_at desc, id desc limit 50 offset ${off}`, [tpl])));
    const mid = (await inTenantRows(`select created_at, id from ${S}.fill_pglz where template_id=$1 order by created_at desc, id desc limit 1 offset ${off}`, [tpl]))[0];
    keyset.push(await timed(TENANT_A, (c) => c.query(`select id, filled_by, status, created_at from ${S}.fill_pglz where template_id=$1 and (created_at, id) < ($2, $3) order by created_at desc, id desc limit 50`, [tpl, mid.created_at, mid.id])));
  }
  out.list = { page1_noCells: stat(first), page1_withCells: stat(firstCells), deepOffset_noCells: stat(deep), deepOffset_withCells: stat(deepCells), keysetDeep_noCells: stat(keyset), templateRows: perYear };
  console.log("list", JSON.stringify(out.list));
  save("fills", out);
}
async function inTenantRows(sql, args) { let rows; await inTenant(TENANT_A, async (c) => { rows = (await c.query(sql, args)).rows; }); return rows; }

async function cleanup() {
  await owner.query(`drop schema if exists ${S} cascade`);
  console.log("schema dropped; database size now", (await one("select pg_size_pretty(pg_database_size(current_database())) s")).s);
}

const phases = process.argv.slice(2);
const plan = phases.includes("all") ? ["setup", "baseline", "templates", "history", "compress", "fills"] : phases;
const table = { setup, baseline, templates, history, fills, compress, cleanup };
try {
  for (const p of plan) { console.log("== phase", p); await table[p](); }
} finally {
  await app.end(); await owner.end();
}
void TENANT_B;
