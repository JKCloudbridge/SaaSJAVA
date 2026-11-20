import { useSyncExternalStore } from "react";

/**
 * Which layout of the Home page of Management Core is shown (ADR-0266). It is a presentation choice and nothing else: no
 * setting, no permission, nothing on the server. The address (`?view=1` or `?view=2`) wins; otherwise the last choice the
 * browser remembers; otherwise Option 1. Every read and write of the browser storage is in a try and catch, because storage
 * can be refused (a private window, blocked site data): the choice then lasts as long as the page does.
 */

export type HomeView = "1" | "2";

export const DEFAULT_VIEW: HomeView = "1";
const KEY = "management-home-view";

let remembered: HomeView | null = null;
const listeners = new Set<() => void>();

/** The view an address names, or undefined when it names none or something else. */
export function viewFromAddress(value: string | null | undefined): HomeView | undefined {
  return value === "1" || value === "2" ? value : undefined;
}

/** The view the browser remembers, or null. */
export function storedView(): HomeView | null {
  if (remembered) {
    return remembered;
  }
  try {
    const value = window.localStorage.getItem(KEY);
    return value === "1" || value === "2" ? value : null;
  } catch {
    return null;
  }
}

/** Remembers a choice, for this page at least and, where the browser allows, for the next visit. */
export function rememberView(view: HomeView): void {
  remembered = view;
  try {
    window.localStorage.setItem(KEY, view);
  } catch {
    // Storage is refused: the choice lasts as long as this page.
  }
  listeners.forEach((listener) => listener());
}

/** The view to draw: the address wins, then what the browser remembers, then Option 1. */
export function chooseView(address: HomeView | undefined, stored: HomeView | null): HomeView {
  return address ?? stored ?? DEFAULT_VIEW;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const changed = (event: StorageEvent) => {
    if (event.key === KEY) {
      remembered = null;
      listener();
    }
  };
  window.addEventListener("storage", changed);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", changed);
  };
}

/** What the browser remembers, as a value React can follow; null on the server and where nothing is remembered. */
export function useStoredView(): HomeView | null {
  return useSyncExternalStore(subscribe, storedView, () => null);
}

/** Forgets the in-memory choice (for tests). */
export function forgetRememberedView(): void {
  remembered = null;
}
