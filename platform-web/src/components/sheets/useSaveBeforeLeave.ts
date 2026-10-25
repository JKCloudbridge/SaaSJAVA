"use client";

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";

/** Delay same-tab link navigation until the draft save succeeds. Browser closing keeps its unsaved warning. */
export function useSaveBeforeLeave(dirty: boolean, save: () => Promise<boolean>) {
  const router = useRouter();
  const current = useRef({ dirty, save });
  useEffect(() => { current.current = { dirty, save }; });
  useEffect(() => {
    const here = { href: window.location.href, state: window.history.state };
    const clicked = (event: MouseEvent) => {
      if (!current.current.dirty || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = (event.target as HTMLElement).closest<HTMLAnchorElement>("a[href]");
      if (!link || link.target && link.target !== "_self" || link.hasAttribute("download")) return;
      const target = new URL(link.href, window.location.href);
      if (target.origin !== window.location.origin || target.href === window.location.href) return;
      event.preventDefault();
      event.stopPropagation();
      void current.current.save().then((ok) => { if (ok) router.push(target.pathname + target.search + target.hash); });
    };
    document.addEventListener("click", clicked, true);
    const traversed = (event: PopStateEvent) => {
      if (!current.current.dirty) return;
      // Restore this editor before the router can unmount it. Resume the chosen destination only after saving.
      const target = window.location.href;
      event.stopImmediatePropagation();
      window.history.pushState(here.state, "", here.href);
      void current.current.save().then((ok) => {
        if (ok) {
          const destination = new URL(target);
          router.replace(destination.pathname + destination.search + destination.hash);
        }
      });
    };
    window.addEventListener("popstate", traversed, true);
    return () => {
      document.removeEventListener("click", clicked, true);
      window.removeEventListener("popstate", traversed, true);
    };
  }, [router]);
}
