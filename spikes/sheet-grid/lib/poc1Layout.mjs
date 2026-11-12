// POC 1: random layouts of cell classes for the cell-protection experiment. Shared by the page and the browser script.
// Classes: "F" fixed (nobody types), "P" filler (the plant person types), "R" reviewer (the reviewer types).

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const ROWS = 8;
export const COLS = 6;

export function layoutOf(seed) {
  const rand = mulberry32(seed);
  const grid = [];
  for (let r = 0; r < ROWS; r++) {
    const row = [];
    for (let c = 0; c < COLS; c++) {
      const x = rand();
      row.push(x < 0.3 ? "F" : x < 0.7 ? "P" : "R");
    }
    grid.push(row);
  }
  // make sure every layout mixes the classes in the first row and the second row, so a block always crosses a boundary
  grid[0][0] = "F"; grid[0][1] = "P"; grid[0][2] = "R";
  grid[1][0] = "P"; grid[1][1] = "R"; grid[1][2] = "F";
  return grid;
}

/** May this role change a cell of this class? Fixed: nobody. Filler cells: the filler. Reviewer cells: the reviewer. */
export function mayChange(role, cls) {
  if (cls === "F") return false;
  return role === "filler" ? cls === "P" : cls === "R";
}

export function forbiddenSet(grid, role) {
  const out = new Set();
  grid.forEach((row, r) => row.forEach((cls, c) => { if (!mayChange(role, cls)) out.add(`${r},${c}`); }));
  return out;
}
