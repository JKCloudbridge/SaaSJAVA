"use client";
import { useEffect, useRef, useState } from "react";
import "@univerjs/preset-sheets-core/lib/index.css";
import { useDoc, useReady } from "./Harness";
import { fixedPositions } from "../lib/gridOps.mjs";
import { toUniver, fromUniver } from "../adapters/univer.mjs";

export default function UniverClient() {
  const { p, doc } = useDoc();
  const box = useRef(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let univer;
    let cancelled = false;
    (async () => {
      try {
        const [{ createUniver, LocaleType, mergeLocales }, { UniverSheetsCorePreset }, enUS] = await Promise.all([
          import("@univerjs/presets"),
          import("@univerjs/preset-sheets-core"),
          import("@univerjs/preset-sheets-core/locales/en-US"),
        ]);
        if (cancelled) return;
        const made = createUniver({
          locale: LocaleType.EN_US,
          locales: { [LocaleType.EN_US]: mergeLocales(enUS.default) },
          presets: [UniverSheetsCorePreset({ container: box.current })],
        });
        univer = made;
        made.univerAPI.createWorkbook(toUniver(doc));
        if (p.mode === "fill") {
          // fill view: the library can cancel the start of an edit, cell by cell (row 0 of its sheet is the label row)
          const fixed = fixedPositions(doc);
          made.univerAPI.addEvent(made.univerAPI.Event.BeforeSheetEditStart, (params) => { if (params.row === 0 || fixed.has(`${params.row - 1},${params.column}`)) params.cancel = true; });
        }
        window.__spike = { ...(window.__spike ?? {}), getDoc: () => fromUniver(made.univerAPI.getActiveWorkbook().save(), doc), univerReady: true, univerAPI: made.univerAPI };
        // wait until the library has put its canvas on the page
        const t0 = performance.now();
        while (!box.current?.querySelector("canvas") && performance.now() - t0 < 20000) await new Promise((r) => setTimeout(r, 50));
        if (!cancelled) setReady(true);
      } catch (e) {
        setError(String(e?.name ?? "Error"));
        window.__spike = { ...(window.__spike ?? {}), failed: String(e?.message ?? e).slice(0, 300) };
      }
    })();
    return () => { cancelled = true; try { univer?.univer?.dispose?.(); } catch { /* ignore */ } };
  }, [doc, p]);

  useReady(ready);
  return (
    <main className="spike-page">
      <h1>Univer</h1>
      {error && <p role="alert">Failed to start: {error}</p>}
      <div ref={box} style={{ height: "32rem", position: "relative" }} />
    </main>
  );
}
