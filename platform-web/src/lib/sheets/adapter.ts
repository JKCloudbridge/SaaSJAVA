import { splitKey, zoneOfRow } from "./geometry";
import { tidyCell } from "./gridModel";
import {
  COLUMN_TYPES,
  FONT_SIZES,
  type CellStyle,
  type FontSize,
  type GridCell,
  type GridColumn,
  type GridDoc,
  type GridLine,
  type RowsMode,
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
  const size: FontSize | undefined = FONT_SIZES.find((candidate) => candidate === source.fontSize);
  if (size && size !== "NORMAL") {
    out.fontSize = size;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** Reads an answer of the API into a document, in the one form the editor and the server agree on. */
export function fromApi(raw: unknown): GridDoc {
  const source = asRecord(raw);
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

/** The document as it is sent: the same form `fromApi` reads, with every part present. */
export function toApi(doc: GridDoc): GridDoc {
  return fromApi(doc);
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
  const merges = [...sent.merges].sort((a, b) => (a.from + a.to < b.from + b.to ? -1 : 1));
  return JSON.stringify(sortedValue({ ...sent, merges }));
}

/** Whether two documents mean the same thing. */
export function sameDocument(a: GridDoc, b: GridDoc): boolean {
  return a === b || canonicalText(a) === canonicalText(b);
}
