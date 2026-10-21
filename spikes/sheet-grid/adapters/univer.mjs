import { flatten, rebuild } from "./common.mjs";

export function toUniver(doc) {
  const { rows, cells, merges } = flatten(doc);
  const cellData = { 0: {} };
  doc.columns.forEach((c, j) => { cellData[0][j] = { v: c.label, s: { bl: 1 } }; });
  for (const e of cells) {
    (cellData[e.r + 1] ??= {})[e.c] = { v: e.value ?? "", s: { ...(e.bold ? { bl: 1 } : {}), ...(e.italic ? { it: 1 } : {}) } };
  }
  return {
    id: "wb1", name: "Template", appVersion: "0.0.1", locale: "enUS", styles: {}, sheetOrder: ["s1"],
    sheets: {
      s1: {
        id: "s1", name: "Template", rowCount: rows.length + 1, columnCount: doc.columns.length, cellData,
        mergeData: merges.map((m) => ({ startRow: m.r0 + 1, endRow: m.r1 + 1, startColumn: m.c0, endColumn: m.c1 })),
        columnData: Object.fromEntries(doc.columns.map((c, j) => [j, { w: c.width ?? 120 }])),
      },
    },
  };
}

export function fromUniver(snapshot, doc) {
  const sheet = snapshot.sheets[snapshot.sheetOrder[0]];
  const entries = [];
  for (const [r, row] of Object.entries(sheet.cellData ?? {})) {
    if (Number(r) === 0) continue;
    for (const [c, cell] of Object.entries(row)) {
      const s = typeof cell.s === "string" ? snapshot.styles?.[cell.s] : cell.s;
      entries.push({ r: Number(r) - 1, c: Number(c), value: cell.v == null ? null : String(cell.v), bold: s?.bl === 1, italic: s?.it === 1 });
    }
  }
  const merges = (sheet.mergeData ?? []).filter((m) => m.startRow > 0).map((m) => ({ r0: m.startRow - 1, c0: m.startColumn, r1: m.endRow - 1, c1: m.endColumn }));
  return rebuild(doc, entries, merges);
}
