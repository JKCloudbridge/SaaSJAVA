"use client";
import { useCallback, useMemo, useRef, useState } from "react";
import dynamic from "next/dynamic";
import "@fortune-sheet/react/dist/index.css";
import { useDoc, useReady } from "./Harness";
import { fixedPositions } from "../lib/gridOps.mjs";
import { toFortune, fromFortune } from "../adapters/fortune.mjs";

// Client only: the library touches window when it is imported.
const Workbook = dynamic(() => import("@fortune-sheet/react").then((m) => m.Workbook), { ssr: false });

export default function FortuneClient() {
  const { p, doc } = useDoc();
  // fill view: the library has a hook that can refuse an edit of one cell (row 0 of its sheet is the label row)
  const hooks = useMemo(() => {
    if (p.mode !== "fill") return undefined;
    const fixed = fixedPositions(doc);
    return { beforeUpdateCell: (r, c) => r !== 0 && !fixed.has(`${r - 1},${c}`) };
  }, [p, doc]);
  const data = useMemo(() => toFortune(doc), [doc]);
  const [ready, setReady] = useState(false);
  const ref = useRef(null);
  const attach = useCallback((api) => {
    ref.current = api;
    if (api) {
      window.__spike = { ...(window.__spike ?? {}), getDoc: () => fromFortune(ref.current.getAllSheets(), doc), api };
      setReady(true);
    }
  }, [doc]);
  useReady(ready);
  return (
    <main className="spike-page">
      <h1>Fortune-sheet</h1>
      <div style={{ height: "30rem" }}>
        <Workbook ref={attach} data={data} showToolbar showFormulaBar={false} showSheetTabs={false} lang="en" hooks={hooks ?? {}} />
      </div>
    </main>
  );
}
