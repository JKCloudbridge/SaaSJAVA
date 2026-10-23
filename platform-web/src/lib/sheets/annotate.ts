import type { ReactNode } from "react";
import { cellKey } from "./geometry";
import type { DisplayRow, GridDoc, Selection } from "./types";

/**
 * Pointing at a place in a sheet (Functional track FA5, ADR-0191): a row, a column or a cell, by the stable identifiers of
 * the document (ADR-0173), never by a position. The grid in annotate mode only reads; this is how a note finds its place and
 * how a place finds its note. Pure functions: no rule here is a permission, and the server checks every place again against
 * the version it belongs to. The module knows no business word: what a note is, is the caller's.
 */

/** A row, a column, both (a cell). A place with neither is the sheet as a whole and is not drawn in the grid. */
export interface Place {
  rowId: string | null;
  columnId: string | null;
}

/** The three places the active cell stands for, or null where a kind of place does not exist there. */
export interface SelectionPlaces {
  cell: Place | null;
  row: Place | null;
  column: Place | null;
}

/** How many notes mark each row, column and cell of the version shown, for the grid to draw. */
export interface AnnotationMarks {
  cells: ReadonlyMap<string, number>;
  rows: ReadonlyMap<string, number>;
  columns: ReadonlyMap<string, number>;
}

export const NO_MARKS: AnnotationMarks = { cells: new Map(), rows: new Map(), columns: new Map() };

/** What the workspace tells the panel that sits under the grid in annotate mode. */
export interface AnnotateContext {
  /** The version shown. */
  versionNo: number;
  doc: GridDoc;
  /** The places the active cell stands for. */
  places: SelectionPlaces;
  /** A place in words ("Column Torque, row First"). */
  describe(place: Place): string;
  /** Moves the grid's selection to a place and focuses the grid; a place that is not in this version is ignored. */
  select(place: Place): void;
  /** Raised each time the person presses Enter on a cell: the panel moves focus to its box. */
  activations: number;
}

/** What a caller hands the workspace to turn a read-only version into one that can be pointed at. */
export interface SheetAnnotate {
  /** The only version that can be annotated (the one in use). */
  versionNo: number;
  marks: AnnotationMarks;
  panel(context: AnnotateContext): ReactNode;
}

export function samePlace(a: Place, b: Place): boolean {
  return a.rowId === b.rowId && a.columnId === b.columnId;
}

/** The places of the active cell. A blank sample row (what a filler will get) is not a place. */
export function placesOfSelection(doc: GridDoc, rows: readonly DisplayRow[], selection: Selection): SelectionPlaces {
  const row = rows[selection.fr];
  const column = doc.columns[selection.fc];
  const realRow = row && row.zone !== "blank" ? row : undefined;
  return {
    cell: realRow && column ? { rowId: realRow.id, columnId: column.id } : null,
    row: realRow ? { rowId: realRow.id, columnId: null } : null,
    column: column ? { rowId: null, columnId: column.id } : null,
  };
}

/** The selection that stands on a place, or null when the version has no such row or column. */
export function selectionOfPlace(doc: GridDoc, rows: readonly DisplayRow[], place: Place): Selection | null {
  const rowPos = place.rowId === null ? 0 : rows.findIndex((row) => row.id === place.rowId && row.zone !== "blank");
  const columnPos = place.columnId === null ? 0 : doc.columns.findIndex((column) => column.id === place.columnId);
  if (rowPos < 0 || columnPos < 0 || rows.length === 0 || doc.columns.length === 0) {
    return null;
  }
  return { ar: rowPos, ac: columnPos, fr: rowPos, fc: columnPos };
}

function rowName(doc: GridDoc, rows: readonly DisplayRow[], rowId: string): string {
  const position = rows.findIndex((row) => row.id === rowId);
  const label = [...doc.headerRows, ...doc.rows].find((line) => line.id === rowId)?.label;
  return label && label.trim() !== "" ? `“${label}”` : position >= 0 ? `number ${position + 1}` : "that is not in this version";
}

function columnName(doc: GridDoc, columnId: string): string {
  const position = doc.columns.findIndex((column) => column.id === columnId);
  const label = doc.columns[position]?.label;
  return label && label.trim() !== "" ? `“${label}”` : position >= 0 ? `number ${position + 1}` : "that is not in this version";
}

/** A place in words, as it is in this document. */
export function describePlace(doc: GridDoc, rows: readonly DisplayRow[], place: Place): string {
  if (place.rowId === null && place.columnId === null) {
    return "The whole sheet";
  }
  if (place.rowId !== null && place.columnId !== null) {
    return `Cell: column ${columnName(doc, place.columnId)}, row ${rowName(doc, rows, place.rowId)}`;
  }
  return place.rowId !== null ? `Row ${rowName(doc, rows, place.rowId)}` : `Column ${columnName(doc, place.columnId ?? "")}`;
}

/** How many notes mark each place. A note on the sheet as a whole marks no place in the grid. */
export function marksOf(places: readonly Place[]): AnnotationMarks {
  const cells = new Map<string, number>();
  const rows = new Map<string, number>();
  const columns = new Map<string, number>();
  const bump = (map: Map<string, number>, key: string) => map.set(key, (map.get(key) ?? 0) + 1);
  for (const place of places) {
    if (place.rowId !== null && place.columnId !== null) {
      bump(cells, cellKey(place.rowId, place.columnId));
    } else if (place.rowId !== null) {
      bump(rows, place.rowId);
    } else if (place.columnId !== null) {
      bump(columns, place.columnId);
    }
  }
  return { cells, rows, columns };
}
