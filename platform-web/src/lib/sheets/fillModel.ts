import type { FillDisplayRow, FillDoc, FillLimits, FillOp, FillRow, FillValue } from "./fillTypes";
import { at, cellKey, cellKind, DEFAULT_ROW_HEIGHT, indexesOf, mergeRects, splitKey } from "./geometry";
import { newId } from "./ids";
import type { DisplayRow, GridCell, GridColumn, GridDoc, GridLine } from "./types";
import { TEXT_UNSAFE, unsafeText, valueProblem } from "./values";

/**
 * The operations on the cells of a fill-in, as pure functions (Functional track FF1, ADR-0192): each takes the sheet the
 * fill-in was made on and the fill-in, and gives a new fill-in or the reason it was refused, in words for a person. The
 * rules are the server's (the same ones, for the person's convenience): only an input cell takes a value, each value
 * fits the type of its column, and rows can be added only where the sheet allows it. The server decides again on every
 * save; nothing here is a permission.
 */

const NOT_INPUT = "This cell is fixed text and cannot be typed into.";

const covered = new WeakMap<GridDoc, ReadonlySet<string>>();

/** The cells of the body that a merged area covers without being its top left cell: they hold nothing. */
function coveredKeys(grid: GridDoc): ReadonlySet<string> {
  const known = covered.get(grid);
  if (known) {
    return known;
  }
  const order: DisplayRow[] = [
    ...grid.headerRows.map((line): DisplayRow => ({ id: line.id, zone: "header", height: DEFAULT_ROW_HEIGHT })),
    ...grid.rows.map((line): DisplayRow => ({ id: line.id, zone: "body", height: DEFAULT_ROW_HEIGHT })),
  ];
  const keys = new Set<string>();
  for (const rect of mergeRects(grid, indexesOf(grid, order))) {
    if (rect.r0 < grid.headerRows.length) {
      continue;
    }
    for (let r = rect.r0; r <= rect.r1; r++) {
      for (let c = rect.c0; c <= rect.c1; c++) {
        if (r !== rect.r0 || c !== rect.c0) {
          keys.add(cellKey(at(order, r).id, at(grid.columns, c).id));
        }
      }
    }
  }
  covered.set(grid, keys);
  return keys;
}

/** Whether the sheet names this row. */
export function isNamedRow(grid: GridDoc, rowId: string): boolean {
  return grid.rows.some((line) => line.id === rowId);
}

/** Whether the filler may add rows to this sheet (the rows mode is repeatable, or fixed and repeatable). */
export function canAddRows(grid: GridDoc): boolean {
  return grid.rowsMode !== "FIXED";
}

/**
 * Whether a person may type into the cell of this row and column (ADR-0173 section 1): a body cell is an input cell
 * unless the sheet fixes it or a merged area covers it. A row the filler added has no cell the sheet fixes, a header row
 * never takes a value, and a row or column the sheet does not know takes nothing.
 */
export function isInputCell(grid: GridDoc, rowId: string, columnId: string): boolean {
  if (!grid.columns.some((column) => column.id === columnId) || grid.headerRows.some((line) => line.id === rowId)) {
    return false;
  }
  if (!isNamedRow(grid, rowId)) {
    return true;
  }
  const key = cellKey(rowId, columnId);
  return cellKind("body", grid.cells[key]) === "input" && !coveredKeys(grid).has(key);
}

/** Reads an answer of the API into a fill-in: every part present, nothing unknown kept. */
export function fromApiFill(raw: unknown): FillDoc {
  const rows: FillRow[] = [];
  const source = typeof raw === "object" && raw !== null ? (raw as { rows?: unknown }) : {};
  for (const item of Array.isArray(source.rows) ? source.rows : []) {
    const row = typeof item === "object" && item !== null ? (item as { id?: unknown; cells?: unknown }) : {};
    if (typeof row.id !== "string") {
      continue;
    }
    const cells: Record<string, FillValue> = {};
    const given = typeof row.cells === "object" && row.cells !== null ? (row.cells as Record<string, unknown>) : {};
    for (const [columnId, value] of Object.entries(given)) {
      if (typeof value === "string" || typeof value === "boolean") {
        cells[columnId] = value;
      }
    }
    rows.push({ id: row.id, cells });
  }
  return { formatVersion: 1, rows };
}

/**
 * The fill-in as it is sent and as the server writes it: the rows of the sheet that hold a value, in the sheet's order,
 * then the rows the filler added in the order they were given; a cell with no value is not there. Two fill-ins that
 * mean the same thing have the same text.
 */
export function toApiFill(grid: GridDoc, fill: FillDoc): FillDoc {
  const named = grid.rows
    .map((line) => fill.rows.find((row) => row.id === line.id))
    .filter((row): row is FillRow => row !== undefined && Object.keys(row.cells).length > 0);
  const added = fill.rows.filter((row) => !isNamedRow(grid, row.id));
  return { formatVersion: 1, rows: [...named, ...added].map((row) => ({ id: row.id, cells: { ...row.cells } })) };
}

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sorted);
  }
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([key, item]) => [key, sorted(item)]),
    );
  }
  return value;
}

/** The text of the fill-in with every object's keys in order: used to tell whether there is something to save. */
export function canonicalFillText(grid: GridDoc, fill: FillDoc): string {
  return JSON.stringify(sorted(toApiFill(grid, fill)));
}

/** Whether two fill-ins mean the same thing. */
export function sameFill(grid: GridDoc, a: FillDoc, b: FillDoc): boolean {
  return a === b || canonicalFillText(grid, a) === canonicalFillText(grid, b);
}

/** The rows as they are drawn below the header rows: the rows the sheet names, then the rows the filler added. */
export function fillDisplayRows(grid: GridDoc, fill: FillDoc): FillDisplayRow[] {
  const named = grid.rows.map((line): FillDisplayRow => ({ id: line.id, added: false, label: line.label, height: line.height }));
  const added = fill.rows
    .filter((row) => !isNamedRow(grid, row.id))
    .map((row): FillDisplayRow => ({ id: row.id, added: true }));
  return [...named, ...added];
}

/** The rows of the grid as drawn: the header rows of the sheet, then {@link fillDisplayRows}. */
export function gridDisplayRows(grid: GridDoc, fill: FillDoc): DisplayRow[] {
  const header = grid.headerRows.map(
    (line): DisplayRow => ({ id: line.id, zone: "header", height: line.height ?? DEFAULT_ROW_HEIGHT, label: line.label }),
  );
  const body = fillDisplayRows(grid, fill).map(
    (row): DisplayRow => ({ id: row.id, zone: "body", height: row.height ?? DEFAULT_ROW_HEIGHT, label: row.label }),
  );
  return [...header, ...body];
}

/**
 * The sheet with the fill-in laid over it, as a document the grid can draw: the rows the filler added are rows of it, the
 * values of the fill-in are the values of input cells, and what the sheet fixes stays as it is. A value an author left in
 * an input cell of the sheet is not the fill-in's value and is not drawn.
 */
export function displayDoc(grid: GridDoc, fill: FillDoc): GridDoc {
  const named = new Set(grid.rows.map((line) => line.id));
  const added: GridLine[] = fill.rows.filter((row) => !named.has(row.id)).map((row) => ({ id: row.id }));
  const cells: Record<string, GridCell> = {};
  for (const [key, cell] of Object.entries(grid.cells)) {
    const [rowId] = splitKey(key);
    if (!named.has(rowId) || cellKind("body", cell) === "fixed") {
      cells[key] = cell;
    }
  }
  for (const row of fill.rows) {
    for (const [columnId, value] of Object.entries(row.cells)) {
      cells[cellKey(row.id, columnId)] = { value, kind: "input" };
    }
  }
  return { ...grid, rows: [...grid.rows, ...added], cells };
}

/** "yes" and "no" in the ways people write them. */
export function parseYesNo(text: string): boolean | null {
  switch (text.trim().toLowerCase()) {
    case "yes":
    case "y":
    case "true":
    case "1":
      return true;
    case "no":
    case "n":
    case "false":
    case "0":
      return false;
    default:
      return null;
  }
}

type Parsed = { ok: true; value: FillValue | null } | { ok: false; error: string };

/** What typed text means in a column of this type, or why it is refused. Empty text is "no value". */
export function parseCell(column: GridColumn, text: string, limits: FillLimits): Parsed {
  if (text.trim() === "") {
    return { ok: true, value: null };
  }
  if (text.length > limits.maxCellChars) {
    return { ok: false, error: `A text is longer than the allowed ${limits.maxCellChars} characters.` };
  }
  if (unsafeText(text)) {
    return { ok: false, error: TEXT_UNSAFE };
  }
  let value: FillValue = text;
  if (column.type === "boolean") {
    const answer = parseYesNo(text);
    if (answer === null) {
      return { ok: false, error: "Type yes or no." };
    }
    value = answer;
  } else if (column.type === "picklist") {
    const values = column.picklist?.values ?? [];
    value = values.find((item) => item === text) ?? values.find((item) => item.toLowerCase() === text.trim().toLowerCase()) ?? text;
  } else if (column.type === "number" || column.type === "date") {
    value = text.trim();
  }
  const problem = valueProblem(column, value);
  return problem ? { ok: false, error: problem } : { ok: true, value };
}

function withValue(fill: FillDoc, grid: GridDoc, rowId: string, columnId: string, value: FillValue | null): FillDoc {
  const existing = fill.rows.find((row) => row.id === rowId);
  if (!existing) {
    return value === null ? fill : { ...fill, rows: [...fill.rows, { id: rowId, cells: { [columnId]: value } }] };
  }
  const cells = { ...existing.cells };
  if (value === null) {
    delete cells[columnId];
  } else {
    cells[columnId] = value;
  }
  const keep = Object.keys(cells).length > 0 || !isNamedRow(grid, rowId);
  const rows = fill.rows.flatMap((row) => (row.id !== rowId ? [row] : keep ? [{ ...row, cells }] : []));
  return { ...fill, rows };
}

/** Types text into a cell. The cell must be an input cell of a row that exists; text that means nothing clears it. */
export function setFillValue(grid: GridDoc, fill: FillDoc, rowId: string, columnId: string, text: string, limits: FillLimits): FillOp {
  const column = grid.columns.find((item) => item.id === columnId);
  if (!column) {
    return { ok: false, error: "This column does not exist." };
  }
  if (!isNamedRow(grid, rowId) && !fill.rows.some((row) => row.id === rowId)) {
    return { ok: false, error: "This row does not exist." };
  }
  if (!isInputCell(grid, rowId, columnId)) {
    return { ok: false, error: NOT_INPUT };
  }
  const parsed = parseCell(column, text, limits);
  return parsed.ok ? { ok: true, fill: withValue(fill, grid, rowId, columnId, parsed.value) } : { ok: false, error: parsed.error };
}

/** Empties the input cells of these rows and columns; cells the sheet fixes are left as they are. */
export function clearFillCells(grid: GridDoc, fill: FillDoc, rowIds: readonly string[], columnIds: readonly string[]): FillDoc {
  let next = fill;
  for (const rowId of rowIds) {
    for (const columnId of columnIds) {
      if (isInputCell(grid, rowId, columnId)) {
        next = withValue(next, grid, rowId, columnId, null);
      }
    }
  }
  return next;
}

/** A new row for the filler: each column starts with its default, where it has one. */
export function addFillRow(grid: GridDoc, fill: FillDoc, limits: FillLimits): FillOp {
  if (!canAddRows(grid)) {
    return { ok: false, error: "The rows of this sheet are fixed: a row cannot be added." };
  }
  if (grid.rows.length + fill.rows.filter((row) => !isNamedRow(grid, row.id)).length >= limits.maxRows) {
    return { ok: false, error: `There are already the most rows allowed, ${limits.maxRows}.` };
  }
  const taken = new Set<string>([
    ...grid.columns.map((column) => column.id),
    ...grid.headerRows.map((line) => line.id),
    ...grid.rows.map((line) => line.id),
    ...fill.rows.map((row) => row.id),
  ]);
  const cells: Record<string, FillValue> = {};
  for (const column of grid.columns) {
    if (column.default !== undefined && column.default !== null && column.default !== "") {
      cells[column.id] = column.default;
    }
  }
  return { ok: true, fill: { ...fill, rows: [...fill.rows, { id: newId("r", taken), cells }] } };
}

/** Takes a row the filler added out. A row the sheet names cannot be removed. */
export function removeFillRow(grid: GridDoc, fill: FillDoc, rowId: string): FillOp {
  if (isNamedRow(grid, rowId)) {
    return { ok: false, error: "A row of the sheet cannot be removed." };
  }
  if (!fill.rows.some((row) => row.id === rowId)) {
    return { ok: false, error: "This row does not exist." };
  }
  return { ok: true, fill: { ...fill, rows: fill.rows.filter((row) => row.id !== rowId) } };
}

/** What a paste came to. */
export interface FillPaste {
  fill: FillDoc;
  applied: number;
  skipped: number;
  problems: string[];
}

/**
 * Pastes a block of text from the first cell it lands on. It fills only input cells; a cell the sheet fixes, a value that
 * does not fit its column and a place past the last column are skipped and counted. Rows past the last row are added
 * where the sheet lets the filler add rows.
 */
export function pasteFill(
  grid: GridDoc,
  fill: FillDoc,
  rows: readonly DisplayRow[],
  row: number,
  column: number,
  matrix: readonly (readonly string[])[],
  limits: FillLimits,
): FillPaste {
  let next = fill;
  let applied = 0;
  let skipped = 0;
  const problems = new Set<string>();
  let shown = [...rows];
  matrix.forEach((line, dr) => {
    const position = row + dr;
    if (position >= shown.length && canAddRows(grid)) {
      const grown = addFillRow(grid, next, limits);
      if (grown.ok) {
        next = grown.fill;
        shown = gridDisplayRows(grid, next);
      } else {
        problems.add(grown.error);
      }
    }
    const target = shown[position];
    line.forEach((text, dc) => {
      const columnAt = grid.columns[column + dc];
      if (!target || target.zone === "header" || !columnAt) {
        skipped++;
        return;
      }
      const result = setFillValue(grid, next, target.id, columnAt.id, text, limits);
      if (result.ok) {
        next = result.fill;
        applied++;
      } else {
        skipped++;
        problems.add(result.error);
      }
    });
  });
  return { fill: next, applied, skipped, problems: [...problems] };
}

/** A required value that is not there yet: the row and the column. */
export interface Missing {
  rowId: string;
  columnId: string;
}

/**
 * What is still to fill in before the fill-in can be sent for review (ADR-0192): a value in every required column of
 * every row, the rows of the sheet that hold nothing included, and at least one row when the filler adds all of them. A
 * cell the sheet fixes is never asked for. A hint only: the server asks again when the fill-in is sent.
 */
export function missingRequired(grid: GridDoc, fill: FillDoc): Missing[] {
  const out: Missing[] = [];
  const rowIds = [...grid.rows.map((line) => line.id), ...fill.rows.filter((row) => !isNamedRow(grid, row.id)).map((row) => row.id)];
  for (const column of grid.columns) {
    if (!column.required) {
      continue;
    }
    for (const rowId of rowIds) {
      const given = fill.rows.find((row) => row.id === rowId)?.cells[column.id];
      if (given === undefined && isInputCell(grid, rowId, column.id)) {
        out.push({ rowId, columnId: column.id });
      }
    }
  }
  return out;
}

/**
 * The cell a place of the server's answer is about. A place is `cells.rows[2].cells.<column>` (the third row of the
 * document that was sent) or `cells.rows.<row>.cells.<column>` (a row of the sheet that holds nothing yet); `sent` is the
 * fill-in that was sent, because a position counts in the document as the server received it.
 */
export function cellOfPlace(grid: GridDoc, sent: FillDoc, place: string): Missing | null {
  const indexed = /^cells\.rows\[(\d+)\]\.cells\.([A-Za-z0-9_-]+)$/.exec(place);
  if (indexed) {
    const row = toApiFill(grid, sent).rows[Number(indexed[1])];
    return row && indexed[2] ? { rowId: row.id, columnId: indexed[2] } : null;
  }
  const named = /^cells\.rows\.([A-Za-z0-9_-]+)\.cells\.([A-Za-z0-9_-]+)$/.exec(place);
  return named?.[1] && named[2] ? { rowId: named[1], columnId: named[2] } : null;
}

/**
 * Says where a place of the server's answer is, for a person: "Row 3, Temperature". A row is named by its label when the
 * sheet gives it one, else by its position among the rows drawn; a place that is not a cell is shown as it is.
 */
export function explainFillPlace(grid: GridDoc, sent: FillDoc, place: string): string {
  const cell = cellOfPlace(grid, sent, place);
  if (!cell) {
    return place === "cells" ? "The fill-in" : place.replace(/^cells\./, "");
  }
  const rows = fillDisplayRows(grid, sent);
  const at = rows.findIndex((row) => row.id === cell.rowId);
  const row = rows[at];
  const column = grid.columns.find((item) => item.id === cell.columnId);
  const rowName = row?.label || (at >= 0 ? `Row ${at + 1}` : "A row");
  return `${rowName}, ${column?.label || "a column"}`;
}

/** The server's refusals gathered by the cell each is about, for the form view; the rest are returned apart. */
export function problemsByCell(
  grid: GridDoc,
  sent: FillDoc,
  problems: readonly { place: string; message: string }[],
): { cells: Map<string, string[]>; other: { place: string; message: string }[] } {
  const cells = new Map<string, string[]>();
  const other: { place: string; message: string }[] = [];
  for (const problem of problems) {
    const cell = cellOfPlace(grid, sent, problem.place);
    if (!cell) {
      other.push(problem);
      continue;
    }
    const key = `${cell.rowId}:${cell.columnId}`;
    cells.set(key, [...(cells.get(key) ?? []), problem.message]);
  }
  return { cells, other };
}

/** Whether the filler adds every row and there is none yet. */
export function needsARow(grid: GridDoc, fill: FillDoc): boolean {
  return grid.dimension === 1 && fill.rows.length === 0;
}
