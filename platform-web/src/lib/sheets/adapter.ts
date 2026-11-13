import { splitKey, zoneOfRow } from "./geometry";
import { tidyCell } from "./gridModel";
import {
  COLUMN_TYPES,
  FONT_SIZES,
  ALIGNMENTS,
  TEXT_COLOURS,
  type CellExtras,
  type CellStyle,
  type FontSize,
  type GridCell,
  type GridColumn,
  type GridDoc,
  type GridLine,
  type GridMerge,
  type RowsMode,
  type SheetExtras,
} from "./types";

/**
 * The adapter between what the API sends and what the editor works with (ADR-0172 section 4, ADR-0186). The document is
 * the server's own canonical format, so there is no library format to convert: the adapter reads an answer into a
 * complete, well-typed document (every part present, nothing unknown kept) and writes a document back as the server
 * would write it, so that "has this changed?" is a comparison of two texts and a save of an unchanged document sends
 * exactly what is stored. The server checks everything again and writes its own canonical text.
 */

const ROWS_MODES: readonly RowsMode[] = ["FIXED", "REPEATABLE", "FIXED_AND_REPEATABLE"];

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function scalar(value: unknown): string | boolean | null {
  return typeof value === "string" || typeof value === "boolean" ? value : null;
}

function integer(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

function column(raw: unknown): GridColumn | null {
  const source = asRecord(raw);
  const id = text(source.id);
  const type = COLUMN_TYPES.find((candidate) => candidate === source.type);
  if (id === undefined || type === undefined) {
    return null;
  }
  const out: GridColumn = { id, label: text(source.label) ?? "", type };
  if (source.required === true) {
    out.required = true;
  }
  if (source.reportable === true) {
    out.reportable = true;
  }
  const given = scalar(source.default);
  if (given !== null && given !== "") {
    out.default = given;
  }
  if (type === "picklist") {
    out.picklist = { values: asList(asRecord(source.picklist).values).filter((v): v is string => typeof v === "string") };
  }
  const width = integer(source.width);
  if (width !== undefined) {
    out.width = width;
  }
  return out;
}

function lines(raw: unknown): GridLine[] {
  const out: GridLine[] = [];
  for (const item of asList(raw)) {
    const source = asRecord(item);
    const id = text(source.id);
    if (id === undefined) {
      continue;
    }
    const line: GridLine = { id };
    const label = text(source.label);
    if (label) {
      line.label = label;
    }
    const height = integer(source.height);
    if (height !== undefined) {
      line.height = height;
    }
    out.push(line);
  }
  return out;
}

function style(raw: unknown): CellStyle | undefined {
  const source = asRecord(raw);
  const out: CellStyle = {};
  if (source.bold === true) {
    out.bold = true;
  }
  if (source.italic === true) {
    out.italic = true;
  }
  if (source.underline === true) out.underline = true;
  const alignment = ALIGNMENTS.find((candidate) => candidate === source.alignment);
  const colour = TEXT_COLOURS.find((candidate) => candidate === source.colour);
  if (alignment && alignment !== "LEFT") out.alignment = alignment;
  if (colour && colour !== "DEFAULT") out.colour = colour;
  const size: FontSize | undefined = FONT_SIZES.find((candidate) => candidate === source.fontSize);
  if (size && size !== "NORMAL") {
    out.fontSize = size;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Reads an answer of the API into a document, in the one form the editor and the server agree on. A sheet in format
 * version 2 is read as the editor's document plus `sheet`: the parts the editor does not know, kept exactly as they were
 * (SH1A, ADR-0251). A document the editor itself made (it has `dimension`, not `sheets`) is only tidied.
 */
export function fromApi(raw: unknown): GridDoc {
  const source = asRecord(raw);
  if (source.formatVersion === 2 && Array.isArray(source.sheets)) {
    return fromTwo(source);
  }
  const doc = fromBody(source);
  const carried = asRecord(source.sheet);
  return source.formatVersion === 2 && typeof carried.key === "string"
    ? { ...doc, formatVersion: 2, sheet: source.sheet as SheetExtras }
    : doc;
}

/** The first sheet of a document of format version 2, with what the editor does not know kept beside it. */
function fromTwo(source: Record<string, unknown>): GridDoc {
  const sheets = asList(source.sheets);
  const first = asRecord(sheets[0]);
  const extras: Record<string, CellExtras> = {};
  for (const [key, item] of Object.entries(asRecord(first.cells))) {
    const cell = asRecord(item);
    const extra: CellExtras = {};
    if (Object.keys(asRecord(cell.look)).length > 0) {
      extra.look = asRecord(cell.look);
    }
    if (typeof cell.link === "string") {
      extra.link = cell.link;
    }
    if (typeof cell.formula === "string" && cell.formula !== "") {
      extra.formula = cell.formula;
    }
    if (extra.look || extra.link || extra.formula) {
      extras[key] = extra;
    }
  }
  const columnFormulas: Record<string, string> = {};
  for (const item of asList(first.columns)) {
    const column = asRecord(item);
    if (typeof column.id === "string" && typeof column.formula === "string" && column.formula !== "") {
      columnFormulas[column.id] = column.formula;
    }
  }
  const sheet: SheetExtras = { key: text(first.key) ?? "main", cells: extras };
  if (Object.keys(columnFormulas).length > 0) sheet.columnFormulas = columnFormulas;
  const title = text(first.title);
  if (title !== undefined) sheet.title = title;
  if (first.fillable === false) sheet.fillable = false;
  if (Object.keys(asRecord(first.freeze)).length > 0) sheet.freeze = asRecord(first.freeze);
  if (Object.keys(asRecord(first.print)).length > 0) sheet.print = asRecord(first.print);
  if (Object.keys(asRecord(first.inputs)).length > 0) sheet.inputs = asRecord(first.inputs);
  const conditions = asList(first.conditions).map(asRecord).filter((one) => Object.keys(one).length > 0);
  if (conditions.length > 0) sheet.conditions = conditions;
  if (sheets.length > 1) sheet.otherSheets = sheets.slice(1);
  return { ...fromBody(first), formatVersion: 2, sheet };
}

function fromBody(source: Record<string, unknown>): GridDoc {
  const dimension: 1 | 2 = source.dimension === 1 ? 1 : 2;
  const rowsMode = ROWS_MODES.find((candidate) => candidate === source.rowsMode) ?? (dimension === 1 ? "REPEATABLE" : "FIXED");
  const columns = asList(source.columns).map(column).filter((c): c is GridColumn => c !== null);
  const headerRows = lines(source.headerRows);
  const rows = lines(source.rows);
  const doc: GridDoc = { formatVersion: 1, dimension, rowsMode, columns, headerRows, rows, cells: {}, merges: [] };
  const cells: Record<string, GridCell> = {};
  for (const [key, item] of Object.entries(asRecord(source.cells))) {
    const rawCell = asRecord(item);
    const cell: GridCell = {};
    const value = scalar(rawCell.value);
    if (value !== null) {
      cell.value = value;
    }
    const given = style(rawCell.style);
    if (given) {
      cell.style = given;
    }
    if (rawCell.kind === "fixed" || rawCell.kind === "input") {
      cell.kind = rawCell.kind;
    }
    const zone = zoneOfRow(doc, splitKey(key)[0]);
    const tidy = zone === undefined ? null : tidyCell(cell, zone === "header");
    if (tidy) {
      cells[key] = tidy;
    }
  }
  const merges = asList(source.merges)
    .map((item) => asRecord(item))
    .filter((item) => typeof item.from === "string" && typeof item.to === "string")
    .map((item) => ({ from: item.from as string, to: item.to as string }));
  return { ...doc, cells, merges };
}

/**
 * The document as it is sent: the form `fromApi` reads, with every part present. A sheet that came from format
 * version 2 is sent as format version 2, with the parts the editor does not know put back.
 */
export function toApi(doc: GridDoc): GridDoc | TwoDocument {
  const body = fromApi({ ...doc, formatVersion: 1, sheet: undefined });
  return doc.sheet ? toTwo(body, doc.sheet) : body;
}

/** A document of format version 2 as it is sent. */
export interface TwoDocument {
  formatVersion: 2;
  sheets: Record<string, unknown>[];
}

/**
 * Puts the parts the editor does not know back into the sheet it changed. A part that belongs to a row or a column
 * that no longer exists is dropped, as is a link on a cell that no longer holds fixed text, a stated input cell that is
 * no longer an input cell, and a text colour or size of the look that the author has now stated by a name in the style
 * (the server refuses a cell that states one twice, and the author's own choice is the newer).
 */
function toTwo(body: GridDoc, sheet: SheetExtras): TwoDocument {
  const rowIds = new Set([...body.headerRows, ...body.rows].map((line) => line.id));
  const headerIds = new Set(body.headerRows.map((line) => line.id));
  const bodyRowIds = new Set(body.rows.map((line) => line.id));
  const columnIds = new Set(body.columns.map((column) => column.id));
  const exists = (key: string): boolean => {
    const [rowId, columnId] = splitKey(key);
    return rowIds.has(rowId) && columnIds.has(columnId);
  };
  const cells: Record<string, Record<string, unknown>> = {};
  for (const [key, cell] of Object.entries(body.cells)) {
    cells[key] = { ...cell };
  }
  for (const [key, extra] of Object.entries(sheet.cells)) {
    if (!exists(key)) continue;
    const cell = cells[key] ?? {};
    if (extra.look) {
      const look = { ...extra.look };
      if (body.cells[key]?.style?.colour) delete look.colour;
      if (body.cells[key]?.style?.fontSize) delete look.size;
      if (Object.keys(look).length > 0) cell.look = look;
    }
    const fixedText =
      typeof body.cells[key]?.value === "string" && (headerIds.has(splitKey(key)[0]) || body.cells[key]?.kind !== "input");
    if (extra.link && fixedText) cell.link = extra.link;
    // A cell that holds a formula is a fixed cell with no text of its own (the server refuses anything else).
    if (extra.formula && body.cells[key]?.kind !== "input" && body.cells[key]?.value === undefined) {
      cell.formula = extra.formula;
      delete cell.value;
      if (!headerIds.has(splitKey(key)[0])) cell.kind = "fixed";
    }
    if (Object.keys(cell).length > 0) cells[key] = cell;
  }
  const inputs: Record<string, unknown> = {};
  for (const [key, spec] of Object.entries(sheet.inputs ?? {})) {
    const [rowId] = splitKey(key);
    const held = body.cells[key];
    const typedInto = held === undefined || held.kind === "input" || (held.kind === undefined && held.value === undefined);
    if (exists(key) && bodyRowIds.has(rowId) && typedInto) inputs[key] = spec;
  }
  // A conditional colour whose range lost a row or a column would be refused by the server, so it is dropped with it.
  const conditions = (sheet.conditions ?? []).filter(
    (one) => typeof one.from === "string" && typeof one.to === "string" && exists(one.from) && exists(one.to),
  );
  const out: Record<string, unknown> = { key: sheet.key };
  if (sheet.title !== undefined) out.title = sheet.title;
  if (sheet.fillable === false) out.fillable = false;
  if (sheet.freeze) out.freeze = sheet.freeze;
  if (sheet.print) out.print = sheet.print;
  if (Object.keys(inputs).length > 0) out.inputs = inputs;
  if (conditions.length > 0) out.conditions = conditions;
  const columns = body.columns.map((column) => {
    const formula = body.dimension === 1 ? sheet.columnFormulas?.[column.id] : undefined;
    return formula ? { ...column, formula } : column;
  });
  Object.assign(out, {
    dimension: body.dimension,
    rowsMode: body.rowsMode,
    columns,
    headerRows: body.headerRows,
    rows: body.rows,
    cells,
    merges: body.merges,
  });
  return { formatVersion: 2, sheets: [out, ...(sheet.otherSheets ?? []).map(asRecord)] };
}

function sortedValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortedValue);
  }
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([k, v]) => [k, sortedValue(v)]),
    );
  }
  return value;
}

/**
 * The text of the document with every object's keys in order and the parts that mean nothing left out: two documents
 * that mean the same thing have the same text. Used to tell whether there is something to save. Merges are put in order
 * because their order means nothing.
 */
export function canonicalText(doc: GridDoc): string {
  const sent = toApi(doc);
  if ("sheets" in sent) {
    const sheets = sent.sheets.map((sheet, index) =>
      index === 0 ? { ...sheet, merges: sortedMerges(sheet.merges as GridMerge[]) } : sheet,
    );
    return JSON.stringify(sortedValue({ ...sent, sheets }));
  }
  return JSON.stringify(sortedValue({ ...sent, merges: sortedMerges(sent.merges) }));
}

function sortedMerges(merges: GridMerge[]): GridMerge[] {
  return [...merges].sort((a, b) => (a.from + a.to < b.from + b.to ? -1 : 1));
}

/** Whether two documents mean the same thing. */
export function sameDocument(a: GridDoc, b: GridDoc): boolean {
  return a === b || canonicalText(a) === canonicalText(b);
}
