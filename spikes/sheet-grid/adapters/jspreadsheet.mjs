import { flatten, rebuild, colName } from "./common.mjs";

// The library has its own column header, so the labels go there and the sheet starts with the header rows.
export function toJspreadsheet(doc) {
  const { rows, cells, merges } = flatten(doc);
  const data = rows.map(() => doc.columns.map(() => ""));
  const style = {};
  for (const e of cells) {
    data[e.r][e.c] = e.value ?? "";
    if (e.bold || e.italic) style[`${colName(e.c)}${e.r + 1}`] = `${e.bold ? "font-weight: bold;" : ""}${e.italic ? "font-style: italic;" : ""}`;
  }
  const mergeCells = {};
  for (const m of merges) mergeCells[`${colName(m.c0)}${m.r0 + 1}`] = [m.c1 - m.c0 + 1, m.r1 - m.r0 + 1];
  return { data, columns: doc.columns.map((c) => ({ type: "text", title: c.label, width: c.width ?? 120 })), mergeCells, style };
}

const colIndex = (name) => [...name].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1;
const parseName = (n) => {
  const m = /^([A-Z]+)(\d+)$/.exec(n);
  return { c: colIndex(m[1]), r: Number(m[2]) - 1 };
};

export function fromJspreadsheet(state, doc) {
  const entries = [];
  state.data.forEach((row, r) => row.forEach((v, c) => {
    const s = state.style?.[`${colName(c)}${r + 1}`] ?? "";
    entries.push({ r, c, value: v === "" ? null : String(v), bold: /font-weight:\s*bold/.test(s), italic: /font-style:\s*italic/.test(s) });
  }));
  const merges = Object.entries(state.mergeCells ?? {}).map(([name, [cs, rs]]) => {
    const p = parseName(name);
    return { r0: p.r, c0: p.c, r1: p.r + rs - 1, c1: p.c + cs - 1 };
  });
  return rebuild(doc, entries, merges);
}
