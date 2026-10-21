// What happens when a candidate is imported where there is no window (server rendering, a build step).
const tests = [
  ["@fortune-sheet/react", () => import("@fortune-sheet/react")],
  ["@univerjs/presets + sheets-core preset", async () => { await import("@univerjs/presets"); return import("@univerjs/preset-sheets-core"); }],
  ["jspreadsheet-ce", () => import("jspreadsheet-ce")],
  ["@tanstack/react-virtual (own grid)", () => import("@tanstack/react-virtual")],
];
for (const [name, load] of tests) {
  try { await load(); console.log(`${name}: imports without a window`); }
  catch (e) { console.log(`${name}: FAILS on the server: ${e.name}: ${String(e.message).split("\n")[0].slice(0, 100)}`); }
}
