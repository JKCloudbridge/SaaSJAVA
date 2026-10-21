"use client";
// Common harness: builds the canonical document from the address, publishes window.__spike for the browser script.
import { useEffect, useMemo, useRef } from "react";
import { makeTemplate } from "../lib/model.mjs";

export function paramsOf() {
  const q = new URLSearchParams(typeof window === "undefined" ? "" : window.location.search);
  return {
    rows: Number(q.get("rows") ?? 20),
    cols: Number(q.get("cols") ?? 8),
    dim: Number(q.get("dim") ?? 2),
    fixed: Number(q.get("fixed") ?? 0.05),
    mode: q.get("mode") ?? "author",
  };
}

export function useDoc() {
  return useMemo(() => {
    const p = paramsOf();
    return { p, doc: makeTemplate({ rows: p.rows, columns: p.cols, dimension: p.dim, fixedRatio: p.fixed }) };
  }, []);
}

// Marks the page ready after two animation frames, once `ready` is true.
export function useReady(ready, extra) {
  const done = useRef(false);
  useEffect(() => {
    if (!ready || done.current) return;
    done.current = true;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      window.__spike = { ...(window.__spike ?? {}), ready: true, readyAt: performance.now(), ...(extra ?? {}) };
    }));
  }, [ready, extra]);
}

export function Status({ children }) {
  return <p role="status" className="spike-status">{children}</p>;
}
