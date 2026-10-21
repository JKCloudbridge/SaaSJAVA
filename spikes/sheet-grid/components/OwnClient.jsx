"use client";
import { useState } from "react";
import dynamic from "next/dynamic";
import { useDoc, useReady } from "./Harness";

// Client only: the grid sets positions with the style object after mount, never in server markup, so that a policy
// without inline styles does not block it.
const OwnGrid = dynamic(() => import("./OwnGrid"), { ssr: false });

export default function OwnClient() {
  const { p, doc } = useDoc();
  const [mode, setMode] = useState(p.mode);
  const [gridReady, setGridReady] = useState(false);
  useReady(gridReady);
  return (
    <main className="spike-page">
      <h1>Own grid</h1>
      <div className="spike-modes" role="group" aria-label="View">
        <button type="button" aria-pressed={mode === "author"} onClick={() => setMode("author")}>Author view</button>
        <button type="button" aria-pressed={mode === "fill"} onClick={() => setMode("fill")}>Fill view</button>
      </div>
      <OwnGrid key={mode} doc={doc} mode={mode} onMounted={() => setGridReady(true)} />
    </main>
  );
}
