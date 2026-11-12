// POC 2, WP5: the browser evaluator against (a) the documented behaviour written in lib/poc2-formula-cases.txt, (b) POI's own answers
// (a results file written by spike.FormulaCases), (c) the growing range, (d) the refusals, (e) the 21 hostile formulas of POC 1.
//   POC2_POI=<results file> node --test lib/poc2Formula.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { toIds, toExcel, parse, evaluate, checkGrowing, XlError, FormulaRefused, show, FUNCTIONS } from "./poc2Formula.mjs";

const here = new URL(".", import.meta.url);
const layout = { colIds: Array.from({ length: 30 }, (_, i) => `c${i + 1}`), rowIds: Array.from({ length: 60 }, (_, i) => `r${i + 1}`) };
const colIdx = (L) => L.toUpperCase().charCodeAt(0) - 65;

function splitInputs(spec) {
  const parts = []; let cur = ""; let q = false;
  for (const ch of spec) { if (ch === '"') q = !q; if (ch === ";" && !q) { parts.push(cur.trim()); cur = ""; } else cur += ch; }
  if (cur.trim()) parts.push(cur.trim());
  return parts.map((p) => { const i = p.indexOf("="); return [p.slice(0, i).trim(), p.slice(i + 1).trim()]; });
}
function value(v) {
  if (v.startsWith('"')) return v.slice(1, -1);
  if (v === "TRUE") return true; if (v === "FALSE") return false;
  if (v.startsWith("#")) return new XlError(v);
  return Number(v);
}
function makeCtx(cells, bodyRows) {
  const idx = (id) => Number(id.slice(1)) - 1;
  const ctx = {
    row: "r1",
    cell: (c, r) => cells.get(`${c}|${r}`),
    range(a, b) {
      const out = [];
      const c0 = Math.min(idx(a.col), idx(b.col)); const c1 = Math.max(idx(a.col), idx(b.col));
      const r0 = Math.min(idx(a.row), idx(b.row)); const r1 = Math.max(idx(a.row), idx(b.row));
      for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) out.push(cells.get(`c${c + 1}|r${r + 1}`));
      return out;
    },
    bodyRows: () => bodyRows,
  };
  return ctx;
}
function run(formula, spec) {
  const cells = new Map();
  for (const [ref, v] of splitInputs(spec)) cells.set(`c${colIdx(ref[0]) + 1}|r${Number(ref.slice(1))}`, value(v));
  const tree = parse(toIds(formula, 1, layout));
  return evaluate(tree, makeCtx(cells, layout.rowIds));
}
function sameValue(expected, got) {
  if (expected.startsWith('"')) return got === expected.slice(1, -1);
  if (expected.startsWith("#")) return got instanceof XlError && got.code === expected;
  if (expected === "TRUE") return got === true; if (expected === "FALSE") return got === false;
  return typeof got === "number" && Math.abs(got - Number(expected)) <= 1e-9;
}
const CASES = readFileSync(new URL("poc2-formula-cases.txt", here), "utf8").split(/\r?\n/).filter((l) => l.trim() && !l.startsWith("#")).map((l) => {
  const p = l.split("|"); return { name: p[0].trim(), f: p[1].trim(), spec: p[2].trim(), expected: p[3].trim() };
});

test("the browser evaluator equals the documented value on every case it accepts", () => {
  let accepted = 0; let narrowed = 0; const bad = [];
  for (const c of CASES) {
    let got;
    try { got = run(c.f, c.spec); } catch (e) { if (e instanceof FormulaRefused && ["count_needs_cells", "text_format_not_allowed"].includes(e.key)) { narrowed++; continue; } throw e; }
    accepted++;
    if (!sameValue(c.expected, got)) bad.push(`${c.name}: expected ${c.expected} got ${show(got)}`);
  }
  console.log(`browser evaluator: ${CASES.length} cases, ${accepted} accepted (${accepted - bad.length} equal to the documented value, ${bad.length} differ), ${narrowed} outside the narrowed forms`);
  bad.forEach((b) => console.log("  DIFFERS:", b));
  assert.equal(bad.length, 0);
});

const POI = process.env.POC2_POI;
test("three-way table: documented value, POI, browser", { skip: !POI || !existsSync(POI) }, () => {
  const poi = new Map(readFileSync(POI, "utf8").split("\n").filter(Boolean).map((l) => l.split("\t")));
  let all3 = 0; const poiOff = []; const narrowed = [];
  for (const c of CASES) {
    const p = poi.get(c.name);
    const poiOk = p !== undefined && sameValue(c.expected, (() => { if (p.startsWith('"')) return p.slice(1, -1); if (p === "TRUE") return true; if (p === "FALSE") return false; if (p.startsWith("#")) return new XlError(p); return Number(p); })());
    let browserOk;
    try { browserOk = sameValue(c.expected, run(c.f, c.spec)); } catch (e) { if (e instanceof FormulaRefused) { narrowed.push(c.name); browserOk = null; } else throw e; }
    if (browserOk && poiOk) all3++;
    else if (browserOk === true && !poiOk) poiOff.push(`${c.name} (POI said ${p})`);
  }
  console.log(`cases ${CASES.length}: documented = POI = browser on ${all3}; outside the narrowed forms (refused by the gate) ${narrowed.length}: ${narrowed.join("; ")}`);
  console.log(`POI differs from the documented value, browser equal to it: ${poiOff.length}`);
  poiOff.forEach((x) => console.log("  POI DIFFERS:", x));
  assert.equal(all3 + narrowed.length + poiOff.length, CASES.length);
});

// ---------------------------------------------------------------- the growing range
// a dimension 1 sheet: header row h1, body rows r2.. (the filler adds rows), column "val" holds numbers, the formula sits in a total row
test("the growing range: stored once, drawn with the rows that exist, recalculated when rows are added", () => {
  const colIds = ["lab", "val", "tot"];
  const body3 = ["b1", "b2", "b3"];
  const lay = (rows) => ({ colIds, rowIds: ["h1", ...rows, "t1"], bodyRowIds: rows });
  const L3 = lay(body3);
  // the author writes the formula in a total row (row 5 of the 5-row layout): AVERAGE of B2:B4 = every body row
  const stored = toIds("AVERAGE(B2:B4)", 5, L3);
  assert.equal(stored, "AVERAGE(@{val,*})");
  const cells = new Map([["val|b1", 2], ["val|b2", 4], ["val|b3", 9]]);
  const ctx = (rows) => ({ ...makeCtxById(cells, rows), row: "t1" });
  assert.equal(evaluate(parse(stored), ctx(body3)), 5);
  // the stored text does not change when the filler adds two rows; the drawn text and the answer do
  const L5 = lay([...body3, "b4", "b5"]);
  cells.set("val|b4", 10); cells.set("val|b5", 15);
  assert.equal(toExcel(stored, 7, L5), "AVERAGE(B2:B6)");
  assert.equal(evaluate(parse(stored), ctx(L5.bodyRowIds)), 8);
  // the same stored text round trips to the same stored text at either size
  assert.equal(toIds(toExcel(stored, 7, L5), 7, L5), stored);
  assert.equal(toIds(toExcel(stored, 5, L3), 5, L3), stored);
  // a range that is NOT every body row keeps its two identifiers
  assert.equal(toIds("SUM(B2:B3)", 5, L3), "SUM(@{val,b1}:@{val,b2})");
  // an open end: from a named row to the last body row, written by the editor's own gesture; drawn with the current last row
  assert.equal(toExcel("SUM(@{val,b2}:@{val,*})", 5, L3), "SUM(B3:B4)");
  assert.equal(toExcel("SUM(@{val,b2}:@{val,*})", 7, L5), "SUM(B3:B6)");
  assert.equal(evaluate(parse("SUM(@{val,b2}:@{val,*})"), ctx(L5.bodyRowIds)), 4 + 9 + 10 + 15);
  // a total row is not part of its own range: a row flagged total is not a body row, so a total of the total column adds no loop
  cells.set("val|t1", 999);
  assert.equal(evaluate(parse(stored), ctx(L5.bodyRowIds)), 8);
  // a formula in a body row may not name its own column with the token (it would be part of its own range)
  assert.throws(() => checkGrowing(parse("SUM(@{val,*})"), "val", true), (e) => e.key === "circular");
  checkGrowing(parse("SUM(@{val,*})"), "val", false); // allowed in a total row
  checkGrowing(parse("SUM(@{val,*})"), "tot", true);  // allowed from another column
  // no body rows: the token draws nothing and the answer is the function's empty answer
  assert.throws(() => toExcel(stored, 3, { colIds, rowIds: ["h1", "t1"], bodyRowIds: [] }), (e) => e.key === "no_body_rows");
  assert.equal(evaluate(parse("SUM(@{val,*})"), { ...makeCtxById(new Map(), []), row: "t1" }), 0);
  // a reference outside the sheet is still refused
  assert.throws(() => toIds("SUM(B2:B99)", 5, L3), (e) => e.key === "row_outside_sheet");
});
function makeCtxById(cells, rows) {
  return {
    row: "t1",
    cell: (c, r) => cells.get(`${c}|${r}`),
    range(a, b) {
      const order = rows; const i0 = order.indexOf(a.row); const i1 = order.indexOf(b.row);
      return order.slice(Math.min(i0, i1), Math.max(i0, i1) + 1).map((r) => cells.get(`${a.col}|${r}`));
    },
    bodyRows: () => rows,
  };
}

// ---------------------------------------------------------------- the gate
test("the allowlist: every function outside it, and every kind of token outside it, is refused", () => {
  const hostile = [
    "INDIRECT(A1)", "TODAY()", "NOW()", "RAND()", "RANDBETWEEN(1,2)", "WEBSERVICE(A1)", "HYPERLINK(A1)", "OFFSET(A1,1,1)", "CELL(A1)", "INFO(A1)",
    "EVALUATE(A1)", "CALL(A1)", "FILTERXML(A1,A2)", "ENCODEURL(A1)", "SUMPRODUCT(A1:A2,B1:B2)", "VLOOKUP(A1,A1:B2,2)", "N(A1)", "A1:A2 B1:B2",
    "Sheet2!A1", "[x.xlsx]S!A1", "A1&B1", "{1,2}", "'a'!A1", "name", "FOO(1)", "SUM(A1:A2", "1+", ")", "TEXT(A1,\"dd/mm\")", "TEXT(A1,A2)", "COUNT(1,2)",
    "ROUND(1)", "MOD(1)", "ABS()", "IF(1)", "SWITCH(1,2)", "(".repeat(40) + "1" + ")".repeat(40), "1+".repeat(3000) + "1",
  ];
  let refused = 0;
  for (const h of hostile) { try { parse(toIds(h, 1, layout)); } catch (e) { if (e instanceof FormulaRefused) refused++; else throw e; } }
  console.log(`hostile formulas: ${hostile.length}, refused ${refused}`);
  assert.equal(refused, hostile.length);
  // the allowlist is exactly the proposed set minus N
  const expectedSet = "ABS MOD INT ROUND ROUNDUP ROUNDDOWN SQRT POWER PRODUCT IF IFERROR AND OR NOT ISNUMBER ISTEXT ISBLANK SUMIF COUNTIF AVERAGEIF IFS SWITCH SUM AVERAGE MEDIAN MODE STDEV VAR COUNT COUNTA MIN MAX LARGE SMALL RANK VALUE TEXT CONCATENATE LEFT RIGHT LEN UPPER TRIM".split(" ");
  assert.deepEqual([...FUNCTIONS].sort(), expectedSet.sort());
});

test("the formulas of the two real forms still agree (when the fixtures are there)", { skip: !process.env.POC1_DATA || !existsSync(`${process.env.POC1_DATA}/spm.json`) }, () => {
  const DATA = process.env.POC1_DATA;
  const colIds = Array.from({ length: 200 }, (_, i) => `c${i + 1}`); const rowIds = Array.from({ length: 2000 }, (_, i) => `r${i + 1}`);
  const lay = { colIds, rowIds };
  let total = 0; let equal = 0;
  for (const [file, sheets] of [["plant.json", [0, 1]], ["spm.json", [2]]]) {
    const fx = JSON.parse(readFileSync(`${DATA}/${file}`, "utf8"));
    for (const si of sheets) {
      const sh = fx.sheets[si]; const values = new Map(); const formulas = new Map(); const cached = [];
      for (const c of sh.cells) {
        const k = `c${c.c + 1}|r${c.r + 1}`;
        if (c.t === "n" || c.t === "s" || c.t === "b") values.set(k, c.v);
        else if (c.t === "f") { formulas.set(k, toIds(c.f, c.r + 1, lay)); cached.push(c); }
      }
      const memo = new Map();
      const ctx = {
        row: "r1",
        cell(col, row) { const k = `${col}|${row}`; if (formulas.has(k)) { if (!memo.has(k)) memo.set(k, evaluate(parse(formulas.get(k)), { ...ctx, row })); return memo.get(k); } return values.get(k); },
        range(a, b) { const out = []; const c0 = Number(a.col.slice(1)); const c1 = Number(b.col.slice(1)); const r0 = Number(a.row.slice(1)); const r1 = Number(b.row.slice(1));
          for (let r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) for (let c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) out.push(ctx.cell(`c${c}`, `r${r}`)); return out; },
        bodyRows: () => rowIds,
      };
      for (const c of cached) {
        total++;
        const got = ctx.cell(`c${c.c + 1}`, `r${c.r + 1}`);
        if (c.vt === "n" ? typeof got === "number" && Math.abs(got - c.v) <= 1e-9 : c.vt === "s" ? got === c.v || (got === 0 && c.v === "") : got instanceof XlError) equal++;
      }
    }
  }
  console.log(`real formulas through the extended evaluator: ${equal} of ${total}`);
  assert.equal(equal, total);
});
