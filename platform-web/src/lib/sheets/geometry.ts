import type { CellKind, DisplayRow, GridCell, GridDoc, Rect, Selection, Zone } from "./types";

/**
 * Where things are in a grid, in the order they are drawn (ADR-0172, ADR-0186). Header rows come first, then the named
 * rows, then (when the filler adds the rows) a few blank sample rows that show what the filler will get. A position in
 * this order is what the screen and a selection use; the document itself only ever uses identifiers.
 */

/** The height of a row that states none, in pixels. A default of the format, not a design size. */
export const DEFAULT_ROW_HEIGHT = 24;
/** The width of a column that states none, in pixels. */
export const DEFAULT_COLUMN_WIDTH = 120;
/** The height of the band of column labels above the first row, in pixels. */
export const LABEL_BAND_HEIGHT = 28;
/** How many blank sample rows a grid whose filler adds the rows shows. */
export const BLANK_ROWS = 10;

export const cellKey = (rowId: string, columnId: string): string => `${rowId}:${columnId}`;

/** The row and the column identifier of a cell key. A key is `row:column` (the format allows no other colon). */
export function splitKey(key: string): [string, string] {
  const colon = key.indexOf(":");
  return [key.slice(0, colon), key.slice(colon + 1)];
}

export function displayRows(doc: GridDoc, blankRows: number = BLANK_ROWS): DisplayRow[] {
  const rows: DisplayRow[] = [];
  for (const line of doc.headerRows) {
    rows.push({ id: line.id, zone: "header", height: line.height ?? DEFAULT_ROW_HEIGHT, label: line.label });
  }
  for (const line of doc.rows) {
    rows.push({ id: line.id, zone: "body", height: line.height ?? DEFAULT_ROW_HEIGHT, label: line.label });
  }
  if (doc.dimension === 1) {
    for (let i = 0; i < blankRows; i++) {
      rows.push({ id: `blank${i}`, zone: "blank", height: DEFAULT_ROW_HEIGHT });
    }
  }
  return rows;
}

export interface Indexes {
  rowPos: Map<string, number>;
  colPos: Map<string, number>;
}

export function indexesOf(doc: GridDoc, rows: DisplayRow[]): Indexes {
  return {
    rowPos: new Map(rows.map((row, i) => [row.id, i])),
    colPos: new Map(doc.columns.map((column, i) => [column.id, i])),
  };
}

/** A merged area in display positions, with the key of its top left cell. */
export interface MergeRect extends Rect {
  key: string;
}

export function mergeRects(doc: GridDoc, indexes: Indexes): MergeRect[] {
  const rects: MergeRect[] = [];
  for (const merge of doc.merges) {
    const [fromRow, fromColumn] = splitKey(merge.from);
    const [toRow, toColumn] = splitKey(merge.to);
    const r0 = indexes.rowPos.get(fromRow);
    const r1 = indexes.rowPos.get(toRow);
    const c0 = indexes.colPos.get(fromColumn);
    const c1 = indexes.colPos.get(toColumn);
    if (r0 === undefined || r1 === undefined || c0 === undefined || c1 === undefined) {
      continue;
    }
    rects.push({ key: merge.from, r0, c0, r1, c1 });
  }
  return rects;
}

export function overlaps(a: Rect, b: Rect): boolean {
  return a.r0 <= b.r1 && a.r1 >= b.r0 && a.c0 <= b.c1 && a.c1 >= b.c0;
}

export function contains(rect: Rect, row: number, column: number): boolean {
  return row >= rect.r0 && row <= rect.r1 && column >= rect.c0 && column <= rect.c1;
}

/** The selection as a rectangle, whichever way it was dragged. */
export function normalized(selection: Selection): Rect {
  return {
    r0: Math.min(selection.ar, selection.fr),
    r1: Math.max(selection.ar, selection.fr),
    c0: Math.min(selection.ac, selection.fc),
    c1: Math.max(selection.ac, selection.fc),
  };
}

/** Whether a cell is fixed text or a cell the filler types into (ADR-0173 section 1). */
export function cellKind(zone: Zone, cell: GridCell | undefined): CellKind {
  if (zone === "header") {
    return "fixed";
  }
  if (zone === "blank") {
    return "input";
  }
  return cell?.kind ?? (cell?.value != null ? "fixed" : "input");
}

/** The zone a row identifier is in, or undefined when the document has no such row. */
export function zoneOfRow(doc: GridDoc, rowId: string): "header" | "body" | undefined {
  if (doc.headerRows.some((line) => line.id === rowId)) {
    return "header";
  }
  return doc.rows.some((line) => line.id === rowId) ? "body" : undefined;
}

/** Everything the document uses as an identifier: columns, header rows and rows share one namespace. */
export function usedIds(doc: GridDoc): Set<string> {
  return new Set([...doc.columns.map((c) => c.id), ...doc.headerRows.map((r) => r.id), ...doc.rows.map((r) => r.id)]);
}

/**
 * The item at an index that the caller has already shown to be inside the list. A mistake fails loudly instead of
 * drawing or writing something wrong (the project compiles with unchecked indexes off).
 */
export function at<T>(list: readonly T[], index: number): T {
  const item = list[index];
  if (item === undefined) {
    throw new Error(`No item at ${index}`);
  }
  return item;
}
