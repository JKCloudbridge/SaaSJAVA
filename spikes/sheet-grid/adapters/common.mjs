// Shared by the three adapters: positions of the template in a library's sheet, and the way back.
import { cellKey } from "../lib/model.mjs";
import { displayRows, indexOf } from "../lib/gridOps.mjs";

export function layout(doc) {
  const rows = displayRows(doc);
  const { rowPos, colPos } = indexOf(doc, rows);
  return { rows, rowPos, colPos };
}

// Flat description every adapter starts from: one entry per stored cell, one rectangle per merge (display positions).
export function flatten(doc) {
  const { rows, rowPos, colPos } = layout(doc);
  const cells = [];
  for (const [key, cell] of Object.entries(doc.cells)) {
    const [rid, cid] = key.split(":");
    cells.push({ r: rowPos.get(rid), c: colPos.get(cid), value: cell.value == null ? null : String(cell.value), bold: !!cell.style?.bold, italic: !!cell.style?.italic });
  }
  const merges = doc.merges.map((m) => {
    const [fr, fc] = m.from.split(":"), [tr, tc] = m.to.split(":");
    return { r0: rowPos.get(fr), c0: colPos.get(fc), r1: rowPos.get(tr), c1: colPos.get(tc) };
  });
  return { rows, cells, merges };
}

// The way back: entries read from a library (display positions) become the canonical cells and merges again.
export function rebuild(doc, entries, merges) {
  const { rows } = layout(doc);
  const cells = {};
  // a library may copy the content or style of a merged cell onto the cells it covers; the canonical form keeps it on the
  // top left cell only
  const { rowPos, colPos } = layout(doc);
  void rowPos; void colPos;
  const covered = new Set();
  for (const m of merges) for (let r = m.r0; r <= m.r1; r++) for (let c = m.c0; c <= m.c1; c++) if (r !== m.r0 || c !== m.c0) covered.add(`${r}:${c}`);
  for (const e of entries) {
    if (covered.has(`${e.r}:${e.c}`)) continue;
    if (e.r < 0 || e.r >= rows.length || e.c < 0 || e.c >= doc.columns.length) continue;
    if ((e.value == null || e.value === "") && !e.bold && !e.italic) continue;
    const cell = {};
    if (e.value != null && e.value !== "") cell.value = e.value;
    if (e.bold || e.italic) cell.style = { ...(e.bold ? { bold: true } : {}), ...(e.italic ? { italic: true } : {}) };
    if (cell.value !== undefined) cell.kind = "fixed";
    cells[cellKey(rows[e.r].id, doc.columns[e.c].id)] = cell;
  }
  const out = merges.map((m) => ({ from: cellKey(rows[m.r0].id, doc.columns[m.c0].id), to: cellKey(rows[m.r1].id, doc.columns[m.c1].id) }));
  return { ...doc, cells, merges: out };
}

export const colName = (j) => {
  let s = "", n = j + 1;
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
};
export const normalise = (doc) => JSON.parse(JSON.stringify(doc));
