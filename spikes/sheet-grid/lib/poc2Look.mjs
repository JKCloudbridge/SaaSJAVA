// POC 2, WP4 (throwaway): the three candidate sets of looks, written BEFORE any distance was measured.
//   (a) POC 1's closed lists: 4 font families, a 36-name palette (lib/poc1Closed.mjs).
//   (b) a wider closed list: 13 named font families and 3 generic fallbacks, a 72-name palette (12 hues x 5 strengths + 12 greys).
//   (c) any colour written as a validated #rrggbb value, with the font families of (b).
//   (d) a font name taken from the file as typed is REJECTED: a name placed into a style value is an injection risk, and a font the
//       viewer does not have changes the layout without anybody knowing.
// The colour distance is CIE76 in Lab (D65), stated before the measurement. Nothing here holds text of a form.

import { PALETTE as POC1_PALETTE, familyOf as poc1Family, sizeOf as poc1Size } from "./poc1Closed.mjs";

// ---------------------------------------------------------------------------------------------------------------- colour
export const HEX = /^#[0-9a-fA-F]{6}$/;
export const rgbOf = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const lin = (v) => { const c = v / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
export function lab([r, g, b]) {
  const R = lin(r), G = lin(g), B = lin(b);
  const X = (0.4124564 * R + 0.3575761 * G + 0.1804375 * B) / 0.95047, Y = 0.2126729 * R + 0.7151522 * G + 0.072175 * B, Z = (0.0193339 * R + 0.119192 * G + 0.9503041 * B) / 1.08883;
  const f = (t) => (t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116);
  return [116 * f(Y) - 16, 500 * (f(X) - f(Y)), 200 * (f(Y) - f(Z))];
}
export const deltaE = (a, b) => { const x = lab(a), y = lab(b); return Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]); };

function hslToRgb(h, s, l) {
  const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [r, g, b].map((v) => Math.round((v + m) * 255));
}
/** (b): 12 hues (every 30 degrees) in 5 strengths, and 12 greys from black to white = 72 names. */
export const PALETTE_72 = (() => {
  const out = [];
  const strengths = [[0.9, 0.9], [0.8, 0.75], [0.7, 0.6], [0.65, 0.45], [0.6, 0.3]];
  for (let hue = 0; hue < 360; hue += 30) strengths.forEach(([s, l], i) => out.push({ name: `h${hue}-${i + 1}`, rgb: hslToRgb(hue, s, l) }));
  for (let i = 0; i < 12; i++) { const v = Math.round((i * 255) / 11); out.push({ name: `g${i}`, rgb: [v, v, v] }); }
  return out;
})();
export const PALETTE_36 = POC1_PALETTE;

export function nearest(palette, hex) {
  const c = rgbOf(hex); let best = null, d = Infinity;
  for (const p of palette) { const x = deltaE(c, p.rgb); if (x < d) { d = x; best = p; } }
  return { name: best.name, distance: d };
}

// ---------------------------------------------------------------------------------------------------------------- fonts
/** (b) and (c): the closed list. The CSS stack of each key is written in the generated stylesheet only, never in a stored document. */
export const FONT_KEYS = [
  "SANS_NEUTRAL", "SANS_HUMANIST", "SANS_WIDE", "SANS_FRIENDLY", "SANS_UNICODE", "DISPLAY_HEAVY", "DISPLAY_IMPACT",
  "SERIF_TEXT", "SERIF_SCREEN", "SERIF_SLAB", "MONO_CLASSIC", "MONO_CODE", "SANS_UI", "SANS", "SERIF", "MONO",
];
/** name (lower case, contains) -> key. "exact" when the file's family is the entry's own, "alias" when it is mapped to the nearest entry. */
export const FONT_TABLE = [
  ["arial unicode", "SANS_UNICODE", "exact"], ["arial black", "DISPLAY_HEAVY", "exact"], ["aharoni", "DISPLAY_HEAVY", "alias"], ["impact", "DISPLAY_IMPACT", "exact"],
  ["arial", "SANS_NEUTRAL", "exact"], ["helvetica", "SANS_NEUTRAL", "exact"], ["calibri", "SANS_HUMANIST", "exact"], ["segoe", "SANS_HUMANIST", "alias"],
  ["verdana", "SANS_WIDE", "exact"], ["tahoma", "SANS_WIDE", "alias"], ["trebuchet", "SANS_FRIENDLY", "exact"],
  ["times", "SERIF_TEXT", "exact"], ["georgia", "SERIF_SCREEN", "exact"], ["cambria", "SERIF_SLAB", "exact"],
  ["courier", "MONO_CLASSIC", "exact"], ["consolas", "MONO_CODE", "exact"], ["lucida console", "MONO_CODE", "alias"],
];
/** A name from a file is NEVER used as typed: it is compared with the table, then with a few class words, then it falls back to the generic key. */
export function fontKey(name) {
  const n = String(name ?? "").toLowerCase();
  for (const [part, key, kind] of FONT_TABLE) if (n.includes(part)) return { key, kind };
  if (/mono|courier|code|typewriter/.test(n)) return { key: "MONO", kind: "fallback" };
  if (/serif|roman|mincho|song|garamond|palatino|book/.test(n) && !/sans/.test(n)) return { key: "SERIF", kind: "fallback" };
  return { key: "SANS", kind: "fallback" };
}
export { poc1Family, poc1Size };

// ---------------------------------------------------------------------------------------------------------------- sizes
export const SIZES_WHOLE = Array.from({ length: 21 }, (_, i) => 8 + i);
export const sizeKey = (pt) => SIZES_WHOLE.reduce((best, s) => (Math.abs(s - pt) < Math.abs(best - pt) ? s : best), SIZES_WHOLE[0]);

// ---------------------------------------------------------------------------------------------------------------- widths and heights
/** The file's own unit for a column is characters of the default digit width; at 100 percent that digit is 7 pixels and the stored width already holds the padding. */
export const PX_PER_CHAR = 7;
export const LIMITS_V1 = { colMin: 40, colMax: 1000, rowMin: 16, rowMax: 400 };
export const LIMITS_V2_DRAFT = { colMin: 8, colMax: 1000, rowMin: 4, rowMax: 600 };
export const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
