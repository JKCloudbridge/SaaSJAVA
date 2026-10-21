// Validator of the canonical grid (FA0 prototype of what FA3 writes in Java). Every problem has a fixed code, fixed
// words and a place made only of ids that match the id pattern. It never repeats a value, a label or a key that a
// person typed (the grid is the organization's text: ADR-0012, Sprint 15).
import Ajv2020 from "ajv/dist/2020.js";
import { readFileSync } from "node:fs";
import { LIMITS, ID_PATTERN } from "../lib/model.mjs";
import { unsafeText, valueProblem } from "../lib/values.mjs";

const schema = JSON.parse(readFileSync(new URL("./sheet-grid.schema.json", import.meta.url), "utf8"));
const ajv = new Ajv2020({ allErrors: false, strict: true, allowUnionTypes: true, verbose: false });
const validateShape = ajv.compile(schema);

const WORDS = {
  required: "A required part is missing.",
  type: "A part has the wrong kind of value.",
  additionalProperties: "A part is not allowed here.",
  enum: "A value is not one of the allowed ones.",
  const: "The format version is not supported.",
  pattern: "An identifier or cell reference has an invalid shape.",
  propertyNames: "An identifier or cell reference has an invalid shape.",
  maxLength: "A text is longer than allowed.",
  maxItems: "A list has more entries than allowed.",
  minItems: "A list needs at least one entry.",
  maxProperties: "There are more cells than allowed.",
  minimum: "A number is below the allowed range.",
  maximum: "A number is above the allowed range.",
};

const SAFE = /^[A-Za-z0-9_-]{1,40}$/;
const safePath = (instancePath) =>
  instancePath
    .split("/")
    .filter(Boolean)
    .map((s) => (/^\d+$/.test(s) || ["columns", "rows", "headerRows", "cells", "merges", "picklist", "values", "style", "value", "kind", "default", "width", "height", "label", "type", "id", "from", "to"].includes(s) ? s : SAFE.test(s) ? s : "…"))
    .join(".");

// Before parsing: the size and the nesting of the raw text, so that a hostile document is refused cheaply.
export function preflight(raw, limits = LIMITS) {
  const bytes = Buffer.byteLength(raw, "utf8");
  if (bytes > limits.maxBytes) return [{ code: "too_large", place: "", message: "The grid is larger than the allowed size." }];
  let depth = 0, max = 0, inString = false, escaped = false;
  for (let i = 0; i < raw.length; i++) {
    const ch = raw.charCodeAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === 92) escaped = true;
      else if (ch === 34) inString = false;
      continue;
    }
    if (ch === 34) inString = true;
    else if (ch === 123 || ch === 91) { depth++; if (depth > max) max = depth; if (max > limits.maxDepth) break; }
    else if (ch === 125 || ch === 93) depth--;
  }
  if (max > limits.maxDepth) return [{ code: "too_deep", place: "", message: "The grid is nested deeper than allowed." }];
  return [];
}

const problem = (code, place, message) => ({ code, place, message });

export function validateGrid(doc, limits = LIMITS) {
  const problems = [];
  if (!validateShape(doc)) {
    const e = validateShape.errors[0];
    problems.push(problem("shape", safePath(e.instancePath), WORDS[e.keyword] ?? "A part is not valid."));
    return problems; // the first stage with a problem answers alone, like the write pipeline of Sprint 15
  }
  // text of the organization: no markup and no control characters anywhere (it is always drawn as plain text, this
  // keeps the stored document clean for every later reader: exports, mails, other front ends)
  const texts = [];
  doc.columns.forEach((c, i) => {
    texts.push([c.label, `columns.${i}.label`], [c.default, `columns.${i}.default`]);
    (c.picklist?.values ?? []).forEach((v, j) => texts.push([v, `columns.${i}.picklist.values.${j}`]));
  });
  [...(doc.headerRows ?? []).map((r, i) => [r, `headerRows.${i}`]), ...doc.rows.map((r, i) => [r, `rows.${i}`])]
    .forEach(([r, p]) => texts.push([r.label, `${p}.label`]));
  for (const [key, cell] of Object.entries(doc.cells)) texts.push([cell.value, `cells.${SAFE.test(key.replace(":", "")) || /^[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$/.test(key) ? key : "…"}.value`]);
  for (const [t, place] of texts) {
    if (typeof t === "string" && unsafeText(t)) problems.push(problem("unsafe_text", place, "A text contains markup or a control character."));
  }
  if (problems.length) return problems;
  const colIds = new Set(), rowIds = new Set();
  const colPos = new Map(), rowZone = new Map();
  doc.columns.forEach((c, i) => {
    if (colIds.has(c.id)) problems.push(problem("duplicate_id", `columns.${i}.id`, "An identifier is used twice."));
    colIds.add(c.id);
    colPos.set(c.id, i);
    if (c.type === "picklist") {
      if (!c.picklist) problems.push(problem("picklist_missing", `columns.${i}`, "A list column needs its values."));
      else if (new Set(c.picklist.values).size !== c.picklist.values.length) problems.push(problem("picklist_duplicate", `columns.${i}.picklist`, "A list has the same value twice."));
    } else if (c.picklist) problems.push(problem("picklist_unexpected", `columns.${i}`, "Only a list column takes values."));
  });
  if (doc.columns.length > limits.maxColumns) problems.push(problem("too_many_columns", "columns", "There are more columns than allowed."));
  if (doc.rows.length > limits.maxRows) problems.push(problem("too_many_rows", "rows", "There are more rows than allowed."));
  const rowOrder = [];
  for (const [zone, list] of [["header", doc.headerRows ?? []], ["body", doc.rows]]) {
    list.forEach((r, i) => {
      if (rowIds.has(r.id) || colIds.has(r.id)) problems.push(problem("duplicate_id", `${zone === "header" ? "headerRows" : "rows"}.${i}.id`, "An identifier is used twice."));
      rowIds.add(r.id);
      rowZone.set(r.id, { zone, pos: rowOrder.length });
      rowOrder.push(r.id);
    });
  }
  if (doc.dimension === 1 && doc.rows.length > 0 && doc.rowsMode === "REPEATABLE") problems.push(problem("rows_mode", "rows", "A repeatable-only template names no rows."));
  if (doc.dimension === 1 && doc.rowsMode === "FIXED") problems.push(problem("rows_mode", "rowsMode", "A template that names no rows cannot have fixed rows only."));
  if (doc.dimension === 2 && doc.rowsMode === "REPEATABLE" && doc.rows.length > 0) problems.push(problem("rows_mode", "rowsMode", "A template that names rows cannot be repeatable only."));
  if (problems.length) return problems;
  // cells: point at a real row and column, header cells are fixed, the value fits the column
  for (const [key, cell] of Object.entries(doc.cells)) {
    const [r, c] = key.split(":");
    const place = `cells.${SAFE.test(r) && SAFE.test(c) ? key : "…"}`;
    if (!rowIds.has(r) || !colIds.has(c)) { problems.push(problem("cell_unknown", place, "A cell points at a row or column that does not exist.")); continue; }
    const zone = rowZone.get(r).zone;
    const col = doc.columns[colPos.get(c)];
    if (zone === "header" && cell.kind === "input") problems.push(problem("header_input", place, "A header cell cannot be typed into."));
    if (cell.value != null && zone === "body" && (cell.kind ?? "fixed") === "input") {
      const bad = valueProblem(col, cell.value);
      if (bad) problems.push(problem("value_type", place, bad));
    }
    if (typeof cell.value === "string" && cell.value.length > limits.maxCellChars) problems.push(problem("cell_too_long", place, "A cell text is longer than allowed."));
  }
  doc.columns.forEach((c, i) => {
    if (c.default != null) { const bad = valueProblem(c, c.default); if (bad) problems.push(problem("default_type", `columns.${i}.default`, bad)); }
  });
  if (problems.length) return problems;
  // merges: inside one zone, rectangular, not overlapping, not a single cell
  const taken = new Set();
  doc.merges.forEach((m, i) => {
    const [fr, fc] = m.from.split(":"), [tr, tc] = m.to.split(":");
    const a = rowZone.get(fr), b = rowZone.get(tr);
    if (!a || !b || !colPos.has(fc) || !colPos.has(tc)) return problems.push(problem("merge_unknown", `merges.${i}`, "A merge points at a row or column that does not exist."));
    if (a.zone !== b.zone) return problems.push(problem("merge_zones", `merges.${i}`, "A merge cannot join header rows and body rows."));
    const r0 = a.pos, r1 = b.pos, c0 = colPos.get(fc), c1 = colPos.get(tc);
    if (r1 < r0 || c1 < c0) return problems.push(problem("merge_order", `merges.${i}`, "A merge must run from its top left cell to its bottom right cell."));
    if (r0 === r1 && c0 === c1) return problems.push(problem("merge_single", `merges.${i}`, "A merge needs more than one cell."));
    for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) {
      const k = r * 1000 + c;
      if (taken.has(k)) return problems.push(problem("merge_overlap", `merges.${i}`, "Two merges cover the same cell."));
      taken.add(k);
    }
  });
  return problems;
}

export function checkRaw(raw, limits = LIMITS) {
  const pre = preflight(raw, limits);
  if (pre.length) return pre;
  let doc;
  try { doc = JSON.parse(raw); } catch { return [problem("not_json", "", "The grid is not valid JSON.")]; }
  return validateGrid(doc, limits);
}

export { ID_PATTERN };
