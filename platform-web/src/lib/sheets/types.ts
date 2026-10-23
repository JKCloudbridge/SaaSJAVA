/**
 * The grid of a sheet as the browser works with it (Functional track FA3, ADR-0172, ADR-0173, ADR-0186). This is the
 * server's own canonical document, read into plain types, and nothing else: no library's format exists here or reaches
 * the API. The model functions in this folder take a document and give a new one; none of them touches the page.
 */

export type ColumnType = "text" | "number" | "date" | "picklist" | "boolean";
export type RowsMode = "FIXED" | "REPEATABLE" | "FIXED_AND_REPEATABLE";
export type FontSize = "SMALL" | "NORMAL" | "LARGE" | "TITLE";
export type CellKind = "fixed" | "input";

export const COLUMN_TYPES: readonly ColumnType[] = ["text", "number", "date", "picklist", "boolean"];
export const FONT_SIZES: readonly FontSize[] = ["SMALL", "NORMAL", "LARGE", "TITLE"];

export interface GridColumn {
  id: string;
  label: string;
  type: ColumnType;
  required?: boolean;
  /** Copied, when a fill-in is saved, to the table a report reads (FF2). Absent means no. */
  reportable?: boolean;
  default?: string | boolean | null;
  picklist?: { values: string[] } | null;
  width?: number;
}

/** A header row or a named body row. */
export interface GridLine {
  id: string;
  label?: string;
  height?: number;
}

export interface CellStyle {
  bold?: boolean;
  italic?: boolean;
  fontSize?: FontSize;
}

export interface GridCell {
  value?: string | boolean | null;
  style?: CellStyle;
  kind?: CellKind;
}

export interface GridMerge {
  from: string;
  to: string;
}

export interface GridDoc {
  formatVersion: 1;
  dimension: 1 | 2;
  rowsMode: RowsMode;
  columns: GridColumn[];
  headerRows: GridLine[];
  rows: GridLine[];
  cells: Record<string, GridCell>;
  merges: GridMerge[];
}

/** What a grid may hold in this installation, as the API says (the server checks again on every save). */
export interface SheetLimits {
  maxBytes: number;
  maxRows: number;
  maxColumns: number;
  maxHeaderRows: number;
  maxCellChars: number;
  maxLabelChars: number;
  maxPicklistValues: number;
  maxMerges: number;
}

export type Zone = "header" | "body" | "blank";

/** One row as drawn: header rows, then named rows, then (one dimension) blank sample rows. */
export interface DisplayRow {
  id: string;
  zone: Zone;
  height: number;
  label?: string;
}

/** A rectangle of display positions, both ends included. */
export interface Rect {
  r0: number;
  c0: number;
  r1: number;
  c1: number;
}

/** A selection in display positions: where it started (anchor) and where it is now (focus). */
export interface Selection {
  ar: number;
  ac: number;
  fr: number;
  fc: number;
}

/** What an operation came to: the new document, or why it was refused (in words for a person). */
export type OpResult = { ok: true; doc: GridDoc; note?: string } | { ok: false; error: string };

/** A place that is wrong, in the words and the shape the API uses (`grid.columns[1].label`). */
export interface Problem {
  place: string;
  message: string;
}
