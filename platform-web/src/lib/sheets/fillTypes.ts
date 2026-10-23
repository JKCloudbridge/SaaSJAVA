/**
 * The cells of a fill-in as the browser works with them (Functional track FF1, ADR-0173 section 6, ADR-0192). This is the
 * server's own canonical document, shape B (a list of rows, each with its cells keyed by the identifier of the column),
 * read into plain types. The sheet it was made on (a {@link import("./types").GridDoc}) says which cells exist and which a
 * person may type into; the fill-in holds only what was typed.
 */

/** What a cell of a fill-in holds: text (a number and a date travel as text), or yes or no. */
export type FillValue = string | boolean;

/** One row: the identifier of a row of the sheet, or one this browser made for a row the filler added. */
export interface FillRow {
  id: string;
  cells: Record<string, FillValue>;
}

export interface FillDoc {
  formatVersion: 1;
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
