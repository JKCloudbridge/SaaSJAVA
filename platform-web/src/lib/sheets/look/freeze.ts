import type { MergeRect } from "../geometry";

/**
 * The rows and columns kept in view while the rest scrolls (SH4). The format states them as a count from the top and from
 * the left (header rows first). What is used can be less than what is stated, for two reasons the page decides and the
 * format does not: a merged area must not be cut by the line (it would be drawn half fixed and half moving), so the line is
 * moved up or left to the nearest place no merged area crosses; and the fixed part must leave room to scroll, so it never
 * takes more than three fifths of the height or the width of the view.
 */

export interface Freeze {
  rows: number;
  columns: number;
}

/** The most of the view the fixed part may take, so there is always room to scroll. */
const MAX_SHARE = 0.6;

export const NO_FREEZE: Freeze = Object.freeze({ rows: 0, columns: 0 });

/** The stored freeze, read exactly: a whole number from zero, anything else is zero. */
export function readFreeze(raw: Record<string, unknown> | undefined): Freeze {
  const whole = (value: unknown): number => (typeof value === "number" && Number.isInteger(value) && value > 0 ? value : 0);
  return raw ? { rows: whole(raw.rows), columns: whole(raw.columns) } : NO_FREEZE;
}

export interface FreezeSpace {
  rowHeights: readonly number[];
  columnWidths: readonly number[];
  /** The size of the view in pixels, with the band of column labels taken off the height. */
  viewHeight: number;
  viewWidth: number;
}

/**
 * The freeze that is drawn. A line is a count of rows (or columns) above (or left of) it; it may not fall inside a merged
 * area and the part above it may take at most three fifths of the view.
 */
export function effectiveFreeze(stated: Freeze, merges: readonly MergeRect[], space: FreezeSpace): Freeze {
  let rows = Math.min(stated.rows, space.rowHeights.length - 1);
  let columns = Math.min(stated.columns, space.columnWidths.length - 1);
  const fits = (sizes: readonly number[], count: number, limit: number): boolean =>
    sizes.slice(0, count).reduce((sum, size) => sum + size, 0) <= limit;
  while (rows > 0 && (merges.some((m) => m.r0 < rows && m.r1 >= rows) || !fits(space.rowHeights, rows, space.viewHeight * MAX_SHARE))) {
    rows--;
  }
  while (columns > 0 && (merges.some((m) => m.c0 < columns && m.c1 >= columns) || !fits(space.columnWidths, columns, space.viewWidth * MAX_SHARE))) {
    columns--;
  }
  return { rows: Math.max(0, rows), columns: Math.max(0, columns) };
}
