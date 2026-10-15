import { THEME_VARIABLE_NAMES, type ThemeVariables } from "./protocol.ts";

/**
 * The design values a custom page may use, read from the page the frame sits in (Sprint 18, ADR-0127). The page's own
 * stylesheet is the single source (`src/styles/tokens.css`, dark mode included), so a custom page looks like the rest of
 * the application without anyone copying a value: the host reads the values that are in force now and gives them to the
 * frame over its private channel, and again when the colour scheme changes.
 */
export function readTheme(root: HTMLElement = document.documentElement): ThemeVariables {
  const style = getComputedStyle(root);
  const theme: ThemeVariables = {};
  for (const name of THEME_VARIABLE_NAMES) {
    const value = style.getPropertyValue(name).trim();
    if (value) {
      theme[name] = value;
    }
  }
  return theme;
}

/** `dark` when the person's system asks for it, else `light`. */
export function currentScheme(): "light" | "dark" {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

/** Calls the listener when the system's colour scheme changes. Returns the function that stops listening. */
export function watchScheme(listener: (scheme: "light" | "dark") => void): () => void {
  if (typeof window.matchMedia !== "function") {
    return () => {};
  }
  const query = window.matchMedia("(prefers-color-scheme: dark)");
  const onChange = () => listener(query.matches ? "dark" : "light");
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}
