// The canonical grid model of the spike (FA0). Pure functions over plain JSON, no library format anywhere.
// The same file is used by the browser pages, the schema corpus and the PostgreSQL measurements.

export const FORMAT_VERSION = 1;

export const LIMITS = Object.freeze({
  maxBytes: 1_000_000, // 1 MB, the cap the product owner decided on 2026-10-21 ("1 MB" is taken as 1,000,000 bytes)
  maxRows: 500,
  maxColumns: 100,
  maxHeaderRows: 10,
  maxCellChars: 1000,
  maxLabelChars: 200,
  maxPicklistValues: 100,
  maxPicklistValueChars: 100,
  maxMerges: 2000,
  maxDepth: 12,
});

export const COLUMN_TYPES = ["text", "number", "date", "picklist", "boolean"];
export const ROWS_MODES = ["FIXED", "REPEATABLE", "FIXED_AND_REPEATABLE"];
export const ID_PATTERN = /^[A-Za-z0-9_-]{1,40}$/;

// A small seeded generator so that every run produces the same documents (measurements can be repeated).
export function seeded(seed = 42) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
export function makeIds(count, prefix, rnd) {
  const seen = new Set();
  const ids = [];
  while (ids.length < count) {
    let id = prefix;
    for (let i = 0; i < 7; i++) id += ALPHABET[Math.floor(rnd() * ALPHABET.length)];
    if (!seen.has(id)) {
      seen.add(id);
      ids.push(id);
    }
  }
  return ids;
}

export const cellKey = (rowId, colId) => `${rowId}:${colId}`;
export const bytesOf = (doc) => Buffer.byteLength(typeof doc === "string" ? doc : JSON.stringify(doc), "utf8");

const WORDS = ["Fire", "Exit", "Guard", "Check", "Valid", "Panel", "Alarm", "Stock", "Clean", "Label", "Safety", "Gauge"];
const phrase = (rnd, n) => Array.from({ length: n }, () => WORDS[Math.floor(rnd() * WORDS.length)]).join(" ");

const TYPE_CYCLE = ["text", "picklist", "number", "date", "boolean"];

// A realistic template: `columns` columns, `rows` named body rows (2-D) or none (1-D), a header band with two merged
// cells, a label in the first column of each row, bold header, italic remarks. `fixedRatio` is the share of the
// remaining body cells that hold fixed text (instructions, limits); the rest are input cells and are not stored.
export function makeTemplate({ rows = 20, columns = 8, dimension = 2, fixedRatio = 0.05, seed = 7 } = {}) {
  const rnd = seeded(seed);
  const colIds = makeIds(columns, "c", rnd);
  const rowIds = dimension === 2 ? makeIds(rows, "r", rnd) : [];
  const headIds = makeIds(2, "h", rnd);
  const cols = colIds.map((id, i) => {
    const type = i === 0 ? "text" : TYPE_CYCLE[i % TYPE_CYCLE.length];
    const col = { id, label: `Column ${i + 1}`, type, required: i % 4 === 0, default: null, picklist: null, width: 120 };
    if (type === "picklist") col.picklist = { values: ["Pass", "Fail", "Not applicable"] };
    return col;
  });
  const headerRows = headIds.map((id) => ({ id, height: 24 }));
  const bodyRows = rowIds.map((id, i) => ({ id, label: `Row ${i + 1}`, height: 24 }));
  const cells = {};
  // header band: a title spanning the first half, a group label spanning the second half
  const half = Math.max(1, Math.floor(columns / 2));
  cells[cellKey(headIds[0], colIds[0])] = { value: "Inspection of installations", style: { bold: true }, kind: "fixed" };
  if (columns > half) cells[cellKey(headIds[0], colIds[half])] = { value: "Findings", style: { bold: true, italic: true }, kind: "fixed" };
  const merges = [];
  if (half > 1) merges.push({ from: cellKey(headIds[0], colIds[0]), to: cellKey(headIds[0], colIds[half - 1]) });
  if (columns - half > 1) merges.push({ from: cellKey(headIds[0], colIds[half]), to: cellKey(headIds[0], colIds[columns - 1]) });
  for (const row of bodyRows) {
    cells[cellKey(row.id, colIds[0])] = { value: `${row.label} ${phrase(rnd, 2)}`, kind: "fixed" };
    for (let c = 1; c < columns; c++) {
      if (rnd() < fixedRatio) {
        cells[cellKey(row.id, colIds[c])] = { value: phrase(rnd, 3), style: c % 3 === 0 ? { italic: true } : undefined, kind: "fixed" };
      }
    }
  }
  return {
    formatVersion: FORMAT_VERSION,
    dimension,
    rowsMode: dimension === 2 ? "FIXED" : "REPEATABLE",
    columns: cols,
    headerRows,
    rows: bodyRows,
    cells,
    merges,
  };
}

// How many fixed text cells of `chars` characters fit in the cap in the largest grid (500 x 100). Returns the count.
export function fullGridCellsThatFit({ rows = 500, columns = 100, chars = 20, cap = LIMITS.maxBytes, seed = 3 } = {}) {
  const rnd = seeded(seed);
  const base = makeTemplate({ rows, columns, dimension: 2, fixedRatio: 0, seed });
  const baseBytes = bytesOf(base);
  const rowIds = base.rows.map((r) => r.id);
  const colIds = base.columns.map((c) => c.id);
  const text = "x".repeat(chars);
  let count = 0;
  let bytes = baseBytes;
  outer: for (const r of rowIds) {
    for (const c of colIds) {
      const key = cellKey(r, c);
      if (base.cells[key]) continue;
      const add = Buffer.byteLength(`,"${key}":{"value":"${text}","kind":"fixed"}`, "utf8");
      if (bytes + add > cap) break outer;
      bytes += add;
      count++;
    }
  }
  void rnd;
  return { count, baseBytes, bytes, ofCells: rows * columns };
}

// ----- the geometry the prototypes share ------------------------------------------------------------------------
// The visible grid: header band (column labels), the header rows, then body rows. 1-D templates show `minBlankRows`
// blank input rows so the author sees what the filler will get.
export function layoutOf(doc, minBlankRows = 5) {
  const rows = [];
  rows.push({ kind: "labels", id: null });
  for (const h of doc.headerRows ?? []) rows.push({ kind: "header", id: h.id, height: h.height });
  for (const r of doc.rows) rows.push({ kind: "body", id: r.id, height: r.height, label: r.label });
  if (doc.dimension === 1) for (let i = 0; i < minBlankRows; i++) rows.push({ kind: "blank", id: `blank${i}` });
  return { rows, columns: doc.columns };
}

// Merge lookup: for each cell the top-left anchor and span.
export function mergeIndex(doc) {
  const colPos = new Map(doc.columns.map((c, i) => [c.id, i]));
  const rowOrder = [...(doc.headerRows ?? []), ...doc.rows];
  const rowPos = new Map(rowOrder.map((r, i) => [r.id, i]));
  const anchors = new Map();
  const covered = new Set();
  for (const m of doc.merges ?? []) {
    const [fr, fc] = m.from.split(":");
    const [tr, tc] = m.to.split(":");
    const r0 = rowPos.get(fr), r1 = rowPos.get(tr), c0 = colPos.get(fc), c1 = colPos.get(tc);
    if ([r0, r1, c0, c1].some((v) => v === undefined)) continue;
    anchors.set(m.from, { rowSpan: r1 - r0 + 1, colSpan: c1 - c0 + 1 });
    for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) {
      if (r !== r0 || c !== c0) covered.add(cellKey(rowOrder[r].id, doc.columns[c].id));
    }
  }
  return { anchors, covered };
}
