import {
  at,
  BLANK_ROWS,
  cellKey,
  cellKind,
  contains,
  displayRows,
  indexesOf,
  mergeRects,
  overlaps,
  splitKey,
  usedIds,
  zoneOfRow,
} from "./geometry";
import { newId } from "./ids";
import {
  COLUMN_TYPES,
  type CellKind,
  type CellStyle,
  type DisplayRow,
  type FontSize,
  type GridCell,
  type GridColumn,
  type GridDoc,
  type GridLine,
  type OpResult,
  type Rect,
  type RowsMode,
  type SheetLimits,
} from "./types";
import { TEXT_UNSAFE, unsafeText, valueProblem } from "./values";

/**
 * The edit operations of a grid (FA3, ADR-0172, ADR-0186): pure functions from a document to a new document, or to the
 * reason in plain words why the edit is not allowed. Nothing here touches the page; a person's click, key or paste is
 * turned into one of these calls, so the rules are tested without a browser. The server checks every rule again on
 * every save: these exist so that the person is told at once.
 *
 * <p>A document is never changed in place. An operation that changes nothing structural shares what it does not touch
 * with the document it was given, which is what makes undo cheap (the history keeps documents, not patches).
 */

export const STYLE_NEEDS_TEXT = "Only fixed text can be bold, italic or a size. Type the text first.";
export const BLANK_ROW_NOTE = "The blank rows show what a filler will get. They cannot be changed.";

const ok = (doc: GridDoc, note?: string): OpResult => ({ ok: true, doc, note });
const fail = (error: string): OpResult => ({ ok: false, error });

// ---- cells ----

/**
 * Gives a cell its one form, the same the server writes: an empty text is nothing; a header cell is always fixed; a body
 * cell is fixed when it has text or says so, and is then stored without a kind when the text makes it clear; an input
 * cell carries no style; a cell that holds nothing is not stored. Returns null for a cell that need not be stored.
 */
export function tidyCell(cell: GridCell | undefined, header: boolean): GridCell | null {
  if (!cell) {
    return null;
  }
  const value = cell.value === "" ? null : (cell.value ?? null);
  const style = tidyStyle(cell.style);
  const fixed = header || cell.kind === "fixed" || (cell.kind === undefined && value !== null);
  if (fixed) {
    if (value !== null) {
      return style ? { value, style } : { value };
    }
    if (header) {
      return style ? { style } : null;
    }
    return style ? { kind: "fixed", style } : { kind: "fixed" };
  }
  return value === null ? null : { value, kind: "input" };
}

function tidyStyle(style: CellStyle | undefined): CellStyle | undefined {
  if (!style) {
    return undefined;
  }
  const out: CellStyle = {};
  if (style.bold) {
    out.bold = true;
  }
  if (style.italic) {
    out.italic = true;
  }
  if (style.fontSize && style.fontSize !== "NORMAL") {
    out.fontSize = style.fontSize;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** A document with these cells changed; a null deletes the cell. The cell map is copied only when something changes. */
function withCells(doc: GridDoc, changes: Record<string, GridCell | null>): GridDoc {
  const keys = Object.keys(changes);
  if (keys.length === 0) {
    return doc;
  }
  const cells = { ...doc.cells };
  for (const key of keys) {
    const cell = changes[key];
    if (cell === null) {
      delete cells[key];
    } else {
      cells[key] = cell as GridCell;
    }
  }
  return { ...doc, cells };
}

/** The keys of the cells in a rectangle that can be changed: not covered by a merge, not a blank sample row. */
export function changeableKeys(doc: GridDoc, rows: DisplayRow[], rect: Rect): string[] {
  const indexes = indexesOf(doc, rows);
  const rects = mergeRects(doc, indexes);
  const keys: string[] = [];
  for (let r = rect.r0; r <= rect.r1 && r < rows.length; r++) {
    if (at(rows, r).zone === "blank") {
      continue;
    }
    for (let c = rect.c0; c <= rect.c1 && c < doc.columns.length; c++) {
      const covered = rects.some((m) => contains(m, r, c) && !(m.r0 === r && m.c0 === c));
      if (!covered) {
        keys.push(cellKey(at(rows, r).id, at(doc.columns, c).id));
      }
    }
  }
  return keys;
}

/** Whether the cell is under a merged area but is not its top left cell: such a cell holds nothing and takes nothing. */
function isCovered(doc: GridDoc, rowId: string, columnId: string): boolean {
  const rows = displayRows(doc);
  const indexes = indexesOf(doc, rows);
  const row = indexes.rowPos.get(rowId);
  const column = indexes.colPos.get(columnId);
  if (row === undefined || column === undefined) {
    return false;
  }
  return mergeRects(doc, indexes).some((m) => contains(m, row, column) && !(m.r0 === row && m.c0 === column));
}

const COVERED = "This cell is covered by a merged area. Change the top left cell of the area.";

function checkText(text: string, limits: SheetLimits): string | null {
  if (unsafeText(text)) {
    return TEXT_UNSAFE;
  }
  if (text.length > limits.maxCellChars) {
    return `A text is longer than the allowed ${limits.maxCellChars} characters.`;
  }
  return null;
}

/** The cell after its text is set: it is fixed text now, or, if the text is empty, a blank input cell again. */
function textedCell(old: GridCell | undefined, text: string, header: boolean): GridCell | null {
  if (text !== "") {
    return tidyCell({ value: text, style: old?.style, kind: "fixed" }, header);
  }
  const keepsStyle = tidyStyle(old?.style) !== undefined;
  return tidyCell(keepsStyle ? { style: old?.style, kind: "fixed" } : undefined, header);
}

/** A person typed the text into a cell: fixed text in the author's grid. */
export function setCellText(doc: GridDoc, rowId: string, columnId: string, text: string, limits: SheetLimits): OpResult {
  const zone = zoneOfRow(doc, rowId);
  if (!zone) {
    return fail(BLANK_ROW_NOTE);
  }
  if (!doc.columns.some((column) => column.id === columnId)) {
    return fail("This column does not exist.");
  }
  if (isCovered(doc, rowId, columnId)) {
    return fail(COVERED);
  }
  const problem = checkText(text, limits);
  if (problem) {
    return fail(problem);
  }
  const key = cellKey(rowId, columnId);
  return ok(withCells(doc, { [key]: textedCell(doc.cells[key], text, zone === "header") }));
}

/** Clears the text of every cell in the rectangle that can be changed (the Delete key). */
export function clearCells(doc: GridDoc, rows: DisplayRow[], rect: Rect): OpResult {
  const changes: Record<string, GridCell | null> = {};
  for (const key of changeableKeys(doc, rows, rect)) {
    const [rowId] = splitKey(key);
    const old = doc.cells[key];
    if (old?.value != null) {
      changes[key] = textedCell(old, "", zoneOfRow(doc, rowId) === "header");
    }
  }
  return ok(withCells(doc, changes));
}

/** Whether the cell is fixed text now (a header cell always is). */
function isFixedKey(doc: GridDoc, key: string): boolean {
  const [rowId] = splitKey(key);
  const zone = zoneOfRow(doc, rowId);
  return zone !== undefined && cellKind(zone, doc.cells[key]) === "fixed";
}

function changeStyle(doc: GridDoc, keys: string[], change: (style: CellStyle) => CellStyle): OpResult {
  if (keys.length === 0) {
    return fail("Select a cell first.");
  }
  if (keys.some((key) => isCovered(doc, ...splitKey(key)))) {
    return fail(COVERED);
  }
  if (!keys.every((key) => isFixedKey(doc, key))) {
    return fail(STYLE_NEEDS_TEXT);
  }
  const changes: Record<string, GridCell | null> = {};
  for (const key of keys) {
    const [rowId] = splitKey(key);
    const old = doc.cells[key];
    const header = zoneOfRow(doc, rowId) === "header";
    changes[key] = tidyCell({ ...old, style: change({ ...old?.style }), kind: old?.kind ?? "fixed" }, header);
  }
  return ok(withCells(doc, changes));
}

/** Bold or italic on every cell of the selection: all of them go on, unless all of them already were. */
export function toggleStyle(doc: GridDoc, keys: string[], property: "bold" | "italic"): OpResult {
  const allOn = keys.length > 0 && keys.every((key) => doc.cells[key]?.style?.[property] === true);
  return changeStyle(doc, keys, (style) => ({ ...style, [property]: !allOn }));
}

/** One of the four named sizes on every cell of the selection. */
export function setFontSize(doc: GridDoc, keys: string[], size: FontSize): OpResult {
  return changeStyle(doc, keys, (style) => ({ ...style, fontSize: size }));
}

/** Makes a body cell fixed text (an empty one, if it holds nothing) or a cell the filler types into. */
export function setCellKind(doc: GridDoc, rowId: string, columnId: string, kind: CellKind): OpResult {
  const zone = zoneOfRow(doc, rowId);
  if (!zone) {
    return fail(BLANK_ROW_NOTE);
  }
  if (zone === "header") {
    return fail("A header cell is always fixed text.");
  }
  if (isCovered(doc, rowId, columnId)) {
    return fail(COVERED);
  }
  const key = cellKey(rowId, columnId);
  const old = doc.cells[key];
  if (kind === "input") {
    return ok(withCells(doc, { [key]: null }));
  }
  return ok(withCells(doc, { [key]: tidyCell({ ...old, kind: "fixed" }, false) }));
}

// ---- merging ----

export function mergeRect(doc: GridDoc, rows: DisplayRow[], rect: Rect, limits: SheetLimits): OpResult {
  if (rect.r0 === rect.r1 && rect.c0 === rect.c1) {
    return fail("A merged area needs more than one cell.");
  }
  const zone = rows[rect.r0]?.zone;
  for (let r = rect.r0; r <= rect.r1; r++) {
    if (at(rows, r).zone !== zone) {
      return fail("A merged area cannot join header rows and body rows.");
    }
  }
  if (zone === "blank") {
    return fail(BLANK_ROW_NOTE);
  }
  const indexes = indexesOf(doc, rows);
  if (mergeRects(doc, indexes).some((m) => overlaps(m, rect))) {
    return fail("Two merged areas cannot cover the same cell.");
  }
  if (doc.merges.length >= limits.maxMerges) {
    return fail(`A sheet can have at most ${limits.maxMerges} merged areas.`);
  }
  const from = cellKey(at(rows, rect.r0).id, at(doc.columns, rect.c0).id);
  const to = cellKey(at(rows, rect.r1).id, at(doc.columns, rect.c1).id);
  // Like a spreadsheet application: only the top left cell keeps what it holds.
  const dropped: Record<string, null> = {};
  for (let r = rect.r0; r <= rect.r1; r++) {
    for (let c = rect.c0; c <= rect.c1; c++) {
      const key = cellKey(at(rows, r).id, at(doc.columns, c).id);
      if (key !== from && doc.cells[key]) {
        dropped[key] = null;
      }
    }
  }
  const lost = Object.keys(dropped).length;
  const merged = withCells(doc, dropped);
  return ok(
    { ...merged, merges: [...merged.merges, { from, to }] },
    lost > 0 ? "Only the top left cell keeps its content." : undefined,
  );
}

export function unmergeAt(doc: GridDoc, rows: DisplayRow[], row: number, column: number): OpResult {
  const hit = mergeRects(doc, indexesOf(doc, rows)).find((m) => contains(m, row, column));
  if (!hit) {
    return fail("There is no merged area here.");
  }
  return ok({ ...doc, merges: doc.merges.filter((m) => m.from !== hit.key) });
}

// ---- paste ----

/** A block copied from a spreadsheet application: a tab between cells, a line break between rows. */
export function parseClipboard(text: string): string[][] {
  const clean = text.replace(/\r\n?/g, "\n").replace(/\n$/, "");
  return clean.split("\n").map((line) => line.split("\t"));
}

export interface PasteResult {
  doc: GridDoc;
  applied: number;
  skipped: number;
  problems: string[];
}

/**
 * Writes a pasted block as fixed text from the cell, one cell at a time. A cell that is covered by a merged area, in a
 * blank sample row, outside the grid, or whose text is refused, is skipped and counted; the rest is written. The whole
 * paste is one step of the history.
 */
export function pasteBlock(
  doc: GridDoc,
  rows: DisplayRow[],
  startRow: number,
  startColumn: number,
  matrix: string[][],
  limits: SheetLimits,
): PasteResult {
  const rects = mergeRects(doc, indexesOf(doc, rows));
  const changes: Record<string, GridCell | null> = {};
  const problems = new Set<string>();
  let applied = 0;
  let skipped = 0;
  matrix.forEach((line, i) => {
    line.forEach((text, j) => {
      const r = startRow + i;
      const c = startColumn + j;
      if (r >= rows.length || c >= doc.columns.length || at(rows, r).zone === "blank") {
        skipped++;
        return;
      }
      if (rects.some((m) => contains(m, r, c) && !(m.r0 === r && m.c0 === c))) {
        skipped++;
        return;
      }
      const problem = checkText(text, limits);
      if (problem) {
        problems.add(problem);
        skipped++;
        return;
      }
      const key = cellKey(at(rows, r).id, at(doc.columns, c).id);
      changes[key] = textedCell(changes[key] ?? doc.cells[key], text, at(rows, r).zone === "header");
      applied++;
    });
  });
  return { doc: withCells(doc, changes), applied, skipped, problems: [...problems] };
}

// ---- columns ----

function mergeTouching(doc: GridDoc, rows: DisplayRow[], touches: (rect: Rect) => boolean): boolean {
  return mergeRects(doc, indexesOf(doc, rows)).some(touches);
}

const MERGED_COLUMN = "This column is part of a merged area. Unmerge the cells first.";
const MERGED_ROW = "This row is part of a merged area. Unmerge the cells first.";

function insertAfter<T extends { id: string }>(list: T[], afterId: string | null, item: T): T[] {
  const at = afterId === null ? list.length : list.findIndex((entry) => entry.id === afterId) + 1;
  return [...list.slice(0, at), item, ...list.slice(at)];
}

function label(prefix: string, taken: GridLine[] | GridColumn[]): string {
  return `${prefix} ${taken.length + 1}`;
}

/** A new text column after the column named (or at the end). */
export function addColumn(doc: GridDoc, afterId: string | null, limits: SheetLimits): OpResult {
  if (doc.columns.length >= limits.maxColumns) {
    return fail(`A sheet can have at most ${limits.maxColumns} columns.`);
  }
  const column: GridColumn = { id: newId("c", usedIds(doc)), label: label("Column", doc.columns), type: "text" };
  return ok({ ...doc, columns: insertAfter(doc.columns, afterId, column) });
}

export function removeColumn(doc: GridDoc, columnId: string): OpResult {
  const position = doc.columns.findIndex((column) => column.id === columnId);
  if (position < 0) {
    return fail("This column does not exist.");
  }
  if (doc.columns.length === 1) {
    return fail("A sheet needs at least one column.");
  }
  if (mergeTouching(doc, displayRows(doc), (rect) => rect.c0 <= position && rect.c1 >= position)) {
    return fail(MERGED_COLUMN);
  }
  const cells = { ...doc.cells };
  for (const key of Object.keys(cells)) {
    if (splitKey(key)[1] === columnId) {
      delete cells[key];
    }
  }
  return ok({ ...doc, columns: doc.columns.filter((column) => column.id !== columnId), cells });
}

export function moveColumn(doc: GridDoc, columnId: string, delta: -1 | 1): OpResult {
  const from = doc.columns.findIndex((column) => column.id === columnId);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= doc.columns.length) {
    return fail("The column cannot move further.");
  }
  if (mergeTouching(doc, displayRows(doc), (rect) => [from, to].some((p) => rect.c0 <= p && rect.c1 >= p))) {
    return fail(MERGED_COLUMN);
  }
  const columns = [...doc.columns];
  const swapped = [at(columns, to), at(columns, from)];
  columns[from] = swapped[0] as GridColumn;
  columns[to] = swapped[1] as GridColumn;
  return ok({ ...doc, columns });
}

export type ColumnChange = Partial<Pick<GridColumn, "label" | "type" | "required" | "default" | "picklist" | "width">>;

/**
 * Changes what a column is called or holds. A new type drops the default and the list that no longer fit, and clears
 * the pre-filled cells of the column that no longer fit the type (the result says how many).
 */
export function updateColumn(doc: GridDoc, columnId: string, change: ColumnChange, limits: SheetLimits): OpResult {
  const old = doc.columns.find((column) => column.id === columnId);
  if (!old) {
    return fail("This column does not exist.");
  }
  if (change.label !== undefined) {
    if (unsafeText(change.label)) {
      return fail(TEXT_UNSAFE);
    }
    if (change.label.length > limits.maxLabelChars) {
      return fail(`A label is longer than the allowed ${limits.maxLabelChars} characters.`);
    }
  }
  if (change.type !== undefined && !COLUMN_TYPES.includes(change.type)) {
    return fail("This kind of column does not exist.");
  }
  if (change.picklist?.values && change.picklist.values.length > limits.maxPicklistValues) {
    return fail(`A list can have at most ${limits.maxPicklistValues} values.`);
  }
  let next: GridColumn = { ...old, ...change };
  if (change.type !== undefined && change.type !== old.type) {
    next = { ...next, default: undefined };
    next.picklist = change.type === "picklist" ? (change.picklist ?? { values: [] }) : undefined;
  }
  if (next.default !== undefined && next.default !== null && valueProblem(next, next.default) !== null) {
    next = { ...next, default: undefined };
  }
  next = tidyColumn(next);
  let cleared = 0;
  const changes: Record<string, GridCell | null> = {};
  for (const [key, cell] of Object.entries(doc.cells)) {
    const [rowId, cellColumn] = splitKey(key);
    if (cellColumn === columnId && zoneOfRow(doc, rowId) === "body" && cell.kind === "input" && cell.value != null) {
      if (valueProblem(next, cell.value) !== null) {
        changes[key] = null;
        cleared++;
      }
    }
  }
  const columns = doc.columns.map((column) => (column.id === columnId ? next : column));
  return ok(
    withCells({ ...doc, columns }, changes),
    cleared > 0 ? `${cleared} pre-filled cell${cleared === 1 ? "" : "s"} no longer fit and ${cleared === 1 ? "was" : "were"} cleared.` : undefined,
  );
}

/** A column without the parts that mean nothing: no `required: false`, no empty default, no list on a plain column. */
function tidyColumn(column: GridColumn): GridColumn {
  const out: GridColumn = { id: column.id, label: column.label, type: column.type };
  if (column.required) {
    out.required = true;
  }
  if (column.default !== undefined && column.default !== null && column.default !== "") {
    out.default = column.default;
  }
  if (column.type === "picklist") {
    out.picklist = { values: column.picklist?.values ?? [] };
  }
  if (column.width !== undefined) {
    out.width = column.width;
  }
  return out;
}

// ---- rows ----

type RowZone = "header" | "body";

function linesOf(doc: GridDoc, zone: RowZone): GridLine[] {
  return zone === "header" ? doc.headerRows : doc.rows;
}

function withLines(doc: GridDoc, zone: RowZone, lines: GridLine[]): GridDoc {
  return zone === "header" ? { ...doc, headerRows: lines } : { ...doc, rows: lines };
}

/** A new header row or named row after the one named (or at the end). */
export function addRow(doc: GridDoc, zone: RowZone, afterId: string | null, limits: SheetLimits): OpResult {
  if (zone === "body" && doc.dimension === 1) {
    return fail("The filler adds the rows of this sheet; it names none.");
  }
  if (zone === "body" && doc.rows.length >= limits.maxRows) {
    return fail(`A sheet can have at most ${limits.maxRows} rows.`);
  }
  if (zone === "header" && doc.headerRows.length >= limits.maxHeaderRows) {
    return fail(`A sheet can have at most ${limits.maxHeaderRows} header rows.`);
  }
  const lines = linesOf(doc, zone);
  const line: GridLine = { id: newId(zone === "header" ? "h" : "r", usedIds(doc)) };
  if (zone === "body") {
    line.label = label("Row", lines);
  }
  return ok(withLines(doc, zone, insertAfter(lines, afterId, line)));
}

export function removeRow(doc: GridDoc, rowId: string): OpResult {
  const zone = zoneOfRow(doc, rowId);
  if (!zone) {
    return fail("This row does not exist.");
  }
  const rows = displayRows(doc);
  const position = rows.findIndex((row) => row.id === rowId);
  if (mergeTouching(doc, rows, (rect) => rect.r0 <= position && rect.r1 >= position)) {
    return fail(MERGED_ROW);
  }
  const cells = { ...doc.cells };
  for (const key of Object.keys(cells)) {
    if (splitKey(key)[0] === rowId) {
      delete cells[key];
    }
  }
  return ok({ ...withLines(doc, zone, linesOf(doc, zone).filter((line) => line.id !== rowId)), cells });
}

export function moveRow(doc: GridDoc, rowId: string, delta: -1 | 1): OpResult {
  const zone = zoneOfRow(doc, rowId);
  if (!zone) {
    return fail("This row does not exist.");
  }
  const lines = [...linesOf(doc, zone)];
  const from = lines.findIndex((line) => line.id === rowId);
  const to = from + delta;
  if (to < 0 || to >= lines.length) {
    return fail("The row cannot move further.");
  }
  const rows = displayRows(doc);
  const positions = [from, to].map((p) => rows.findIndex((row) => row.id === at(lines, p).id));
  if (mergeTouching(doc, rows, (rect) => positions.some((p) => rect.r0 <= p && rect.r1 >= p))) {
    return fail(MERGED_ROW);
  }
  const swappedLines = [at(lines, to), at(lines, from)];
  lines[from] = swappedLines[0] as GridLine;
  lines[to] = swappedLines[1] as GridLine;
  return ok(withLines(doc, zone, lines));
}

/** Changes the label or the height of a row. An empty label is nothing. */
export function updateRow(
  doc: GridDoc,
  rowId: string,
  change: Partial<Pick<GridLine, "label" | "height">>,
  limits: SheetLimits,
): OpResult {
  const zone = zoneOfRow(doc, rowId);
  if (!zone) {
    return fail("This row does not exist.");
  }
  if (change.label !== undefined) {
    if (unsafeText(change.label)) {
      return fail(TEXT_UNSAFE);
    }
    if (change.label.length > limits.maxLabelChars) {
      return fail(`A label is longer than the allowed ${limits.maxLabelChars} characters.`);
    }
  }
  const lines = linesOf(doc, zone).map((line) => {
    if (line.id !== rowId) {
      return line;
    }
    const next: GridLine = { id: line.id };
    const labelText = change.label !== undefined ? change.label : line.label;
    if (labelText) {
      next.label = labelText;
    }
    const height = "height" in change ? change.height : line.height;
    if (height !== undefined) {
      next.height = height;
    }
    return next;
  });
  return ok(withLines(doc, zone, lines));
}

// ---- the shape of the sheet ----

/**
 * Changes how the rows of the sheet come about. The server allows this only while the first version is the only one
 * and is still a draft; the screen offers it only then. Going to one dimension needs a sheet that names no rows.
 */
export function setShape(doc: GridDoc, dimension: 1 | 2, rowsMode?: RowsMode): OpResult {
  if (dimension === 1) {
    if (doc.rows.length > 0) {
      return fail("Remove the named rows first: the filler adds the rows of this sheet.");
    }
    return ok({ ...doc, dimension: 1, rowsMode: "REPEATABLE" });
  }
  const mode: RowsMode = rowsMode === "FIXED_AND_REPEATABLE" ? "FIXED_AND_REPEATABLE" : "FIXED";
  return ok({ ...doc, dimension: 2, rowsMode: mode });
}

// ---- what a selection is, for the toolbar ----

export interface SelectionState {
  /** Cells that can be changed in the selection. */
  keys: string[];
  bold: boolean;
  italic: boolean;
  /** The one size every cell has, or null when they differ. */
  size: FontSize | null;
  /** Fixed, input, or mixed. */
  kind: CellKind | "mixed" | null;
  canMerge: boolean;
  canUnmerge: boolean;
}

export function selectionState(doc: GridDoc, rows: DisplayRow[], rect: Rect): SelectionState {
  const keys = changeableKeys(doc, rows, rect);
  const indexes = indexesOf(doc, rows);
  const rects = mergeRects(doc, indexes);
  const sizes = new Set(keys.map((key) => doc.cells[key]?.style?.fontSize ?? "NORMAL"));
  const kinds = new Set(
    keys.map((key) => {
      const zone = zoneOfRow(doc, splitKey(key)[0]);
      return zone ? cellKind(zone, doc.cells[key]) : "input";
    }),
  );
  const single = rect.r0 === rect.r1 && rect.c0 === rect.c1;
  return {
    keys,
    bold: keys.length > 0 && keys.every((key) => doc.cells[key]?.style?.bold === true),
    italic: keys.length > 0 && keys.every((key) => doc.cells[key]?.style?.italic === true),
    size: sizes.size === 1 ? ([...sizes][0] as FontSize) : null,
    kind: kinds.size === 0 ? null : kinds.size === 1 ? ([...kinds][0] as CellKind) : "mixed",
    canMerge: !single && rows.slice(rect.r0, rect.r1 + 1).every((row) => row.zone !== "blank"),
    canUnmerge: rects.some((m) => overlaps(m, rect)),
  };
}

export { BLANK_ROWS };
