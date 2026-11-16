/**
 * The cells of a fill-in as the browser works with them (Functional track FF1, ADR-0173 section 6, ADR-0192). This is the
 * server's own canonical document, shape B (a list of rows, each with its cells keyed by the identifier of the column),
 * read into plain types. The sheet it was made on (a {@link import("./types").GridDoc}) says which cells exist and which a
 * person may type into; the fill-in holds values and, in version 2, sparse input-cell style overrides.
 */

/**
 * What a cell of a fill-in holds: text (a number and a date travel as text), yes or no, or, in an attachment cell of a
 * fill-in in format 3, the identifiers of the files of the fill-in's header record. A file is never named by an address
 * or a file name in a cell.
 */
export type FillValue = string | boolean | string[] | FillChoice;

/**
 * The value of a Yes/No cell with a reason (format 3, SH5.1, ADR-0263): the answer and the reason the person typed;
 * either may be missing, never both. A formula and a report see only the answer.
 */
export interface FillChoice {
  answer?: boolean;
  reason?: string;
}

/** Whether a value is the answer and reason of a Yes/No cell with a reason. */
export function isChoice(value: FillValue | undefined | null): value is FillChoice {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** One row: the identifier of a row of the sheet, or one this browser made for a row the filler added. */
export type FillStyle = import("./types").CellStyle;

export interface FillRow {
  id: string;
  cells: Record<string, FillValue>;
  styles?: Record<string, FillStyle>;
}

export interface FillDoc {
  /** 1 and 2 for a sheet in format version 1; 3 for a sheet in format version 2 (SH5), which also names its sheet. */
  formatVersion: 1 | 2 | 3;
  /** The key of the sheet the cells belong to; only in format 3. */
  sheetKey?: string;
  rows: FillRow[];
}

/** What a fill-in may hold in this installation, as the API says (the server checks again on every save). */
export interface FillLimits {
  maxBytes: number;
  maxRows: number;
  maxCellChars: number;
}

export type FillStatus = "DRAFT" | "SUBMITTED" | "RETURNED" | "APPROVED";

/** What an operation came to: the new fill-in, or why it was refused (in words for a person). */
export type FillOp = { ok: true; fill: FillDoc; note?: string } | { ok: false; error: string };

/** A row as it is drawn: the rows the sheet names, then the rows the filler added. */
export interface FillDisplayRow {
  id: string;
  /** Whether the sheet names this row (it cannot be removed) or the filler added it. */
  added: boolean;
  label?: string;
  height?: number;
}
