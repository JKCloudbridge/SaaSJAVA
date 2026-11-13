import { cellKey, cellKind } from "../geometry";
import type { DisplayRow, GridDoc } from "../types";
import type { BorderSource } from "./borders";
import { NO_LOOK, readLook, type ResolvedLook } from "./look";

/**
 * What the screen and the print view share about the look of one sheet (SH4): where a cell's look is read from, the thin
 * lines a cell a person types into has when it states no look, whether a document is drawn from its look at all, and the
 * answers of its formulas laid out by cell key.
 */

/** A sheet in format version 2 is drawn from what it states; a sheet in version 1 takes none of the look. */
export function isLooked(doc: GridDoc): boolean {
  return doc.formatVersion === 2 && doc.sheet !== undefined;
}

/** The look a cell a person types into has when it states none: a thin line round it, as the file out draws it (SH3). */
export const INPUT_LINES: ResolvedLook = Object.freeze({
  borders: Object.freeze({ top: "THIN", right: "THIN", bottom: "THIN", left: "THIN" } as const),
  numberFormat: "GENERAL",
}) as ResolvedLook;

/** The look at a display position, as the borders need it: the cell's own, or the lines of an input cell, or none. */
export function lookSource(doc: GridDoc, rows: readonly DisplayRow[]): BorderSource {
  return {
    rows: rows.length,
    columns: doc.columns.length,
    look(r, c) {
      const row = rows[r];
      const column = doc.columns[c];
      if (!row || !column) return undefined;
      const key = cellKey(row.id, column.id);
      const own = readLook(doc.sheet?.cells[key]?.look);
      if (own !== NO_LOOK) return own;
      return row.zone !== "header" && cellKind(row.zone, doc.cells[key]) === "input" ? INPUT_LINES : undefined;
    },
  };
}

/** The answers of the formulas laid out by cell key, with the kind of each (a calculated column is drawn at every blank row). */
export interface AnswerMaps {
  text: Record<string, string>;
  kinds: Record<string, string>;
}

export interface AnswerSource {
  cells: Record<string, string>;
  columns: Record<string, string>;
  kinds: { cells: Record<string, string>; columns: Record<string, string> };
}

export function answerMaps(answers: AnswerSource, rows: readonly DisplayRow[]): AnswerMaps {
  const text: Record<string, string> = { ...answers.cells };
  const kinds: Record<string, string> = { ...answers.kinds.cells };
  for (const row of rows) {
    if (row.zone !== "blank") continue;
    for (const [column, answer] of Object.entries(answers.columns)) {
      text[`${row.id}:${column}`] = answer;
      kinds[`${row.id}:${column}`] = answers.kinds.columns[column] ?? "text";
    }
  }
  return { text, kinds };
}
