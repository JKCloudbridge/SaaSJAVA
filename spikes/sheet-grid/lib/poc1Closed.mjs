// POC 1, WP3 (throwaway): the CLOSED lists the extended own grid would store instead of free values, and the small number formatter.
// A neutral style (from the Java spike's reading of a workbook) is mapped to names; the names are the only thing the stored format would
// hold, and each name is a class in poc1-own.css (no free colour, size or font ever reaches the page, so the strict content policy holds).

export const FAMILIES = {
  SANS: ["calibri", "arial", "arial unicode ms", "segoe ui", "verdana", "tahoma", "helvetica", "meiryo", "ms pgothic", "ms gothic", "yu gothic"],
  SERIF: ["cambria", "times new roman", "georgia", "ms mincho", "mincho"],
  DISPLAY: ["aharoni", "arial black", "impact"],
  MONO: ["courier new", "consolas", "lucida console"],
};
export const SIZES = Array.from({ length: 21 }, (_, i) => 8 + i); // 8 to 28 points: the sizes the real forms use fit (13, 15 and 26 included)

export const familyOf = (name) => { const n = String(name ?? "").toLowerCase(); for (const [k, l] of Object.entries(FAMILIES)) if (l.some((x) => n.includes(x))) return k; return "SANS"; };
export const sizeOf = (pt) => SIZES.reduce((best, s) => (Math.abs(s - pt) < Math.abs(best - pt) ? s : best), SIZES[0]);

// Palette: neutrals and six hues in three strengths; every entry is (hue, strength). Dark mode comes from mixing the hue with the
// surface token in CSS, so one name serves both themes.
export const HUES = { red: [214, 69, 69], orange: [230, 140, 30], yellow: [235, 200, 20], green: [60, 160, 90], blue: [60, 120, 200], purple: [130, 90, 170], pink: [210, 90, 150], brown: [140, 90, 50], teal: [40, 150, 150] };
const STRENGTH = { 1: 0.18, 2: 0.45, 3: 0.85 };
const mix = (rgb, t, base) => rgb.map((v, i) => Math.round(v * t + base[i] * (1 - t)));
export const PALETTE = [{ name: "text", rgb: [21, 32, 43] }, { name: "grey-1", rgb: [245, 246, 248] }, { name: "grey-2", rgb: [205, 210, 216] }, { name: "grey-3", rgb: [120, 130, 142] }, { name: "white", rgb: [255, 255, 255] },
  ...Object.entries(HUES).flatMap(([h, rgb]) => [1, 2, 3].map((s) => ({ name: `${h}-${s}`, rgb: mix(rgb, STRENGTH[s], [255, 255, 255]) })))];
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
export function colourName(hexString) {
  if (!hexString) return null;
  const c = hex(hexString);
  let best = PALETTE[0], d = Infinity;
  for (const p of PALETTE) { const x = Math.hypot(c[0] - p.rgb[0], c[1] - p.rgb[1], c[2] - p.rgb[2]); if (x < d) { d = x; best = p; } }
  return best.name;
}

export const BORDER_CLASS = { THIN: "thin", HAIR: "thin", DOTTED: "dotted", DASHED: "dashed", DASH_DOT: "dashed", DASH_DOT_DOT: "dashed", DOUBLE: "double", MEDIUM: "medium", MEDIUM_DASHED: "medium", MEDIUM_DASH_DOT: "medium", MEDIUM_DASH_DOT_DOT: "medium", SLANTED_DASH_DOT: "medium", THICK: "thick" };

/** The closed number formats. Anything else is shown as General and counted by the caller. */
export function formatKind(fmt) {
  const f = String(fmt ?? "General");
  if (f === "General") return "general";
  if (f === "0") return "int";
  if (f === "0.00") return "dec2";
  if (f === "0%") return "pct0";
  if (f === "0.00%") return "pct2";
  if (f === "#,##0" || f === "#,##0 ") return "thousands";
  if (f === "#,##0.00") return "thousands2";
  if (/mmm-yy/i.test(f)) return "monthYear";
  if (/^m\/d\/yy/i.test(f)) return "shortDate";
  if (/\*/.test(f) && /#,##0/.test(f)) return "accounting";
  return "other";
}
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const dateOf = (serial) => new Date(Math.round((serial - 25569) * 86400000));
export function format(kind, v) {
  if (typeof v !== "number") return v === undefined || v === null ? "" : String(v);
  const group = (n, d) => n.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
  switch (kind) {
    case "int": return String(Math.round(v));
    case "dec2": return v.toFixed(2);
    case "pct0": return `${Math.round(v * 100)}%`;
    case "pct2": return `${(v * 100).toFixed(2)}%`;
    case "thousands": return group(v, 0);
    case "thousands2": return group(v, 2);
    case "accounting": return v === 0 ? "-" : group(v, 0);
    case "monthYear": { const d = dateOf(v); return `${MONTHS[d.getUTCMonth()]}-${String(d.getUTCFullYear()).slice(2)}`; }
    case "shortDate": { const d = dateOf(v); return `${d.getUTCMonth() + 1}/${d.getUTCDate()}/${String(d.getUTCFullYear()).slice(2)}`; }
    default: return Number.isInteger(v) ? String(v) : String(Math.round(v * 1e10) / 1e10);
  }
}

/** Conditional rule (POI comparison codes): returns true when the value meets the rule. */
export function meets(op, limit, v) {
  if (typeof v !== "number" || typeof limit !== "number") return false;
  return { 3: v === limit, 4: v !== limit, 5: v > limit, 6: v < limit, 7: v >= limit, 8: v <= limit }[op] ?? false;
}
