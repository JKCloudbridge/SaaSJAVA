// Pure operations on the canonical grid (what FA3 will call gridModel). Every operation returns a patch; applying a
// patch returns the new document and the patch that undoes it, so undo and redo never copy the whole document.
import { cellKey } from "./model.mjs";
import { parseTyped } from "./values.mjs";

// Rows in display order below the label band: header rows, body rows, then (one dimension) blank input rows.
export function displayRows(doc, blankRows = 5) {
  const out = [];
  for (const h of doc.headerRows ?? []) out.push({ id: h.id, zone: "header", height: h.height ?? 24 });
  for (const r of doc.rows) out.push({ id: r.id, zone: "body", height: r.height ?? 24 });
  if (doc.dimension === 1) for (let i = 0; i < blankRows; i++) out.push({ id: `blank${i}`, zone: "blank", height: 24 });
  return out;
}

export function indexOf(doc, rows) {
  const rowPos = new Map(rows.map((r, i) => [r.id, i]));
  const colPos = new Map(doc.columns.map((c, i) => [c.id, i]));
  return { rowPos, colPos };
}

// Merges as rectangles in display positions.
export function mergeRects(doc, rowPos, colPos) {
  const rects = [];
  for (const m of doc.merges) {
    const [fr, fc] = m.from.split(":"), [tr, tc] = m.to.split(":");
    const r0 = rowPos.get(fr), r1 = rowPos.get(tr), c0 = colPos.get(fc), c1 = colPos.get(tc);
    if ([r0, r1, c0, c1].some((v) => v === undefined)) continue;
    rects.push({ key: m.from, r0, c0, r1, c1 });
  }
  return rects;
}

export function applyPatch(doc, patch) {
  const inverse = { cells: {}, merges: undefined };
  let cells = doc.cells;
  if (patch.cells && Object.keys(patch.cells).length) {
    cells = { ...doc.cells };
    for (const [k, v] of Object.entries(patch.cells)) {
      inverse.cells[k] = doc.cells[k] ?? null;
      if (v === null) delete cells[k]; else cells[k] = v;
    }
  }
  let merges = doc.merges;
  if (patch.merges) { inverse.merges = doc.merges; merges = patch.merges; }
  return { doc: { ...doc, cells, merges }, inverse };
}

const clean = (cell) => {
  const style = cell.style && (cell.style.bold || cell.style.italic) ? { ...(cell.style.bold ? { bold: true } : {}), ...(cell.style.italic ? { italic: true } : {}) } : undefined;
  const out = { ...cell, style };
  if (!out.style) delete out.style;
  if (out.value == null) delete out.value;
  if (out.value === undefined && !out.style && !out.kind) return null;
  if (out.value == null && !out.style) return null;
  return out;
};

// Is the cell something the person may type into in this mode?
export function editable(doc, row, col, cellAt, mode) {
  if (row.zone === "header") return mode === "author";
  const cell = cellAt;
  const fixed = cell && (cell.kind ?? (cell.value != null ? "fixed" : "input")) === "fixed";
  return mode === "author" ? true : !fixed;
}

// A person typed `text` into a cell. Author mode writes fixed text, fill mode writes an input value checked against
// the column type. Returns {patch} or {error}.
export function typeInto(doc, row, col, text, mode) {
  const key = cellKey(row.id, col.id);
  const old = doc.cells[key];
  if (mode === "author") {
    const value = text === "" ? null : text.length > 1000 ? undefined : text;
    if (value === undefined) return { error: "A cell text is longer than allowed." };
    if (value != null && /<[A-Za-z/!?]/.test(value)) return { error: "A text contains markup or a control character." };
    const next = clean({ ...(old ?? {}), value, kind: value == null ? undefined : "fixed" });
    return { patch: { cells: { [key]: next } } };
  }
  const parsed = parseTyped(col, text);
  if (parsed.error) return { error: parsed.error };
  const next = clean({ ...(old ?? {}), value: parsed.value, kind: "input" });
  return { patch: { cells: { [key]: next } } };
}

export function toggleStyle(doc, keys, prop) {
  const allOn = keys.every((k) => doc.cells[k]?.style?.[prop]);
  const cells = {};
  for (const k of keys) {
    const old = doc.cells[k] ?? {};
    cells[k] = clean({ ...old, style: { ...(old.style ?? {}), [prop]: !allOn } });
  }
  return { patch: { cells } };
}

// Merge the rectangle [r0..r1] x [c0..c1] (display positions). Only inside one zone, nothing overlapping.
export function mergeRect(doc, rows, rowPos, colPos, rect) {
  const { r0, r1, c0, c1 } = rect;
  if (r0 === r1 && c0 === c1) return { error: "A merge needs more than one cell." };
  const zone = rows[r0].zone;
  for (let r = r0; r <= r1; r++) if (rows[r].zone !== zone) return { error: "A merge cannot join header rows and body rows." };
  if (zone === "blank") return { error: "Merge cells in the rows the template names, not in the blank rows." };
  for (const m of mergeRects(doc, rowPos, colPos)) {
    if (m.r0 <= r1 && m.r1 >= r0 && m.c0 <= c1 && m.c1 >= c0) return { error: "Two merges cannot cover the same cell." };
  }
  const from = cellKey(rows[r0].id, doc.columns[c0].id), to = cellKey(rows[r1].id, doc.columns[c1].id);
  const cells = {};
  for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) {
    const k = cellKey(rows[r].id, doc.columns[c].id);
    if (k !== from && doc.cells[k]) cells[k] = null; // like a spreadsheet: only the top left cell keeps its content
  }
  return { patch: { cells, merges: [...doc.merges, { from, to }] } };
}

export function unmergeAt(doc, rowPos, colPos, r, c) {
  const hit = mergeRects(doc, rowPos, colPos).find((m) => m.r0 <= r && m.r1 >= r && m.c0 <= c && m.c1 >= c);
  if (!hit) return { error: "There is no merge here." };
  return { patch: { merges: doc.merges.filter((m) => m.from !== hit.key) } };
}

// A block copied from a spreadsheet application: tab between cells, line break between rows.
export function parseClipboard(text) {
  const t = text.replace(/\r\n?/g, "\n").replace(/\n$/, "");
  return t.split("\n").map((line) => line.split("\t"));
}

export function pasteBlock(doc, rows, startRow, startCol, matrix, mode, merged) {
  const cells = {};
  let applied = 0, skipped = 0;
  const errors = [];
  let working = doc;
  matrix.forEach((line, i) => line.forEach((text, j) => {
    const r = startRow + i, c = startCol + j;
    if (r >= rows.length || c >= doc.columns.length) { skipped++; return; }
    const key = cellKey(rows[r].id, doc.columns[c].id);
    if (merged.has(key)) { skipped++; return; }
    if (!editable(doc, rows[r], doc.columns[c], working.cells[key], mode)) { skipped++; return; }
    const res = typeInto(working, rows[r], doc.columns[c], text, mode);
    if (res.error) { errors.push(res.error); skipped++; return; }
    Object.assign(cells, res.patch.cells);
    working = { ...working, cells: { ...working.cells, ...res.patch.cells } };
    applied++;
  }));
  return { patch: { cells }, applied, skipped, errors: [...new Set(errors)] };
}

// Display positions ("row,column") the filler may not change: every cell of a header row and every body cell that holds
// fixed text. Used to wire the protection of fixed cells into each library in the fill view.
export function fixedPositions(doc) {
  const rows = displayRows(doc);
  const { rowPos, colPos } = indexOf(doc, rows);
  const fixed = new Set();
  rows.forEach((r, i) => { if (r.zone === "header") doc.columns.forEach((_, j) => fixed.add(`${i},${j}`)); });
  for (const [key, cell] of Object.entries(doc.cells)) {
    const [rid, cid] = key.split(":");
    if ((cell.kind ?? (cell.value != null ? "fixed" : "input")) === "fixed") fixed.add(`${rowPos.get(rid)},${colPos.get(cid)}`);
  }
  return fixed;
}
