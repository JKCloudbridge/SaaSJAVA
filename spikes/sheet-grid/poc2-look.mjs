// POC 2, WP4.1: how far is each candidate set of looks from the two real forms? Counts and distances only; no text of the files.
//   POC1_DATA=<folder with plant.json and spm.json> node poc2-look.mjs
import { readFileSync } from "node:fs";
import { HEX, rgbOf, deltaE, nearest, PALETTE_36, PALETTE_72, fontKey, poc1Family, SIZES_WHOLE, sizeKey, LIMITS_V1, LIMITS_V2_DRAFT, clamp } from "./lib/poc2Look.mjs";

const DATA = process.env.POC1_DATA;
const rows = [];
const summary = {};

function visible(fx) { return fx.sheets.filter((s) => !s.hidden); }

for (const [file, label] of [["plant.json", "plant"], ["spm.json", "supplier"]]) {
  const fx = JSON.parse(readFileSync(`${DATA}/${file}`, "utf8"));
  const fills = new Set(), texts = new Set(), families = new Set(), sizes = new Set();
  const colWidths = [], rowHeights = [];
  for (const sh of visible(fx)) {
    const used = new Set(sh.cells.map((c) => c.s));
    for (const id of used) {
      const st = sh.styles[id];
      if (st.fill) fills.add(st.fill.toLowerCase());
      if (st.font?.color) texts.add(st.font.color.toLowerCase());
      families.add(st.font.name); sizes.add(st.font.size);
    }
    for (const rule of sh.cf) { if (rule.fill) fills.add(rule.fill.toLowerCase()); if (rule.color) texts.add(rule.color.toLowerCase()); }
    // the stored width of the proof's fixture is round(width * 7 + 5): the +5 counted the padding twice, so the file's own pixels are 5 fewer
    sh.colWidths.forEach((px) => colWidths.push(px - 5));
    for (const px of Object.values(sh.rowHeights)) rowHeights.push(px);
  }
  const measure = (set) => {
    const list = [...set];
    const r = { count: list.length, bad: list.filter((h) => !HEX.test(h)).length };
    for (const [name, palette] of [["a", PALETTE_36], ["b", PALETTE_72]]) {
      const ds = list.map((h) => nearest(palette, h).distance);
      r[name] = { within10: ds.filter((d) => d <= 10).length, within30: ds.filter((d) => d <= 30).length, max: Math.max(0, ...ds).toFixed(1) };
    }
    // (c): any #rrggbb stays itself: the distance is that of a round trip through the validated text
    const ds = list.filter((h) => HEX.test(h)).map((h) => deltaE(rgbOf(h), rgbOf("#" + rgbOf(h).map((v) => v.toString(16).padStart(2, "0")).join(""))));
    r.c = { within10: ds.filter((d) => d <= 10).length, max: Math.max(0, ...ds).toFixed(1) };
    return r;
  };
  summary[label] = { fills: measure(fills), texts: measure(texts) };
  // fonts
  const fam = { exact: 0, alias: 0, fallback: 0 }, poc1Fam = new Set();
  for (const name of families) { fam[fontKey(name).kind]++; poc1Fam.add(poc1Family(name)); }
  const fractional = [...sizes].filter((s) => !Number.isInteger(s)).length, outside = [...sizes].filter((s) => s < 8 || s > 28).length;
  const sizeErr = Math.max(0, ...[...sizes].map((s) => Math.abs(sizeKey(s) - s)));
  // widths and heights
  const err = (list, lim) => { const e = list.map((px) => Math.abs(clamp(px, lim[0], lim[1]) - px)); return { off2: e.filter((x) => x > 2).length, max: Math.max(0, ...e) }; };
  summary[label].fonts = { distinct: families.size, ...fam, unmapped: 0, poc1FamiliesUsed: poc1Fam.size };
  summary[label].sizes = { distinct: sizes.size, fractional, outsideOffered: outside, largestRoundingPt: sizeErr };
  summary[label].columns = { count: colWidths.length, narrowest: Math.min(...colWidths), widest: Math.max(...colWidths), v1: err(colWidths, [LIMITS_V1.colMin, LIMITS_V1.colMax]), v2draft: err(colWidths, [LIMITS_V2_DRAFT.colMin, LIMITS_V2_DRAFT.colMax]) };
  summary[label].rows = { listed: rowHeights.length, smallest: Math.min(...rowHeights), tallest: Math.max(...rowHeights), v1: err(rowHeights, [LIMITS_V1.rowMin, LIMITS_V1.rowMax]), v2draft: err(rowHeights, [LIMITS_V2_DRAFT.rowMin, LIMITS_V2_DRAFT.rowMax]) };
}
console.log(JSON.stringify(summary, null, 1));
