// POC 1 (throwaway): neutral workbook description (written by the Java spike from POI's reading) -> Univer workbook snapshot.
// Nothing here is the product adapter; it measures what Univer can show of a real workbook.

const BORDER = { THIN: 1, HAIR: 2, DOTTED: 3, DASHED: 4, DASH_DOT: 5, DASH_DOT_DOT: 6, DOUBLE: 7, MEDIUM: 8, MEDIUM_DASHED: 9,
  MEDIUM_DASH_DOT: 10, MEDIUM_DASH_DOT_DOT: 11, SLANTED_DASH_DOT: 12, THICK: 13 };
const HALIGN = { LEFT: 1, CENTER: 2, RIGHT: 3, JUSTIFY: 4 };
const VALIGN = { TOP: 1, CENTER: 2, BOTTOM: 3 };

export function styleOf(st) {
  const s = {};
  if (st.font) {
    s.ff = st.font.name; s.fs = st.font.size;
    if (st.font.bold) s.bl = 1;
    if (st.font.italic) s.it = 1;
    if (st.font.underline) s.ul = { s: 1 };
    if (st.font.color) s.cl = { rgb: st.font.color };
  }
  if (st.fill) s.bg = { rgb: st.fill };
  const bd = {};
  for (const side of ["t", "r", "b", "l"]) {
    const b = st.border?.[side];
    if (b) bd[side] = { s: BORDER[b.style] ?? 1, cl: { rgb: b.color ?? "#000000" } };
  }
  if (Object.keys(bd).length) s.bd = bd;
  if (st.numFmt && st.numFmt !== "General") s.n = { pattern: st.numFmt };
  if (HALIGN[st.h]) s.ht = HALIGN[st.h];
  if (VALIGN[st.v]) s.vt = VALIGN[st.v];
  if (st.wrap) s.tb = 3;
  return s;
}

/** @param extraRows blank rows appended (room for the filler's new rows) */
export function toUniverSheet(neutral, sheetIndex, { extraRows = 20, id = "wb1", name = "POC" } = {}) {
  const sh = neutral.sheets[sheetIndex];
  const styles = {};
  sh.styles.forEach((st, i) => { styles[`s${i}`] = styleOf(st); });
  const cellData = {};
  for (const c of sh.cells) {
    if (c.t === "z" && !sh.styles[c.s]?.fill && !Object.keys(sh.styles[c.s]?.border ?? {}).length) continue;
    const cell = { s: `s${c.s}` };
    if (c.t === "s") { cell.v = c.v; cell.t = 1; }
    else if (c.t === "n") { cell.v = c.v; cell.t = 2; }
    else if (c.t === "b") { cell.v = c.v ? 1 : 0; cell.t = 3; }
    else if (c.t === "f") {
      cell.f = `=${c.f}`;
      if (c.vt === "n") { cell.v = c.v; cell.t = 2; } else if (c.vt === "s") { cell.v = c.v; cell.t = 1; }
    }
    (cellData[c.r] ??= {})[c.c] = cell;
  }
  const columnData = {};
  sh.colWidths.forEach((w, i) => { columnData[i] = { w }; });
  const rowData = {};
  for (const [r, h] of Object.entries(sh.rowHeights)) rowData[r] = { h };
  const sheet = {
    id: "sheet1", name, rowCount: sh.rows + extraRows, columnCount: sh.cols + 2, cellData, columnData, rowData,
    mergeData: sh.merges.map((m) => ({ startRow: m.r0, startColumn: m.c0, endRow: m.r1, endColumn: m.c1 })),
    defaultColumnWidth: 64, defaultRowHeight: 20,
  };
  if (sh.freeze.rows || sh.freeze.cols) {
    sheet.freeze = { xSplit: sh.freeze.cols, ySplit: sh.freeze.rows, startRow: sh.freeze.rows, startColumn: sh.freeze.cols };
  }
  return { id, name, appVersion: "1.4.0", locale: "enUS", styles, sheetOrder: ["sheet1"], sheets: { sheet1: sheet } };
}

/** A small synthetic sheet for the cell-class experiment: rows x cols of text, every cell has a value so changes are visible. */
export function syntheticSheet(rows, cols) {
  const cellData = {};
  for (let r = 0; r < rows; r++) {
    cellData[r] = {};
    for (let c = 0; c < cols; c++) cellData[r][c] = { v: `v${r}_${c}`, t: 1 };
  }
  return {
    id: "wb1", name: "classes", appVersion: "1.4.0", locale: "enUS", styles: {}, sheetOrder: ["sheet1"],
    sheets: { sheet1: { id: "sheet1", name: "classes", rowCount: rows + 10, columnCount: cols + 2, cellData, defaultColumnWidth: 80, defaultRowHeight: 24 } },
  };
}

/** Several worksheets in one Univer workbook (the WP7 probe): the visible ones of the list, hidden ones skipped. */
export function toUniverWorkbook(neutral, indices, { extraRows = 20 } = {}) {
  const wb = { id: "wb1", name: "POC", appVersion: "1.4.0", locale: "enUS", styles: {}, sheetOrder: [], sheets: {} };
  indices.filter((i) => !neutral.sheets[i].hidden).forEach((i, n) => {
    const one = toUniverSheet(neutral, i, { extraRows });
    const id = `sheet${n + 1}`;
    for (const [k, v] of Object.entries(one.styles)) wb.styles[`${id}_${k}`] = v;
    const sh = one.sheets.sheet1;
    for (const row of Object.values(sh.cellData)) for (const cell of Object.values(row)) if (cell.s) cell.s = `${id}_${cell.s}`;
    sh.id = id; sh.name = `Sheet ${n + 1}`;
    wb.sheets[id] = sh; wb.sheetOrder.push(id);
  });
  return wb;
}
