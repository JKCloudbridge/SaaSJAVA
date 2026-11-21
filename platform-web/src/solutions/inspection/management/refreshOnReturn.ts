import { useEffect, useRef } from "react";

/**
 * Asks the page's numbers again when the person comes back to the tab (ADR-0266: the dashboard must always be right, with
 * no timer). The page also asks when it opens, which includes coming back from a fill-in, because Home is then drawn
 * again. A return less than {@code minGap} milliseconds after the last ask is ignored, so a quick switch of windows does
 * not make a burst of requests.
 */
export function useRefreshOnReturn(refresh: () => void, minGap = 5000): void {
  const latest = useRef(refresh);
  const last = useRef(0);
  useEffect(() => {
    latest.current = refresh;
  }, [refresh]);
  useEffect(() => {
    last.current = Date.now();
    const back = () => {
      if (document.visibilityState === "hidden") {
        return;
      }
      const now = Date.now();
      if (now - last.current >= minGap) {
        last.current = now;
        latest.current();
      }
    };
    document.addEventListener("visibilitychange", back);
    window.addEventListener("focus", back);
    return () => {
      document.removeEventListener("visibilitychange", back);
      window.removeEventListener("focus", back);
    };
  }, [minGap]);
}
