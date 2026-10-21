// Runs the hostile corpus and the good documents against the validator. Exit code 1 when a hostile document is
// accepted, a good one refused, or any message repeats the text of the document.
import { checkRaw } from "./validate.mjs";
import { makeTemplate, fullGridCellsThatFit, bytesOf, cellKey } from "../lib/model.mjs";

const MARK = "HOSTILEMARK";
const base = () => makeTemplate({ rows: 6, columns: 4 });
const good = [];
const bad = [];
const withDoc = (name, f) => {
  const d = base();
  f(d);
  return [name, JSON.stringify(d)];
};

good.push(["scenario 8x20 (2 merged header cells)", JSON.stringify(makeTemplate({ rows: 20, columns: 8 }))]);
good.push(["one dimension, repeatable rows", JSON.stringify(makeTemplate({ columns: 10, dimension: 1 }))]);
good.push(["60 x 15", JSON.stringify(makeTemplate({ rows: 60, columns: 15 }))]);
good.push(["500 x 100 sparse (5% fixed cells)", JSON.stringify(makeTemplate({ rows: 500, columns: 100, fixedRatio: 0.05 }))]);
good.push(["a comparison sign with a space is text", JSON.stringify((() => { const d = base(); d.cells[cellKey(d.rows[0].id, d.columns[0].id)].value = "Limit 5 < 6 and 7 > 3"; return d; })())]);

bad.push(["a cell of 10 MB", JSON.stringify((() => { const d = base(); d.cells[cellKey(d.rows[0].id, d.columns[0].id)].value = MARK + "x".repeat(10_000_000); return d; })()), "too_large"]);
bad.push(["a cell of 1,001 characters", JSON.stringify((() => { const d = base(); d.cells[cellKey(d.rows[0].id, d.columns[0].id)].value = MARK + "x".repeat(1000); return d; })()), "shape"]);
bad.push(["nesting 10,000 levels deep", "[".repeat(10_000) + "]".repeat(10_000), "too_deep"]);
bad.push(["a document that is not JSON", MARK + "{{{", "not_json"]);
bad.push(["an unknown top-level part", JSON.stringify({ ...base(), [MARK]: 1 }), "shape"]);
bad.push(["format version 2", JSON.stringify({ ...base(), formatVersion: 2 }), "shape"]);
bad.push(["a cell key that is not row:column", JSON.stringify((() => { const d = base(); d.cells[MARK + "<script>"] = { value: "x" }; return d; })()), "shape"]);
bad.push(["a prototype-pollution key", '{"__proto__":{"x":1},"formatVersion":1,"dimension":2,"rowsMode":"FIXED","columns":[],"rows":[],"cells":{},"merges":[]}', "shape"]);
bad.push(["an identifier of 41 characters", JSON.stringify((() => { const d = base(); d.columns[0].id = "c" + MARK.padEnd(40, "z"); return d; })()), "shape"]);
bad.push(["a numeric cell value", JSON.stringify((() => { const d = base(); d.cells[cellKey(d.rows[0].id, d.columns[0].id)].value = 12; return d; })()), "shape"]);
bad.push(["600 rows", JSON.stringify(makeTemplate({ rows: 600, columns: 3 })), "shape"]);
bad.push(["101 columns", JSON.stringify(makeTemplate({ rows: 3, columns: 101 })), "shape"]);
for (const [n, f, code] of [
  ["markup in a column label", (d) => { d.columns[1].label = `<script>alert('${MARK}')</script>`; }, "unsafe_text"],
  ["markup in a row label", (d) => { d.rows[1].label = `<img src=x onerror=${MARK}>`; }, "unsafe_text"],
  ["markup in a cell", (d) => { d.cells[cellKey(d.rows[0].id, d.columns[0].id)].value = `<b>${MARK}</b>`; }, "unsafe_text"],
  ["a control character in a label", (d) => { d.columns[0].label = MARK + "\u0000"; }, "unsafe_text"],
  ["two columns with one identifier", (d) => { d.columns[1].id = d.columns[0].id; }, "duplicate_id"],
  ["a row and a column with one identifier", (d) => { d.rows[0].id = d.columns[0].id; }, "duplicate_id"],
  ["a cell on a row that does not exist", (d) => { d.cells["nope:" + d.columns[0].id] = { value: MARK }; }, "cell_unknown"],
  ["overlapping merges", (d) => { d.merges.push({ from: cellKey(d.headerRows[0].id, d.columns[1].id), to: cellKey(d.headerRows[1].id, d.columns[3].id) }); }, "merge_overlap"],
  ["a merge from header to body", (d) => { d.merges = [{ from: cellKey(d.headerRows[1].id, d.columns[0].id), to: cellKey(d.rows[0].id, d.columns[1].id) }]; }, "merge_zones"],
  ["a merge written backwards", (d) => { d.merges = [{ from: cellKey(d.headerRows[0].id, d.columns[2].id), to: cellKey(d.headerRows[0].id, d.columns[0].id) }]; }, "merge_order"],
  ["a merge of one cell", (d) => { d.merges = [{ from: cellKey(d.rows[0].id, d.columns[0].id), to: cellKey(d.rows[0].id, d.columns[0].id) }]; }, "merge_single"],
  ["a merge to a cell that does not exist", (d) => { d.merges = [{ from: cellKey(d.rows[0].id, d.columns[0].id), to: "nope:" + d.columns[1].id }]; }, "merge_unknown"],
  ["a typed-in preset that is not a number", (d) => { d.cells[cellKey(d.rows[0].id, d.columns[2].id)] = { value: MARK, kind: "input" }; }, "value_type"],
  ["an input cell in the header", (d) => { d.cells[cellKey(d.headerRows[1].id, d.columns[0].id)] = { value: "x", kind: "input" }; }, "header_input"],
  ["a list column without values", (d) => { d.columns[1].type = "picklist"; d.columns[1].picklist = null; }, "picklist_missing"],
  ["a list with a value twice", (d) => { d.columns[1].type = "picklist"; d.columns[1].picklist = { values: [MARK, MARK] }; }, "picklist_duplicate"],
  ["a default that does not fit the type", (d) => { d.columns[2].type = "date"; d.columns[2].default = MARK; }, "default_type"],
  ["a template of one dimension that names rows and is repeatable only", (d) => { d.dimension = 1; d.rowsMode = "REPEATABLE"; }, "rows_mode"],
]) bad.push(withDoc(n, f).concat([code]));

let failed = 0;
const rows = [];
const run = (name, raw, expect) => {
  const t0 = performance.now();
  const problems = checkRaw(raw);
  const ms = performance.now() - t0;
  const text = JSON.stringify(problems);
  const leak = text.includes(MARK) || text.includes("script") || /alert|onerror/.test(text);
  const ok = expect === undefined ? problems.length === 0 : problems.length > 0 && problems[0].code === expect;
  if (!ok || leak) failed++;
  rows.push(`${ok && !leak ? "ok  " : "FAIL"} ${name.padEnd(62)} ${expect === undefined ? "accepted" : (problems[0]?.code ?? "ACCEPTED").padEnd(16)} ${ms.toFixed(1).padStart(7)} ms${leak ? "  LEAKS TEXT" : ""}${!ok ? `  got ${JSON.stringify(problems[0] ?? null)}` : ""}`);
};
for (const [n, raw] of good) run(n, raw);
for (const [n, raw, code] of bad) run(n, raw, code);
console.log(rows.join("\n"));
const fit = fullGridCellsThatFit({ chars: 20 });
console.log(`\nLargest grid 500 x 100 = ${fit.ofCells} cells; with the 1,000,000 byte cap ${fit.count} fixed cells of 20 characters fit (${((fit.count / fit.ofCells) * 100).toFixed(1)} percent of the grid); the empty frame alone is ${fit.baseBytes} bytes.`);
const fit2 = fullGridCellsThatFit({ chars: 5 });
console.log(`With fixed cells of 5 characters: ${fit2.count} fit (${((fit2.count / fit2.ofCells) * 100).toFixed(1)} percent).`);
void bytesOf;
console.log(failed ? `\n${failed} FAILED` : "\nall as expected; no message repeated a typed text");
process.exit(failed ? 1 : 0);
