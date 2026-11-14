/**
 * The ink of a cell that has a fill and states no text colour (SH4, POC 2 rule): the one of two inks that reads better on
 * the fill, by the contrast ratio of the web content guidelines. Exact colours are never adjusted; only this default is
 * chosen. The two inks are tokens (`--sheet-ink-dark`, `--sheet-ink-light`); their relative brightness is written here as
 * numbers so no colour literal is in source, and a test holds the numbers to the tokens.
 */

/** Relative luminance of the dark ink token (black). */
export const DARK_INK_LUMINANCE = 0;
/** Relative luminance of the light ink token (white). */
export const LIGHT_INK_LUMINANCE = 1;

export type Ink = "dark" | "light";

/** Relative luminance of a `#rrggbb` colour that has already been checked. */
export function luminanceOf(hex: string): number {
  const channel = (at: number): number => {
    const value = parseInt(hex.slice(at, at + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

export function contrastBetween(a: number, b: number): number {
  const [high = 0, low = 0] = [a, b].sort((x, y) => y - x);
  return (high + 0.05) / (low + 0.05);
}

/** The ink for a fill: dark when it reads at least as well as light. */
export function inkFor(fill: string): Ink {
  const fillLuminance = luminanceOf(fill);
  return contrastBetween(fillLuminance, DARK_INK_LUMINANCE) >= contrastBetween(fillLuminance, LIGHT_INK_LUMINANCE) ? "dark" : "light";
}
