"use client";

import { useEffect, useState } from "react";

/** Below this width the grid is for reading, and the form view is the way to build (ADR-0172 section 3). */
export const NARROW_QUERY = "(max-width: 40rem)";

function matches(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(NARROW_QUERY).matches;
}

/**
 * Whether the screen is narrow. Known from the first render (the workspace is drawn only after its data has arrived, in
 * the browser), so the view that suits the screen is the first one shown. A browser without `matchMedia` (a test) is
 * not narrow.
 */
export function useNarrowScreen(): boolean {
  const [narrow, setNarrow] = useState(matches);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    const query = window.matchMedia(NARROW_QUERY);
    const update = () => setNarrow(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return narrow;
}
