/**
 * The grid of a sheet as the browser works with it (Functional track FA3, ADR-0172, ADR-0173, ADR-0186). This is the
 * server's own canonical document, read into plain types, and nothing else: no library's format exists here or reaches
 * the API. The model functions in this folder take a document and give a new one; none of them touches the page.
 */

export type ColumnType = "text" | "number" | "date" | "picklist" | "boolean";
export type RowsMode = "FIXED" | "REPEATABLE" | "FIXED_AND_REPEATABLE";
export type FontSize = "SMALL" | "NORMAL" | "LARGE" | "TITLE";
export type Alignment = "LEFT" | "CENTER" | "RIGHT";
export type TextColour = "DEFAULT" | "ACCENT" | "SUCCESS" | "DANGER";
export const ALIGNMENTS: readonly Alignment[] = ["LEFT", "CENTER", "RIGHT"];
export const TEXT_COLOURS: readonly TextColour[] = ["DEFAULT", "ACCENT", "SUCCESS", "DANGER"];
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
  underline?: boolean;
  alignment?: Alignment;
  colour?: TextColour;
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

/**
 * What a cell of a sheet in format version 2 has that the editor does not know (SH1A, ADR-0251): its look and its link.
 * Kept exactly as the server sent it, beside the cell, and written back with it.
 */
export interface CellExtras {
  look?: Record<string, unknown>;
  link?: string;
}

/**
 * The parts of a sheet in format version 2 that the present editor does not know, kept exactly as they were so that an
 * author who changes a text, a width or a merge does not destroy the rest. The editor never reads these; the adapter
 * puts them back when the document is written (`toApi`). A part that points at a row or a column that no longer exists
 * is dropped when it is written, because the server would refuse it.
 */
export interface SheetExtras {
  key: string;
  title?: string;
  /** False for an information tab. Absent means true. */
  fillable?: boolean;
  freeze?: Record<string, unknown>;
  print?: Record<string, unknown>;
  /** The input cells that state their own type, requirement or class, by cell key. */
  inputs?: Record<string, unknown>;
  /** The conditional colours (SH1B), in the order they are applied, each pointing at a range by two cell keys. */
  conditions?: Record<string, unknown>[];
  /** The look and the link of cells, by cell key. */
  cells: Record<string, CellExtras>;
  /** The sheets after the first, untouched: a template holds one for now. */
  otherSheets?: unknown[];
}

export interface GridDoc {
  /** 2 when the document was read from a sheet in format version 2; `sheet` then holds what the editor does not know. */
  formatVersion: 1 | 2;
  dimension: 1 | 2;
  rowsMode: RowsMode;
  columns: GridColumn[];
  headerRows: GridLine[];
  rows: GridLine[];
  cells: Record<string, GridCell>;
  merges: GridMerge[];
  sheet?: SheetExtras;
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
