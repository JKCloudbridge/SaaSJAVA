"use client";
// Our own table-based grid (FA0 candidate "own"). It reads and writes the canonical document directly, draws only the
// rows and columns on the screen (virtual rendering) and keeps the ARIA grid pattern: grid, row, columnheader and
// gridcell, one tab stop, aria-activedescendant for the active cell. No library format exists here.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { cellKey } from "../lib/model.mjs";
import {
  displayRows, indexOf, mergeRects, applyPatch, editable, typeInto, toggleStyle, mergeRect, unmergeAt, parseClipboard, pasteBlock,
} from "../lib/gridOps.mjs";

const LABEL_H = 28;
const HISTORY_MAX = 200;

const shown = (v) => (v === true ? "Yes" : v === false ? "No" : v == null ? "" : String(v));
const norm = (s) => ({ r0: Math.min(s.ar, s.fr), r1: Math.max(s.ar, s.fr), c0: Math.min(s.ac, s.fc), c1: Math.max(s.ac, s.fc) });

export default function OwnGrid({ doc: initial, mode = "author", onChange, onMounted, label = "Template grid" }) {
  const [doc, setDoc] = useState(initial);
  const [undo, setUndo] = useState([]);
  const [redo, setRedo] = useState([]);
  const [sel, setSel] = useState({ ar: 0, ac: 0, fr: 0, fc: 0 });
  const [editing, setEditing] = useState(null);
  const [message, setMessage] = useState({ text: "", error: false });
  const scrollRef = useRef(null);
  const inputRef = useRef(null);
  const dragging = useRef(false);

  const rows = useMemo(() => displayRows(doc), [doc.rows, doc.headerRows, doc.dimension]); // eslint-disable-line react-hooks/exhaustive-deps
  const { rowPos, colPos } = useMemo(() => indexOf(doc, rows), [doc.columns, rows]); // eslint-disable-line react-hooks/exhaustive-deps
  const rects = useMemo(() => mergeRects(doc, rowPos, colPos), [doc.merges, rowPos, colPos]); // eslint-disable-line react-hooks/exhaustive-deps
  const { anchors, covered } = useMemo(() => {
    const anchors = new Map(), covered = new Set();
    for (const m of rects) {
      anchors.set(`${m.r0}:${m.c0}`, m);
      for (let r = m.r0; r <= m.r1; r++) for (let c = m.c0; c <= m.c1; c++) if (r !== m.r0 || c !== m.c0) covered.add(`${r}:${c}`);
    }
    return { anchors, covered };
  }, [rects]);
  const rowTop = useMemo(() => { let y = 0; return [...rows.map((r) => { const t = y; y += r.height; return t; }), y]; }, [rows]);
  const colLeft = useMemo(() => { let x = 0; return [...doc.columns.map((c) => { const l = x; x += c.width ?? 120; return l; }), x]; }, [doc.columns]);
  const totalH = rowTop[rows.length], totalW = colLeft[doc.columns.length];

  const rv = useVirtualizer({ count: rows.length, getScrollElement: () => scrollRef.current, estimateSize: (i) => rows[i].height, overscan: 6, scrollPaddingStart: LABEL_H });
  const cv = useVirtualizer({ horizontal: true, count: doc.columns.length, getScrollElement: () => scrollRef.current, estimateSize: (j) => doc.columns[j].width ?? 120, overscan: 2 });

  const vr = rv.getVirtualItems().map((v) => v.index);
  const vc = cv.getVirtualItems().map((v) => v.index);
  const rSet = new Set(vr), cSet = new Set(vc);
  if (vr.length && vc.length) {
    const [rMin, rMax, cMin, cMax] = [vr[0], vr[vr.length - 1], vc[0], vc[vc.length - 1]];
    for (const m of rects) if (m.r0 <= rMax && m.r1 >= rMin && m.c0 <= cMax && m.c1 >= cMin) { rSet.add(m.r0); cSet.add(m.c0); }
  }
  rSet.add(sel.fr); cSet.add(sel.fc);
  const visRows = [...rSet].filter((i) => i < rows.length).sort((a, b) => a - b);
  const visCols = [...cSet].filter((j) => j < doc.columns.length).sort((a, b) => a - b);

  useEffect(() => { onMounted?.(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const say = useCallback((text, error = false) => setMessage({ text, error }), []);
  const commit = useCallback((patch) => {
    setDoc((d) => {
      const { doc: next, inverse } = applyPatch(d, patch);
      setUndo((u) => [...u.slice(-(HISTORY_MAX - 1)), inverse]);
      setRedo([]);
      onChange?.(next);
      return next;
    });
  }, [onChange]);
  const doUndo = () => setUndo((u) => {
    if (!u.length) return u;
    const inverse = u[u.length - 1];
    setDoc((d) => { const r = applyPatch(d, inverse); setRedo((x) => [...x, r.inverse]); onChange?.(r.doc); return r.doc; });
    return u.slice(0, -1);
  });
  const doRedo = () => setRedo((x) => {
    if (!x.length) return x;
    const patch = x[x.length - 1];
    setDoc((d) => { const r = applyPatch(d, patch); setUndo((u) => [...u, r.inverse]); onChange?.(r.doc); return r.doc; });
    return x.slice(0, -1);
  });

  const keyOf = (r, c) => cellKey(rows[r].id, doc.columns[c].id);
  const anchorOf = (r, c) => {
    if (!covered.has(`${r}:${c}`)) return [r, c];
    const m = rects.find((x) => x.r0 <= r && x.r1 >= r && x.c0 <= c && x.c1 >= c);
    return [m.r0, m.c0];
  };
  const isEditable = (r, c) => editable(doc, rows[r], doc.columns[c], doc.cells[keyOf(r, c)], mode);

  const moveTo = (r, c, extend) => {
    r = Math.max(0, Math.min(rows.length - 1, r)); c = Math.max(0, Math.min(doc.columns.length - 1, c));
    [r, c] = anchorOf(r, c);
    setSel((s) => (extend ? { ...s, fr: r, fc: c } : { ar: r, ac: c, fr: r, fc: c }));
    rv.scrollToIndex(r); cv.scrollToIndex(c);
  };

  const startEdit = (r, c, seed) => {
    if (!isEditable(r, c)) { say(mode === "fill" ? "This cell is fixed text and cannot be typed into." : "This cell cannot be edited."); return; }
    const col = doc.columns[c];
    if (rows[r].zone !== "header" && mode === "fill" && col.type === "boolean" && seed === undefined) {
      const cur = doc.cells[keyOf(r, c)]?.value;
      const res = typeInto(doc, rows[r], col, cur === true ? "no" : "yes", mode);
      if (res.patch) commit(res.patch);
      return;
    }
    const current = doc.cells[keyOf(r, c)]?.value;
    setEditing({ r, c, initial: seed !== undefined ? seed : shown(current) });
  };
  useEffect(() => { if (editing && inputRef.current) { inputRef.current.focus(); if (editing.initial === "") inputRef.current.select?.(); } }, [editing]);

  const finishEdit = (save, dr = 0, dc = 0) => {
    const e = editing;
    if (!e) return;
    if (save) {
      const text = inputRef.current?.value ?? "";
      const res = typeInto(doc, rows[e.r], doc.columns[e.c], text, mode);
      if (res.error) { say(res.error, true); return; }
      commit(res.patch);
      say("");
    }
    setEditing(null);
    scrollRef.current?.focus();
    if (dr || dc) moveTo(e.r + dr, e.c + dc, false);
  };

  const onKeyDown = (e) => {
    if (e.target !== scrollRef.current) return;
    const ext = e.shiftKey;
    const cur = sel.fr === undefined ? sel : sel;
    const mod = e.ctrlKey || e.metaKey;
    const a = anchors.get(`${cur.fr}:${cur.fc}`);
    switch (e.key) {
      case "ArrowDown": e.preventDefault(); return moveTo(cur.fr + (a ? a.r1 - a.r0 + 1 : 1), cur.fc, ext);
      case "ArrowUp": e.preventDefault(); return moveTo(cur.fr - 1, cur.fc, ext);
      case "ArrowRight": e.preventDefault(); return moveTo(cur.fr, cur.fc + (a ? a.c1 - a.c0 + 1 : 1), ext);
      case "ArrowLeft": e.preventDefault(); return moveTo(cur.fr, cur.fc - 1, ext);
      case "Tab": e.preventDefault(); return moveTo(cur.fr, cur.fc + (e.shiftKey ? -1 : 1), false);
      case "Home": e.preventDefault(); return moveTo(mod ? 0 : cur.fr, 0, ext);
      case "End": e.preventDefault(); return moveTo(mod ? rows.length - 1 : cur.fr, doc.columns.length - 1, ext);
      case "PageDown": e.preventDefault(); return moveTo(cur.fr + 10, cur.fc, ext);
      case "PageUp": e.preventDefault(); return moveTo(cur.fr - 10, cur.fc, ext);
      case "Enter": case "F2": e.preventDefault(); return startEdit(cur.fr, cur.fc);
      case " ": if (mode === "fill" && doc.columns[cur.fc].type === "boolean") { e.preventDefault(); return startEdit(cur.fr, cur.fc); } break;
      case "Delete": case "Backspace": {
        e.preventDefault();
        const r = norm(sel), cells = {};
        for (let i = r.r0; i <= r.r1; i++) for (let j = r.c0; j <= r.c1; j++) {
          if (!isEditable(i, j) || covered.has(`${i}:${j}`)) continue;
          const res = typeInto(doc, rows[i], doc.columns[j], "", mode);
          Object.assign(cells, res.patch?.cells ?? {});
        }
        if (Object.keys(cells).length) commit({ cells });
        return;
      }
      default:
    }
    if (mod && (e.key === "z" || e.key === "Z")) { e.preventDefault(); return e.shiftKey ? doRedo() : doUndo(); }
    if (mod && (e.key === "y" || e.key === "Y")) { e.preventDefault(); return doRedo(); }
    if (mod && mode === "author" && (e.key === "b" || e.key === "B")) { e.preventDefault(); return style("bold"); }
    if (mod && mode === "author" && (e.key === "i" || e.key === "I")) { e.preventDefault(); return style("italic"); }
    if (!mod && !e.altKey && e.key.length === 1) { e.preventDefault(); startEdit(cur.fr, cur.fc, e.key); }
  };

  const style = (prop) => {
    const r = norm(sel), keys = [];
    for (let i = r.r0; i <= r.r1; i++) for (let j = r.c0; j <= r.c1; j++) if (!covered.has(`${i}:${j}`)) keys.push(keyOf(i, j));
    commit(toggleStyle(doc, keys, prop).patch);
  };
  const merge = () => {
    const res = mergeRect(doc, rows, rowPos, colPos, norm(sel));
    if (res.error) return say(res.error, true);
    commit(res.patch); say("Merged.");
  };
  const unmerge = () => {
    const res = unmergeAt(doc, rowPos, colPos, sel.fr, sel.fc);
    if (res.error) return say(res.error, true);
    commit(res.patch); say("Unmerged.");
  };

  const onPaste = (e) => {
    if (e.target !== scrollRef.current) return;
    e.preventDefault();
    const text = e.clipboardData.getData("text/plain");
    const r = norm(sel);
    const res = pasteBlock(doc, rows, r.r0, r.c0, parseClipboard(text), mode, covered);
    const keys = new Set([...covered].map((p) => { const [i, j] = p.split(":").map(Number); return keyOf(i, j); }));
    void keys;
    if (Object.keys(res.patch.cells).length) commit(res.patch);
    say(`Pasted ${res.applied} cell${res.applied === 1 ? "" : "s"}${res.skipped ? `, ${res.skipped} skipped` : ""}${res.errors.length ? `. ${res.errors.join(" ")}` : "."}`, res.errors.length > 0);
  };
  const onCopy = (e) => {
    if (e.target !== scrollRef.current) return;
    e.preventDefault();
    const r = norm(sel), lines = [];
    for (let i = r.r0; i <= r.r1; i++) {
      const line = [];
      for (let j = r.c0; j <= r.c1; j++) line.push(shown(doc.cells[keyOf(i, j)]?.value));
      lines.push(line.join("\t"));
    }
    e.clipboardData.setData("text/plain", lines.join("\n"));
  };

  const onMouseDown = (e) => {
    const el = e.target.closest?.("[data-r]");
    if (!el || editing) return;
    const r = Number(el.dataset.r), c = Number(el.dataset.c);
    dragging.current = true;
    if (e.shiftKey) setSel((s) => ({ ...s, fr: r, fc: c })); else setSel({ ar: r, ac: c, fr: r, fc: c });
    scrollRef.current?.focus();
    e.preventDefault();
  };
  const onMouseOver = (e) => {
    if (!dragging.current) return;
    const el = e.target.closest?.("[data-r]");
    if (el) setSel((s) => ({ ...s, fr: Number(el.dataset.r), fc: Number(el.dataset.c) }));
  };
  useEffect(() => { const up = () => { dragging.current = false; }; window.addEventListener("mouseup", up); return () => window.removeEventListener("mouseup", up); }, []);

  useEffect(() => { window.__spike = { ...(window.__spike ?? {}), getDoc: () => doc, getSel: () => sel, rowsShown: visRows.length, colsShown: visCols.length, cellsInDom: () => scrollRef.current?.querySelectorAll("[role=gridcell]").length }; });

  const r = norm(sel);
  const editingCell = editing && (() => {
    const col = doc.columns[editing.c];
    const common = { ref: inputRef, "aria-label": `${col.label}, row ${editing.r + 1}`, className: "og-input", defaultValue: editing.initial,
      onKeyDown: (e) => {
        if (e.key === "Enter") { e.preventDefault(); finishEdit(true, e.shiftKey ? -1 : 1, 0); }
        else if (e.key === "Tab") { e.preventDefault(); finishEdit(true, 0, e.shiftKey ? -1 : 1); }
        else if (e.key === "Escape") { e.preventDefault(); finishEdit(false); }
      },
      onBlur: (e) => { if (e.relatedTarget === scrollRef.current) return; if (editing) finishEdit(true); } };
    if (mode === "fill" && col.type === "picklist" && rows[editing.r].zone !== "header") {
      return <select {...common}><option value=""></option>{col.picklist.values.map((v) => <option key={v} value={v}>{v}</option>)}</select>;
    }
    return <input type="text" autoComplete="off" spellCheck={false} {...common} />;
  })();

  return (
    <div className="og">
      {mode === "author" && (
        <div className="og-toolbar" role="toolbar" aria-label="Formatting">
          <button type="button" onClick={() => style("bold")} aria-label="Bold"><b>B</b></button>
          <button type="button" onClick={() => style("italic")} aria-label="Italic"><i>I</i></button>
          <button type="button" onClick={merge}>Merge</button>
          <button type="button" onClick={unmerge}>Unmerge</button>
          <button type="button" onClick={doUndo} disabled={!undo.length}>Undo</button>
          <button type="button" onClick={doRedo} disabled={!redo.length}>Redo</button>
        </div>
      )}
      <div
        ref={scrollRef}
        className="og-scroll"
        role="grid"
        tabIndex={0}
        aria-label={label}
        aria-rowcount={rows.length + 1}
        aria-colcount={doc.columns.length}
        aria-multiselectable="true"
        aria-activedescendant={`og-${sel.fr}-${sel.fc}`}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
        onCopy={onCopy}
        onMouseDown={onMouseDown}
        onMouseOver={onMouseOver}
        onDoubleClick={(e) => { const el = e.target.closest?.("[data-r]"); if (el) startEdit(Number(el.dataset.r), Number(el.dataset.c)); }}
      >
        <div style={{ width: totalW, height: LABEL_H + totalH, position: "relative" }}>
          <div role="row" aria-rowindex={1} className="og-labels" style={{ height: LABEL_H, width: totalW }}>
            {visCols.map((j) => (
              <div key={doc.columns[j].id} role="columnheader" aria-colindex={j + 1} className="og-label" style={{ left: colLeft[j], width: doc.columns[j].width ?? 120, height: LABEL_H }}>
                {doc.columns[j].label}{doc.columns[j].required ? " *" : ""}
              </div>
            ))}
          </div>
          {visRows.map((i) => (
            <div key={rows[i].id} role="row" aria-rowindex={i + 2} className="og-row" style={{ top: LABEL_H + rowTop[i], height: rows[i].height, width: totalW }}>
              {visCols.map((j) => {
                if (covered.has(`${i}:${j}`)) return null;
                const key = keyOf(i, j);
                const cell = doc.cells[key];
                const m = anchors.get(`${i}:${j}`);
                const w = (m ? colLeft[m.c1 + 1] : colLeft[j + 1]) - colLeft[j];
                const h = (m ? rowTop[m.r1 + 1] : rowTop[i + 1]) - rowTop[i];
                const fixed = rows[i].zone === "header" || (cell && (cell.kind ?? (cell.value != null ? "fixed" : "input")) === "fixed");
                const selected = i >= r.r0 && i <= r.r1 && j >= r.c0 && j <= r.c1;
                const active = i === sel.fr && j === sel.fc;
                const cls = ["og-cell", fixed ? "og-fixed" : "og-input-cell", rows[i].zone === "header" ? "og-header" : "", cell?.style?.bold ? "og-bold" : "", cell?.style?.italic ? "og-italic" : "", selected ? "og-selected" : "", active ? "og-active" : "", m ? "og-merged" : ""].join(" ");
                const ed = editing && editing.r === i && editing.c === j;
                return (
                  <div key={j} id={`og-${i}-${j}`} role="gridcell" data-r={i} data-c={j} className={cls} aria-colindex={j + 1}
                    aria-selected={selected} aria-readonly={!isEditable(i, j)} aria-rowspan={m ? m.r1 - m.r0 + 1 : undefined} aria-colspan={m ? m.c1 - m.c0 + 1 : undefined}
                    style={{ left: colLeft[j], width: w, height: h }}>
                    {ed ? editingCell : shown(cell?.value)}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>
      <p role={message.error ? "alert" : "status"} className={message.error ? "og-message og-error" : "og-message"}>{message.text}</p>
    </div>
  );
}
