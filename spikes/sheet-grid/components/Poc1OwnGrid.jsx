"use client";
// POC 1, WP3 (throwaway): the own table grid, extended. It draws a real workbook (fixture from the Java spike) with CLOSED styles
// (font family, size, bold, italic, underline, text colour, fill, four borders, number format, alignment, wrap), a frozen header band,
// a conditional colour, and calculated columns with live recalculation. A formula bar edits cells; formulas are stored in the id-based
// form and drawn as Excel-style text. It keeps the roles of an accessible grid (one grid, rows, cells, one tab stop, arrow keys).
// Query: fixture=plant|spm  sheet=<index>
import { useEffect, useMemo, useRef, useState } from "react";
import { toIds, toExcel, parse, evaluate, show, FormulaRefused, XlError } from "../lib/poc1Formula.mjs";
import { familyOf, sizeOf, colourName, BORDER_CLASS, formatKind, format, meets } from "../lib/poc1Closed.mjs";

const letters = (i) => { let s = ""; for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s; return s; };

function classesOf(st, cf) {
  const c = ["pc-cell", `pc-fam-${familyOf(st.font?.name)}`, `pc-pt-${sizeOf(st.font?.size ?? 11)}`];
  if (st.font?.bold) c.push("pc-b");
  if (st.font?.italic) c.push("pc-i");
  if (st.font?.underline) c.push("pc-u");
  const text = colourName(st.font?.color); if (text && text !== "text") c.push(`pc-tx-${text}`);
  const fill = st.fill ? colourName(st.fill) : null; if (fill && fill !== "white") c.push(`pc-bg-${fill}`);
  for (const side of ["t", "r", "b", "l"]) { const b = st.border?.[side]; if (b) c.push(`pc-b${side}-${BORDER_CLASS[b.style] ?? "thin"}`); }
  c.push(`pc-h-${st.h === "CENTER" ? "c" : st.h === "RIGHT" ? "r" : st.h === "LEFT" ? "l" : "g"}`, `pc-v-${st.v === "TOP" ? "t" : st.v === "CENTER" ? "m" : "b"}`);
  if (st.wrap) c.push("pc-wrap");
  if (cf) c.push(`pc-cf-${cf.fill ?? "none"}`, `pc-cftx-${cf.color ?? "none"}`);
  return c.join(" ");
}

export default function Poc1OwnGrid() {
  const [fx, setFx] = useState(null);
  const [error, setError] = useState("");
  const [sel, setSel] = useState([0, 0]);
  const [edited, setEdited] = useState(0); // bumps on each change so everything recalculates
  const [bar, setBar] = useState("");
  const [message, setMessage] = useState("");
  const store = useRef(null);
  const params = useMemo(() => (typeof window === "undefined" ? {} : Object.fromEntries(new URLSearchParams(window.location.search))), []);

  useEffect(() => {
    (async () => {
      try {
        const neutral = await (await fetch(`/poc1-fixture/${params.fixture}.json`)).json();
        const sh = neutral.sheets[Number(params.sheet ?? 0)];
        const extra = 20;
        const layout = { colIds: Array.from({ length: sh.cols + 2 }, (_, i) => `c${i + 1}`), rowIds: Array.from({ length: sh.rows + extra }, (_, i) => `r${i + 1}`) };
        const values = new Map(); const formulas = new Map(); const cells = new Map();
        for (const c of sh.cells) {
          cells.set(`${c.r},${c.c}`, c);
          const k = `c${c.c + 1}|r${c.r + 1}`;
          if (c.t === "n" || c.t === "s" || c.t === "b") values.set(k, c.v);
          else if (c.t === "f") { try { formulas.set(k, toIds(c.f, c.r + 1, layout)); } catch { /* refused: keep the stored value */ values.set(k, c.v); } }
        }
        store.current = { sh, layout, values, formulas, cells };
        setFx({ ready: true });
        window.__own = { ready: true, readyAt: performance.now() };
      } catch (e) { setError(String(e?.message ?? e).slice(0, 80)); }
    })();
  }, [params]);

  const calc = useMemo(() => {
    if (!store.current) return null;
    const { layout, values, formulas } = store.current;
    const memo = new Map();
    const ctx = (rowId) => ({
      row: rowId,
      cell(c, r) { const k = `${c}|${r}`; if (formulas.has(k)) { if (!memo.has(k)) memo.set(k, evaluate(parse(formulas.get(k)), ctx(r))); return memo.get(k); } return values.get(k); },
      range(a, b) { const c0 = layout.colIds.indexOf(a.col), c1 = layout.colIds.indexOf(b.col), r0 = layout.rowIds.indexOf(a.row), r1 = layout.rowIds.indexOf(b.row); const out = []; for (let r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) for (let c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) out.push(this.cell(layout.colIds[c], layout.rowIds[r])); return out; },
    });
    const result = new Map();
    for (const k of formulas.keys()) { const [c, r] = k.split("|"); try { result.set(k, ctx(r).cell(c, r)); } catch (e) { result.set(k, new XlError(e instanceof FormulaRefused ? "#NAME?" : "#VALUE!")); } }
    return { result, get: (c, r) => (formulas.has(`${c}|${r}`) ? result.get(`${c}|${r}`) : values.get(`${c}|${r}`)) };
  }, [fx, edited]);

  const s = store.current;
  useEffect(() => {
    if (!s) return;
    const [r, c] = sel; const k = `c${c + 1}|r${r + 1}`;
    setBar(s.formulas.has(k) ? `=${toExcel(s.formulas.get(k), r + 1, s.layout)}` : s.values.has(k) ? String(s.values.get(k)) : "");
  }, [sel, edited, fx]);

  if (error) return <p role="alert">Failed: {error}</p>;
  if (!s || !calc) return <p>Loading…</p>;

  const { sh, layout } = s;
  const rowsShown = sh.rows + 8;
  const frozen = sh.freeze.rows;
  const widths = Array.from({ length: sh.cols }, (_, i) => sh.colWidths[i] ?? 64);
  const heights = (r) => sh.rowHeights[r] ?? 20;
  const mergeAt = new Map(); const covered = new Set();
  for (const m of sh.merges) { mergeAt.set(`${m.r0},${m.c0}`, m); for (let r = m.r0; r <= m.r1; r++) for (let c = m.c0; c <= m.c1; c++) if (r !== m.r0 || c !== m.c0) covered.add(`${r},${c}`); }
  let top = 0; const topOf = []; for (let r = 0; r < rowsShown; r++) { topOf.push(top); top += heights(r); }
  const left = [0]; widths.forEach((w, i) => left.push(left[i] + w));

  function commit() {
    const [r, c] = sel; const k = `c${c + 1}|r${r + 1}`;
    try {
      if (bar.startsWith("=")) { s.formulas.set(k, toIds(bar.slice(1), r + 1, layout)); parse(s.formulas.get(k)); s.values.delete(k); }
      else { s.formulas.delete(k); if (bar === "") s.values.delete(k); else s.values.set(k, bar !== "" && !Number.isNaN(Number(bar)) ? Number(bar) : bar); }
      setMessage(""); setEdited((n) => n + 1);
    } catch (e) { setMessage(`This formula is not accepted (${e instanceof FormulaRefused ? e.key : "error"}).`); }
  }
  function onKey(e) {
    const [r, c] = sel; const move = { ArrowDown: [1, 0], ArrowUp: [-1, 0], ArrowRight: [0, 1], ArrowLeft: [0, -1] }[e.key];
    if (move) { e.preventDefault(); setSel([Math.max(0, Math.min(rowsShown - 1, r + move[0])), Math.max(0, Math.min(sh.cols - 1, c + move[1]))]); }
    if (e.key === "F2") { e.preventDefault(); document.getElementById("pc-bar")?.focus(); }
  }

  return (
    <main className="pc-page">
      <div className="pc-barrow">
        <span className="pc-name">{letters(sel[1])}{sel[0] + 1}</span>
        <input id="pc-bar" aria-label="Formula bar" className="pc-bar" value={bar} onChange={(e) => setBar(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(); } }} />
        {message && <span role="alert" className="pc-msg">{message}</span>}
      </div>
      <div className="pc-scroll" role="grid" aria-label="Sheet" aria-rowcount={rowsShown} aria-colcount={sh.cols} tabIndex={0} onKeyDown={onKey}>
        <div className="pc-canvas" style={{ width: left[sh.cols], height: top }}>
          {Array.from({ length: rowsShown }, (_, r) => (
            <div key={r} role="row" aria-rowindex={r + 1} className={r < frozen ? "pc-row pc-frozen" : "pc-row"} style={{ top: topOf[r], height: heights(r), position: r < frozen ? "sticky" : "absolute", zIndex: (r < frozen ? 2000 : 1000) - r }}>
              {Array.from({ length: sh.cols }, (_, c) => {
                if (covered.has(`${r},${c}`)) return null;
                const cell = s.cells.get(`${r},${c}`);
                const st = cell ? sh.styles[cell.s] : {};
                const k = `c${c + 1}|r${r + 1}`;
                const raw = calc.get(`c${c + 1}`, `r${r + 1}`);
                const fmt = formatKind(st.numFmt);
                const m = mergeAt.get(`${r},${c}`);
                const rule = sh.cf.find((x) => x.ranges.some((g) => r >= g.r0 && r <= g.r1 && c >= g.c0 && c <= g.c1));
                const hit = rule && meets(rule.op, Number(rule.f1), typeof raw === "number" ? raw : NaN) ? { fill: rule.fill ? colourName(rule.fill) : null, color: rule.color ? colourName(rule.color) : null } : null;
                const w = m ? left[Math.min(sh.cols, m.c1 + 1)] - left[c] : widths[c];
                const h = m ? topOf[Math.min(rowsShown - 1, m.r1)] + heights(m.r1) - topOf[r] : heights(r);
                const selected = sel[0] === r && sel[1] === c;
                return (
                  <div key={c} role="gridcell" aria-colindex={c + 1} aria-selected={selected} tabIndex={-1} data-r={r} data-c={c}
                    className={classesOf(st, hit) + (selected ? " pc-selected" : "") + (st.h === "GENERAL" || !st.h ? (typeof raw === "number" ? " pc-h-r" : "") : "")}
                    style={{ left: left[c], width: w, height: h, zIndex: m ? 1 : 0 }} onClick={() => setSel([r, c])}>
                    {raw instanceof XlError ? raw.code : format(fmt, raw)}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </main>
  );
}
