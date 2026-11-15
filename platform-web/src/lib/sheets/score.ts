import type { GridDoc, OpResult } from "./types";

/**
 * The cell a template marks as its score (MC2B, ADR-0261): the one formula cell whose answer the platform reads as the
 * percent of a fill-in. The marker is the sheet's own property (`score`, a cell key); the server checks that it names a
 * cell that holds a formula, and calculates the answer. Nothing here calculates or judges a score.
 */
export function scoreCell(doc: GridDoc): string | undefined {
  return doc.formatVersion === 2 ? doc.sheet?.score : undefined;
}

/** Whether this cell is the marked one. */
export function isScore(doc: GridDoc, rowId: string, columnId: string): boolean {
  const marked = scoreCell(doc);
  return marked !== undefined && marked === `${rowId}:${columnId}`;
}

/** Whether the cell may be marked: the sheet is in the new format and the cell holds a formula. */
export function canMarkScore(doc: GridDoc, rowId: string, columnId: string): boolean {
  return doc.formatVersion === 2 && doc.sheet !== undefined && Boolean(doc.sheet.cells[`${rowId}:${columnId}`]?.formula);
}

/** Marks the cell as the score, or takes the mark off it. A sheet has one mark: marking another cell moves it. */
export function setScore(doc: GridDoc, rowId: string, columnId: string, on: boolean): OpResult {
  const sheet = doc.sheet;
  if (doc.formatVersion !== 2 || sheet === undefined) {
    return { ok: false, error: "A score needs the new sheet format." };
  }
  const key = `${rowId}:${columnId}`;
  if (!on) {
    if (sheet.score !== key) return { ok: true, doc };
    const { score: _removed, ...rest } = sheet;
    void _removed;
    return { ok: true, doc: { ...doc, sheet: rest } };
  }
  if (!canMarkScore(doc, rowId, columnId)) {
    return { ok: false, error: "Only a cell that holds a formula can be the score." };
  }
  return { ok: true, doc: { ...doc, sheet: { ...sheet, score: key } } };
}
