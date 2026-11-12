"use client";
// POC 2, WP3 and WP4 (throwaway): the own grid of POC 1 with (1) a TAB BAR for several sheets of one workbook (the hidden ones are skipped),
// (2) three LOOKS: a = POC 1's closed lists, b = the wider closed lists (16 font families, 72 colours), c = any colour as a validated
// #rrggbb value (handed to the page as a custom property, never as a style attribute written into the markup) with the font families of b,
// (3) the check boxes, Yes/No pairs and places of embedded files that the extras reader found, drawn where the real form has them.
// Query: fixture=plant|spm  tab=<index among the visible tabs>  look=a|b|c  extras=0 to leave the marks out
import { useEffect, useMemo, useRef, useState } from "react";
import { toIds, toExcel, parse, evaluate, FormulaRefused, XlError } from "../lib/poc2Formula.mjs";
import { colourName, BORDER_CLASS, formatKind, format, meets } from "../lib/poc1Closed.mjs";
import { HEX, rgbOf, nearest, PALETTE_72, fontKey, sizeKey, clamp, LIMITS_V2_DRAFT } from "../lib/poc2Look.mjs";

const letters = (i) => { let s = ""; for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s; return s; };
const luminance = (hex) => { const [r, g, b] = rgbOf(hex).map((v) => v / 255); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };

/** The classes, and for look c the two validated values. A value that is not #rrggbb is dropped here as well as on the server. */
function styleOf(st, cf, look) {
  const c = ["pc-cell", `pc2-ff-${fontKey(st.font?.name).key}`, `pc-pt-${sizeKey(st.font?.size ?? 11)}`];
  const vars = {};
  if (st.font?.bold) c.push("pc-b");
  if (st.font?.italic) c.push("pc-i");
  if (st.font?.underline) c.push("pc-u");
  const fill = cf?.fillHex ?? st.fill; const text = cf?.colorHex ?? st.font?.color;
  if (look === "a") {
    const t = text ? colourName(text) : null; if (t && t !== "text") c.push(`pc-tx-${t}`);
    const f = fill ? colourName(fill) : null; if (f && f !== "white") c.push(`pc-bg-${f}`);
  } else if (look === "b") {
    if (text) c.push(`pc2-b-tx-${nearest(PALETTE_72, text).name}`);
    if (fill) c.push(`pc2-b-bg-${nearest(PALETTE_72, fill).name}`);
  } else {
    if (fill && HEX.test(fill)) { c.push("pc2-bgv"); vars["--pc2-bg"] = fill; if (!text) c.push(luminance(fill) > 0.5 ? "pc2-ink-dark" : "pc2-ink-light"); }
    if (text && HEX.test(text)) { c.push("pc2-txv"); vars["--pc2-tx"] = text; }
  }
  for (const side of ["t", "r", "b", "l"]) { const b = st.border?.[side]; if (b) c.push(`pc-b${side}-${BORDER_CLASS[b.style] ?? "thin"}`); }
  c.push(`pc-h-${st.h === "CENTER" ? "c" : st.h === "RIGHT" ? "r" : st.h === "LEFT" ? "l" : "g"}`, `pc-v-${st.v === "TOP" ? "t" : st.v === "CENTER" ? "m" : "b"}`);
  if (st.wrap) c.push("pc-wrap");
  return { className: c.join(" "), vars };
}

export default function Poc2OwnGrid() {
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");
  const [tab, setTab] = useState(0);
  const [sel, setSel] = useState([0, 0]);
  const store = useRef(null);
  const params = useMemo(() => (typeof window === "undefined" ? {} : Object.fromEntries(new URLSearchParams(window.location.search))), []);
  const look = params.look === "a" || params.look === "b" ? params.look : "c";

  useEffect(() => {
    (async () => {
      try {
        const neutral = await (await fetch(`/poc1-fixture/${params.fixture}.json`)).json();
        let extras = null;
        if (params.extras !== "0") { try { const r = await fetch(`/poc1-fixture/${params.fixture}-extras.json`); if (r.ok) extras = await r.json(); } catch { /* no marks */ } }
        const visible = neutral.sheets.filter((s) => !s.hidden);
        store.current = { visible, extras, hidden: neutral.sheets.length - visible.length };
        setTab(Math.min(Number(params.tab ?? 0), visible.length - 1));
        setReady(true);
        window.__own2 = { ready: true, tabs: visible.length, hidden: neutral.sheets.length - visible.length };
      } catch (e) { setError(String(e?.message ?? e).slice(0, 80)); }
    })();
  }, [params]);

  const view = useMemo(() => {
    if (!ready) return null;
    const { visible } = store.current;
    const sh = visible[tab];
    const extra = 20;
    const layout = { colIds: Array.from({ length: sh.cols + 2 }, (_, i) => `c${i + 1}`), rowIds: Array.from({ length: sh.rows + extra }, (_, i) => `r${i + 1}`) };
    const values = new Map(); const formulas = new Map(); const cells = new Map();
    for (const c of sh.cells) {
      cells.set(`${c.r},${c.c}`, c);
      const k = `c${c.c + 1}|r${c.r + 1}`;
      if (c.t === "n" || c.t === "s" || c.t === "b") values.set(k, c.v);
      else if (c.t === "f") { try { formulas.set(k, toIds(c.f, c.r + 1, layout)); } catch { values.set(k, c.v); } }
    }
    const memo = new Map();
    const ctx = (rowId) => ({
      row: rowId,
      bodyRows: () => layout.rowIds,
      cell(c, r) { const k = `${c}|${r}`; if (formulas.has(k)) { if (!memo.has(k)) memo.set(k, evaluate(parse(formulas.get(k)), ctx(r))); return memo.get(k); } return values.get(k); },
      range(a, b) { const c0 = layout.colIds.indexOf(a.col), c1 = layout.colIds.indexOf(b.col), r0 = layout.rowIds.indexOf(a.row), r1 = layout.rowIds.indexOf(b.row); const out = []; for (let r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) for (let c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) out.push(this.cell(layout.colIds[c], layout.rowIds[r])); return out; },
    });
    const get = (c, r) => { const k = `${c}|${r}`; if (formulas.has(k)) { try { return ctx(r).cell(c, r); } catch (e) { return new XlError(e instanceof FormulaRefused ? "#NAME?" : "#VALUE!"); } } return values.get(k); };
    const marks = new Map(); // "r,c" -> { kind, ... }
    const ex = tab === 0 ? store.current.extras : null; // the proof's marks belong to the first sheet of the workbook that has them
    if (ex) {
      const pairCells = new Set(ex.pairs.map((p) => `${p.r},${p.c}`));
      for (const p of ex.pairs) marks.set(`${p.r},${p.c}`, { kind: "pair", yes: p.yes, no: p.no });
      for (const b of ex.boxes) if (!pairCells.has(`${b.r},${b.c}`)) marks.set(`${b.r},${b.c}`, { kind: "box", ticked: b.ticked });
      for (const e of ex.embedded) marks.set(`${e.r},${e.c}`, { ...(marks.get(`${e.r},${e.c}`) ?? {}), attach: true, kind: marks.get(`${e.r},${e.c}`)?.kind ?? "attach" });
    }
    return { sh, layout, cells, get, marks };
  }, [ready, tab]);

  if (error) return <p role="alert">Failed: {error}</p>;
  if (!view) return <p>Loading…</p>;
  const { sh, cells, get, marks } = view;
  const rowsShown = sh.rows + 4;
  const frozen = sh.freeze.rows;
  const widths = Array.from({ length: sh.cols }, (_, i) => clamp((sh.colWidths[i] ?? 69) - 5, LIMITS_V2_DRAFT.colMin, LIMITS_V2_DRAFT.colMax));
  const heights = (r) => clamp(sh.rowHeights[r] ?? 20, LIMITS_V2_DRAFT.rowMin, LIMITS_V2_DRAFT.rowMax);
  const mergeAt = new Map(); const covered = new Set();
  for (const m of sh.merges) { mergeAt.set(`${m.r0},${m.c0}`, m); for (let r = m.r0; r <= m.r1; r++) for (let c = m.c0; c <= m.c1; c++) if (r !== m.r0 || c !== m.c0) covered.add(`${r},${c}`); }
  let top = 0; const topOf = []; for (let r = 0; r < rowsShown; r++) { topOf.push(top); top += heights(r); }
  const left = [0]; widths.forEach((w, i) => left.push(left[i] + w));
  const tabs = store.current.visible;

  function onKey(e) {
    const [r, c] = sel; const move = { ArrowDown: [1, 0], ArrowUp: [-1, 0], ArrowRight: [0, 1], ArrowLeft: [0, -1] }[e.key];
    if (move) { e.preventDefault(); setSel([Math.max(0, Math.min(rowsShown - 1, r + move[0])), Math.max(0, Math.min(sh.cols - 1, c + move[1]))]); }
  }

  return (
    <main className="pc-page" data-look={look}>
      <div className="pc2-tabs" role="tablist" aria-label="Sheets of the template">
        {tabs.map((s, i) => (
          <button key={i} type="button" role="tab" aria-selected={i === tab} className="pc2-tab" onClick={() => { setTab(i); setSel([0, 0]); }}>Tab {i + 1}</button>
        ))}
        {store.current.hidden > 0 && <span className="hint">{store.current.hidden} hidden tabs skipped</span>}
      </div>
      <div className="pc-scroll" role="grid" aria-label="Sheet" aria-rowcount={rowsShown} aria-colcount={sh.cols} tabIndex={0} onKeyDown={onKey}>
        <div className="pc-canvas" style={{ width: left[sh.cols], height: top }}>
          {Array.from({ length: rowsShown }, (_, r) => (
            <div key={r} role="row" aria-rowindex={r + 1} className={r < frozen ? "pc-row pc-frozen" : "pc-row"} style={{ top: topOf[r], height: heights(r), position: r < frozen ? "sticky" : "absolute", zIndex: (r < frozen ? 2000 : 1000) - r }}>
              {Array.from({ length: sh.cols }, (_, c) => {
                if (covered.has(`${r},${c}`)) return null;
                const cell = cells.get(`${r},${c}`);
                const st = cell ? sh.styles[cell.s] : {};
                const raw = get(`c${c + 1}`, `r${r + 1}`);
                const fmt = formatKind(st.numFmt);
                const m = mergeAt.get(`${r},${c}`);
                const rule = sh.cf.find((x) => x.ranges.some((g) => r >= g.r0 && r <= g.r1 && c >= g.c0 && c <= g.c1));
                const hit = rule && meets(rule.op, Number(rule.f1), typeof raw === "number" ? raw : NaN) ? { fillHex: rule.fill, colorHex: rule.color } : null;
                const w = m ? left[Math.min(sh.cols, m.c1 + 1)] - left[c] : widths[c];
                const h = m ? topOf[Math.min(rowsShown - 1, m.r1)] + heights(m.r1) - topOf[r] : heights(r);
                const { className, vars } = styleOf(st, hit, look);
                const mark = marks.get(`${r},${c}`);
                const selected = sel[0] === r && sel[1] === c;
                let content = raw instanceof XlError ? raw.code : format(fmt, raw);
                if (mark?.kind === "pair") content = `${mark.yes ? "☑" : "☐"} Yes   ${mark.no ? "☑" : "☐"} No`;
                else if (mark?.kind === "box") content = mark.ticked ? "☑" : "☐";
                return (
                  <div key={c} role="gridcell" aria-colindex={c + 1} aria-selected={selected} tabIndex={-1} data-r={r} data-c={c}
                    className={className + (mark ? " pc2-box" : "") + (mark?.attach ? " pc2-attach" : "") + (selected ? " pc-selected" : "") + (st.h === "GENERAL" || !st.h ? (typeof raw === "number" ? " pc-h-r" : "") : "")}
                    style={{ left: left[c], width: w, height: h, zIndex: m ? 1 : 0, ...vars }} onClick={() => setSel([r, c])}>
                    {content}
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
