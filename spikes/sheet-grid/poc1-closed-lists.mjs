// POC 1: how well do CLOSED lists (font families, sizes, colours) cover the two real workbooks? Counts only; no text of the files.
//   POC1_DATA=<folder> node poc1-closed-lists.mjs
import { readFileSync } from "node:fs";

const DATA = process.env.POC1_DATA;
const FAMILIES = { SANS: ["calibri", "arial", "arial unicode ms", "segoe ui", "verdana", "tahoma", "helvetica", "meiryo", "ms pgothic", "ms gothic", "yu gothic"],
  SERIF: ["cambria", "times new roman", "georgia", "ms mincho", "mincho"], DISPLAY: ["aharoni", "arial black", "impact"], MONO: ["courier new", "consolas", "lucida console"] };
const familyOf = (name) => { const n = name.toLowerCase(); for (const [k, list] of Object.entries(FAMILIES)) if (list.some((x) => n.includes(x))) return k; return null; };
const SIZES = [8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28]; // the sizes of a spreadsheet application's own size menu, up to 28
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
// a closed palette: greys, and eight hues in three strengths (light, medium, dark) = 8 + 24 = 32 entries
const PALETTE = ["#000000", "#404040", "#7F7F7F", "#BFBFBF", "#E6E6E6", "#F2F2F2", "#FFFFFF", "#FFFF00",
  "#FFC7CE", "#FF0000", "#9C0006", "#FFE5CC", "#FFA500", "#9C5700", "#FFFFCC", "#FFD966", "#7F6000",
  "#E2EFDA", "#70AD47", "#375623", "#DDEBF7", "#5B9BD5", "#1F4E79", "#D9E1F2", "#4472C4", "#203864",
  "#E4DFEC", "#8064A2", "#3F3151", "#FCE4D6", "#F4B084", "#833C0B", "#D6DCE4", "#8497B0", "#333F4F", "#C9C9C9"];
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const nearest = (h) => Math.min(...PALETTE.map((p) => dist(hex(h), hex(p))));

for (const [file, sheets] of [["plant.json", [0, 1]], ["spm.json", [0, 1, 2, 3]]]) {
  const fx = JSON.parse(readFileSync(`${DATA}/${file}`, "utf8"));
  const fonts = new Set(), sizes = new Map(), unmapped = new Set(), fills = new Set(), texts = new Set(), formats = new Set();
  let styled = 0, sizeOff = 0, fillFar = 0, textFar = 0, fillsTotal = 0, textTotal = 0;
  for (const si of sheets) {
    const used = new Set(fx.sheets[si].cells.map((c) => c.s));
    for (const id of used) {
      const st = fx.sheets[si].styles[id]; styled++;
      fonts.add(st.font.name); const fam = familyOf(st.font.name); if (!fam) unmapped.add(st.font.name);
      sizes.set(st.font.size, 1); if (!SIZES.includes(st.font.size)) sizeOff++;
      if (st.fill) { fills.add(st.fill); fillsTotal++; if (nearest(st.fill) > 30) fillFar++; }
      if (st.font.color) { texts.add(st.font.color); textTotal++; if (nearest(st.font.color) > 30) textFar++; }
      formats.add(st.numFmt);
    }
  }
  console.log(`${file}: styles used ${styled}; font families ${fonts.size} (not in the closed families: ${unmapped.size}); distinct sizes ${[...sizes.keys()].sort((a, b) => a - b).join(",")} (outside the 13 offered sizes: ${sizeOff} styles);`
    + ` distinct fill colours ${fills.size}, of those farther than 30 (colour distance) from the 36-entry palette: ${[...fills].filter((f) => nearest(f) > 30).length};`
    + ` distinct text colours ${texts.size}, farther: ${[...texts].filter((f) => nearest(f) > 30).length}; distinct number formats ${formats.size}`);
}
