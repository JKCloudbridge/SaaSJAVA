import type { MergeRect } from "../geometry";
import { SIDES, type BorderStyle, type ResolvedLook, type Side } from "./look";

/**
 * Which border a cell draws on each side (SH4). Two neighbouring cells share one line: a cell draws its RIGHT and BOTTOM
 * edge, resolved with what its neighbour states on the facing side, and draws its top and left only at the outer edge of
 * the sheet. So a line is never drawn twice, and when both sides state one the stronger wins. A merged area draws its own
 * edge: the strongest border stated along that edge by the cells of the area (the covered cells may carry the borders of
 * the area) and by the cells just outside it.
 */

export type Edges = Partial<Record<Side, BorderStyle>>;

/** How strong a border is when two cells state different ones for the same line. */
const STRENGTH: Record<BorderStyle, number> = { DOTTED: 1, DASHED: 2, THIN: 3, MEDIUM: 4, DOUBLE: 5, THICK: 6 };

export function stronger(a: BorderStyle | undefined, b: BorderStyle | undefined): BorderStyle | undefined {
  if (a === undefined) return b;
  if (b === undefined) return a;
  return STRENGTH[a] >= STRENGTH[b] ? a : b;
}

/** What the sheet gives the borders of cells: the look at a display position, or none. */
export interface BorderSource {
  look(row: number, column: number): ResolvedLook | undefined;
  rows: number;
  columns: number;
}

/**
 * The borders the cell at a display position (the top left cell of its merged area, if it has one) draws.
 * Cost is the length of the cell's edges, so a sheet of fifty thousand cells costs a few per cell.
 */
export function edgesOf(source: BorderSource, row: number, column: number, area?: MergeRect): Edges {
  const r1 = area ? area.r1 : row;
  const c1 = area ? area.c1 : column;
  const out: Edges = {};
  let right: BorderStyle | undefined;
  for (let r = row; r <= r1; r++) {
    right = stronger(right, source.look(r, c1)?.borders.right);
    if (c1 + 1 < source.columns) right = stronger(right, source.look(r, c1 + 1)?.borders.left);
  }
  let bottom: BorderStyle | undefined;
  for (let c = column; c <= c1; c++) {
    bottom = stronger(bottom, source.look(r1, c)?.borders.bottom);
    if (r1 + 1 < source.rows) bottom = stronger(bottom, source.look(r1 + 1, c)?.borders.top);
  }
  if (right) out.right = right;
  if (bottom) out.bottom = bottom;
  if (row === 0) {
    let top: BorderStyle | undefined;
    for (let c = column; c <= c1; c++) top = stronger(top, source.look(0, c)?.borders.top);
    if (top) out.top = top;
  }
  if (column === 0) {
    let left: BorderStyle | undefined;
    for (let r = row; r <= r1; r++) left = stronger(left, source.look(r, 0)?.borders.left);
    if (left) out.left = left;
  }
  return out;
}

/** Whether a cell states any border at all (a cell with none, in a sheet that states a look, shows the faint grid line). */
export function hasEdges(edges: Edges): boolean {
  return SIDES.some((side) => edges[side] !== undefined);
}
