// POC 1: the browser evaluator against (a) the documented behaviours the Java side also checks, (b) the values the spreadsheet program stored for the 29
// real formulas (fixtures in POC1_DATA, never in the repository), (c) the translators and the refusals.
//   POC1_DATA=<folder> node --test lib/poc1Formula.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { toIds, toExcel, parse, evaluate, XlError, FormulaRefused, show } from "./poc1Formula.mjs";

const layout = { colIds: Array.from({ length: 200 }, (_, i) => `c${i + 1}`), rowIds: Array.from({ length: 2000 }, (_, i) => `r${i + 1}`) };
const colIdx = (L) => [...L].reduce((v, ch) => v * 26 + ch.charCodeAt(0) - 64, 0) - 1;

function makeCtx(values, formulas, rowId, memo = new Map()) {
  const key = (c, r) => `${c}|${r}`;
  const ctx = {
    row: rowId,
    cell(c, r) {
      const k = key(c, r);
      if (formulas.has(k)) { if (!memo.has(k)) memo.set(k, evaluate(parse(formulas.get(k)), { ...ctx, row: r })); return memo.get(k); }
      return values.get(k);
    },
    range(a, b) {
      const c0 = layout.colIds.indexOf(a.col), c1 = layout.colIds.indexOf(b.col), r0 = layout.rowIds.indexOf(a.row), r1 = layout.rowIds.indexOf(b.row);
      const out = [];
      for (let r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) for (let c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) {
        const v = ctx.cell(layout.colIds[c], layout.rowIds[r]); if (v !== undefined) out.push(v); else out.push(undefined);
      }
      return out;
    },
  };
  return ctx;
}

// (a) small cases: [name, Excel-style formula at K1, expected, A1.. inputs]; undefined input = blank
const B = undefined;
const CASES = [
  ["blank in arithmetic", "A1+1", 1, B], ["number as text in arithmetic", '"5"+1', 6], ["text cell in arithmetic", "A1+1", "#VALUE!", "abc"],
  ["divide by zero", "1/0", "#DIV/0!"], ["error through IF condition", "IF(1/0>0,1,2)", "#DIV/0!"], ["blank guard, blank", 'IF(A1="","",A1*2)', "", B],
  ["blank guard, number", 'IF(A1="","",A1*2)', 8, 4], ["SUM range ignores text and blank", "SUM(A1:A3)", 1, 1], ["SUM direct text", 'SUM("5",1)', 6],
  ["MIN of blanks", "MIN(A1:A2)", 0, B, B], ["MAX ignores blank", "MAX(A1:A2)", -3, -3, B], ["MIN with a division error inside", "MIN(100,(B1/A1)*100)", "#DIV/0!", 0, 5],
  ["ROUND half up", "ROUND(2.5,0)", 3], ["ROUND half away from zero", "ROUND(-2.5,0)", -3], ["ROUND 2.675", "ROUND(2.675,2)", 2.68], ["ROUND 1.005", "ROUND(1.005,2)", 1.01],
  ["AND ignores a blank reference", "AND(TRUE,A1)", true, B], ["OR of false", "OR(FALSE,FALSE)", false], ["AND of numbers", "AND(1,0)", false],
  ["ISBLANK of blank", "ISBLANK(A1)", true, B], ["blank equals zero", "A1=0", true, B], ["zero is not the empty text", 'A1=""', false, 0],
  ["text comparison ignores case", '"a"="A"', true], ["IF with text condition", "IF(A1,1,2)", "#VALUE!", "x"], ["TRUE counts as 1", "1+TRUE", 2],
  ["binary floating point", "0.1+0.2", 0.3], ["empty text constant in arithmetic", "A1*2", "#VALUE!", ""],
  ["supplier pattern, blank first cell", 'IF(A1="","",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))', "", B, 5, 6],
  ["supplier pattern, values", 'IF(A1="","",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))', 95, 10, 9, 10],
  ["supplier pattern, capped", 'IF(A1="","",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))', 100, 10, 10, 12],
  ["supplier pattern, below the cap", 'IF(A1="","",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))', 75, 10, 7, 8],
  ["percent literal", "50%*2", 1], ["unary minus binds tighter than power", "-2^2", 4], ["comparison of text and number", '"a">1', true],
];
test("small cases agree with the documented behaviour", () => {
  let bad = 0;
  for (const [name, f, expected, ...inputs] of CASES) {
    const values = new Map(); inputs.forEach((v, i) => { if (v !== undefined) values.set(`c${i + 1}|r1`, v); });
    const got = evaluate(parse(toIds(f, 1, layout)), makeCtx(values, new Map(), "r1"));
    const same = typeof expected === "number" ? typeof got === "number" && Math.abs(got - expected) <= 1e-9 : got instanceof XlError ? got.code === expected : got === expected;
    if (!same) { bad++; console.log("  DIFFERS:", name, "expected", expected, "got", show(got)); }
  }
  console.log(`small cases: ${CASES.length} run, ${bad} differ`);
  assert.equal(bad, 0);
});

// (b) the real formulas
const DATA = process.env.POC1_DATA;
function realCheck(file, sheets) {
  const fx = JSON.parse(readFileSync(`${DATA}/${file}`, "utf8"));
  let total = 0, equal = 0, errors = 0;
  for (const si of sheets) {
    const sh = fx.sheets[si];
    const values = new Map(), formulas = new Map(), cached = [];
    for (const c of sh.cells) {
      const k = `c${c.c + 1}|r${c.r + 1}`;
      if (c.t === "n" || c.t === "s" || c.t === "b") values.set(k, c.v);
      else if (c.t === "f") { formulas.set(k, toIds(c.f, c.r + 1, layout)); cached.push({ k, c }); }
    }
    const memo = new Map();
    for (const { k, c } of cached) {
      total++;
      const rowId = `r${c.r + 1}`;
      const got = makeCtx(values, formulas, rowId, memo).cell(`c${c.c + 1}`, rowId);
      if (c.vt === "n") { if (typeof got === "number" && Math.abs(got - c.v) <= 1e-9) equal++; }
      else if (c.vt === "s") { if (got === c.v || (got === undefined && c.v === "")) equal++; }
      else if (got instanceof XlError) { equal++; errors++; }
    }
  }
  return { total, equal, errors };
}
test("the real formulas equal the value the spreadsheet program stored", { skip: !DATA || !existsSync(`${DATA}/spm.json`) }, () => {
  const plant = realCheck("plant.json", [0, 1]);
  const spm = realCheck("spm.json", [2]);
  console.log(`real formulas: plant ${plant.equal}/${plant.total}, supplier ${spm.equal}/${spm.total} (of which the spreadsheet program stored an error: ${spm.errors})`);
  assert.equal(plant.equal, plant.total); assert.equal(spm.equal, spm.total);
});

// (c) translators and refusals
test("translators round trip and refuse what they must", () => {
  for (const f of ["$A$1+1", "A$1*$B2", "SUM(D4:D9)", 'IF(A3="A1","",A3)', "LOG10"]) { /* LOG10 is a name: only the text is compared */ }
  for (const f of ["$A$1+1", "A$1*$B2", "SUM(D4:D9)", 'IF(A3="A1","",A3)', "ROUND(A1,2)", "1E5+A1"]) assert.equal(toExcel(toIds(f, 3, layout), 3, layout), f);
  assert.throws(() => toIds("Sheet2!A1", 1, layout), (e) => e.key === "character_not_allowed");
  assert.throws(() => toIds("ZZ1+1", 1, layout), (e) => e.key === "column_outside_sheet");
  assert.throws(() => parse("INDIRECT(A1)"), (e) => e instanceof FormulaRefused && e.key === "function_not_allowed");
  assert.throws(() => parse("(".repeat(40) + "1" + ")".repeat(40)), (e) => e.key === "too_deep");
  assert.throws(() => parse("1+".repeat(3000) + "1"), (e) => e.key === "too_long");
  assert.throws(() => parse("NOW()"), (e) => e.key === "function_not_allowed");
  assert.throws(() => parse('"a"&"b"'), (e) => e.key === "character_not_allowed");
});
