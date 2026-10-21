"use client";
import { useEffect, useRef, useState } from "react";
import "jspreadsheet-ce/dist/jspreadsheet.css";
import "jsuites/dist/jsuites.css";
import { useDoc, useReady } from "./Harness";
import { fixedPositions } from "../lib/gridOps.mjs";
import { colName } from "../adapters/common.mjs";
import { toJspreadsheet, fromJspreadsheet } from "../adapters/jspreadsheet.mjs";

export default function JspreadsheetClient() {
  const { p, doc } = useDoc();
  const box = useRef(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let sheets;
    let cancelled = false;
    (async () => {
      try {
        const mod = await import("jspreadsheet-ce");
        const jspreadsheet = mod.default ?? mod;
        if (cancelled || !box.current) return;
        const a = toJspreadsheet(doc);
        sheets = jspreadsheet(box.current, {
          toolbar: true,
          worksheets: [{ data: a.data, columns: a.columns, mergeCells: a.mergeCells, style: a.style, tableOverflow: true, tableHeight: "28rem", tableWidth: "100%", lazyLoading: true, loadingSpin: false, allowComments: false }],
        });
        const ws0 = Array.isArray(sheets) ? sheets[0] : sheets.worksheets?.[0] ?? sheets;
        const ws = ws0;
        if (p.mode === "fill") for (const pos of fixedPositions(doc)) { const [r, c] = pos.split(",").map(Number); ws.setReadOnly(`${colName(c)}${r + 1}`, true); }
        window.__spike = { ...(window.__spike ?? {}), getDoc: () => fromJspreadsheet({ data: ws.getData(), mergeCells: ws.getMerge?.() ?? {}, style: ws.getStyle?.() ?? {} }, doc), ws };
        setReady(true);
      } catch (e) {
        setError(String(e?.name ?? "Error"));
        window.__spike = { ...(window.__spike ?? {}), failed: String(e?.message ?? e).slice(0, 300) };
      }
    })();
    return () => { cancelled = true; try { sheets && (Array.isArray(sheets) ? sheets[0] : sheets).destroy?.(); } catch { /* ignore */ } };
  }, [doc, p]);

  useReady(ready);
  return (
    <main className="spike-page">
      <h1>Jspreadsheet CE</h1>
      {error && <p role="alert">Failed to start: {error}</p>}
      <div ref={box} />
    </main>
  );
}
