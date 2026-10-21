import { flatten, rebuild } from "./common.mjs";

// Row 0 of the library sheet holds the column labels (the library cannot name its column headers).
export function toFortune(doc) {
  const { rows, cells, merges } = flatten(doc);
  const celldata = doc.columns.map((c, j) => ({ r: 0, c: j, v: { v: c.label, m: c.label, bl: 1, ct: { fa: "General", t: "s" } } }));
  for (const e of cells) {
    celldata.push({ r: e.r + 1, c: e.c, v: { v: e.value ?? "", m: e.value ?? "", bl: e.bold ? 1 : 0, it: e.italic ? 1 : 0, ct: { fa: "General", t: "s" } } });
  }
  const merge = {};
  for (const m of merges) merge[`${m.r0 + 1}_${m.c0}`] = { r: m.r0 + 1, c: m.c0, rs: m.r1 - m.r0 + 1, cs: m.c1 - m.c0 + 1 };
  const columnlen = Object.fromEntries(doc.columns.map((c, j) => [j, c.width ?? 120]));
  return [{ name: "Template", order: 0, status: 1, row: rows.length + 1, column: doc.columns.length, celldata, config: { merge, columnlen } }];
}

export function fromFortune(sheets, doc) {
  const sheet = sheets[0];
  const entries = [];
  const read = (r, c, cell) => {
    if (!cell || r === 0) return;
    const value = cell.v == null ? null : String(cell.v);
    entries.push({ r: r - 1, c, value, bold: cell.bl === 1, italic: cell.it === 1 });
  };
  if (sheet.data) sheet.data.forEach((row, r) => row?.forEach((cell, c) => read(r, c, cell)));
  else (sheet.celldata ?? []).forEach((x) => read(x.r, x.c, x.v));
  const merges = Object.values(sheet.config?.merge ?? {}).filter((m) => m.r > 0).map((m) => ({ r0: m.r - 1, c0: m.c, r1: m.r - 1 + m.rs - 1, c1: m.c + m.cs - 1 }));
  return rebuild(doc, entries, merges);
}
