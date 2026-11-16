import type { FormulaLimitsWire, GridCell, GridDoc, OpResult, SheetExtras } from "../types";
import { evaluate, show } from "./evaluate";
import { parse } from "./parse";
import { print } from "./print";
import { toExcel, toIds, type Layout } from "./translate";
import { FormulaRefused, wordsOf, XlError, type FormulaSpec, type Grid, type Value } from "./types";

/**
 * What the author's editor does with formulas (SH2, ADR-0252): the document operations (give a cell a formula, clear
 * it, give a column one), the spreadsheet-style text a bar shows, and the answers the browser shows while a person
 * types. A formula is kept beside its cell in `doc.sheet`, as its stored form with identifiers, so inserting, moving or
 * removing a row or column rewrites none. The server accepts or refuses on save and its answer is the one that counts.
 */

/** The spec the browser checks with, made from what the server said; null when the server did not say. */
export function specOf(wire: FormulaLimitsWire | undefined): FormulaSpec | null {
  if (wire === undefined) return null;
  const functions: Record<string, readonly [number, number]> = {};
  for (const one of wire.functions) functions[one.name] = [one.least, one.most];
  return {
    functions,
    textFormats: wire.textFormats,
    maxChars: wire.maxChars,
    maxDepth: wire.maxDepth,
    maxNodes: wire.maxNodes,
    maxSteps: wire.maxSteps,
    maxTextChars: wire.maxTextChars,
  };
}

/** The layout of a document for the translators; for a sheet where the filler adds the rows, with the rows drawn now. */
export function layoutOf(doc: GridDoc, bodyRows: number): Layout {
  return {
    columnIds: doc.columns.map((column) => column.id),
    rowIds: [...doc.headerRows, ...(doc.dimension === 2 ? doc.rows : [])].map((line) => line.id),
    growing: doc.dimension === 1,
    bodyRows,
  };
}

/** What a person writes for a number: a sign, digits, an optional fraction (the server reads the same). */
const PLAIN_NUMBER = /^[+-]?(\d+(\.\d*)?|\.\d+)$/;

const NOT_HERE = "Formulas need the new sheet format, which is not switched on here.";

function extrasOf(doc: GridDoc): SheetExtras | null {
  return doc.formatVersion === 2 && doc.sheet ? doc.sheet : null;
}

/** The stored form of the formula of a cell, or undefined. */
export function formulaOf(doc: GridDoc, rowId: string, columnId: string): string | undefined {
  return doc.sheet?.cells[`${rowId}:${columnId}`]?.formula;
}

/** The stored form of the formula of a calculated column, or undefined. */
export function columnFormulaOf(doc: GridDoc, columnId: string): string | undefined {
  return doc.dimension === 1 ? doc.sheet?.columnFormulas?.[columnId] : undefined;
}

/** The 1-based position of a row among the rows that have an identifier, or 0 when there is none. */
function positionOf(layout: Layout, rowId: string): number {
  return layout.rowIds.indexOf(rowId) + 1;
}

/** The formula of a cell as a bar shows it (with its equals sign), or the reason it cannot be drawn. */
export function barText(doc: GridDoc, rowId: string, columnId: string): string {
  const stored = formulaOf(doc, rowId, columnId);
  if (stored === undefined) return "";
  try {
    const layout = layoutOf(doc, 1);
    return `=${toExcel(stored, positionOf(layout, rowId), layout)}`;
  } catch {
    return "";
  }
}

/** The formula of a calculated column as a bar shows it: drawn at the first row of the body. */
export function columnBarText(doc: GridDoc, columnId: string): string {
  const stored = columnFormulaOf(doc, columnId);
  if (stored === undefined) return "";
  try {
    const layout = layoutOf(doc, 1);
    return `=${toExcel(stored, layout.rowIds.length + 1, layout)}`;
  } catch {
    return "";
  }
}

/** What a typed formula comes to: its canonical stored form, or why not (in the words of the server). */
export type Typed = { ok: true; stored: string } | { ok: false; error: string };

/** Checks a formula typed in spreadsheet text (with or without the equals sign) at a row. */
export function check(typed: string, row: number, layout: Layout, spec: FormulaSpec): Typed {
  const body = typed.startsWith("=") ? typed.slice(1) : typed;
  try {
    return { ok: true, stored: print(parse(toIds(body, row, layout), spec)) };
  } catch (e) {
    if (e instanceof FormulaRefused) return { ok: false, error: wordsOf(e.reason) };
    throw e;
  }
}

function withExtras(doc: GridDoc, extras: SheetExtras, cells: Record<string, GridCell>): GridDoc {
  return { ...doc, sheet: extras, cells };
}

/** Gives a cell a formula typed in spreadsheet text. A refusal changes nothing. */
export function setCellFormula(doc: GridDoc, rowId: string, columnId: string, typed: string, spec: FormulaSpec | null): OpResult {
  const extras = extrasOf(doc);
  if (extras === null || spec === null) return { ok: false, error: NOT_HERE };
  const layout = layoutOf(doc, 1);
  const position = positionOf(layout, rowId);
  if (position === 0) {
    return { ok: false, error: "A formula belongs to a row of the sheet, not to a row the filler adds." };
  }
  const result = check(typed, position, layout, spec);
  if (!result.ok) return { ok: false, error: result.error };
  const key = `${rowId}:${columnId}`;
  const cells = { ...doc.cells };
  const header = doc.headerRows.some((line) => line.id === rowId);
  const next: GridCell = { ...cells[key] };
  delete next.value;
  if (header) {
    // A header cell is always fixed; one that holds only a formula needs no cell of the grid at all.
    if (next.style) cells[key] = next;
    else delete cells[key];
  } else {
    cells[key] = { ...next, kind: "fixed" };
  }
  return {
    ok: true,
    doc: withExtras(doc, { ...extras, cells: { ...extras.cells, [key]: { ...extras.cells[key], formula: result.stored } } }, cells),
  };
}

/** Takes the formula off a cell; the cell is then empty and is typed into again. */
export function clearCellFormula(doc: GridDoc, rowId: string, columnId: string): GridDoc {
  const extras = extrasOf(doc);
  const key = `${rowId}:${columnId}`;
  if (extras === null || extras.cells[key]?.formula === undefined) return doc;
  const kept = { ...extras.cells[key] };
  delete kept.formula;
  const next = { ...extras.cells };
  if (Object.keys(kept).length > 0) next[key] = kept;
  else delete next[key];
  const cells = { ...doc.cells };
  const held = cells[key];
  if (held && held.value === undefined && held.kind === "fixed") {
    const rest: GridCell = { ...held };
    delete rest.kind;
    if (Object.keys(rest).length > 0) cells[key] = rest;
    else delete cells[key];
  }
  return withExtras(doc, { ...extras, cells: next }, cells);
}

/** Gives a column of a sheet where the filler adds the rows its one formula. */
export function setColumnFormula(doc: GridDoc, columnId: string, typed: string, spec: FormulaSpec | null): OpResult {
  const extras = extrasOf(doc);
  if (extras === null || spec === null) return { ok: false, error: NOT_HERE };
  if (doc.dimension !== 1) {
    return { ok: false, error: "A column can hold a formula only in a sheet where the filler adds all the rows." };
  }
  const layout = layoutOf(doc, 1);
  const result = check(typed, layout.rowIds.length + 1, layout, spec);
  if (!result.ok) return { ok: false, error: result.error };
  return { ok: true, doc: { ...doc, sheet: { ...extras, columnFormulas: { ...extras.columnFormulas, [columnId]: result.stored } } } };
}

/** Takes the formula off a column. */
export function clearColumnFormula(doc: GridDoc, columnId: string): GridDoc {
  const extras = extrasOf(doc);
  if (extras?.columnFormulas?.[columnId] === undefined) return doc;
  const next = { ...extras.columnFormulas };
  delete next[columnId];
  const rest: SheetExtras = { ...extras };
  delete rest.columnFormulas;
  return { ...doc, sheet: Object.keys(next).length > 0 ? { ...rest, columnFormulas: next } : rest };
}

// ---- the answers the browser shows while a person types ----

/**
 * The browser's answers for every formula of the document, with every input cell empty and one row where the filler
 * adds the rows: cells by cell key, columns by column identifier. Only a preview; the server's answer is the one that
 * counts. An error is its code.
 */
export function answers(doc: GridDoc, spec: FormulaSpec | null): { cells: Record<string, string>; columns: Record<string, string> } {
  const { cells, columns } = answerSet(doc, spec);
  return { cells, columns };
}

/** The kind of an answer, as the server names it. */
export type AnswerKindName = "number" | "text" | "boolean" | "error" | "blank";

function kindOf(value: Value): AnswerKindName {
  if (value === null) return "blank";
  if (value instanceof XlError) return "error";
  if (typeof value === "boolean") return "boolean";
  return typeof value === "number" ? "number" : "text";
}

/** The answers with the kind of each (SH4): a number is formatted and judged by the conditional colours, anything else is shown as it is. */
export interface AnswerSet {
  cells: Record<string, string>;
  columns: Record<string, string>;
  kinds: { cells: Record<string, AnswerKindName>; columns: Record<string, AnswerKindName> };
}

export function answerSet(doc: GridDoc, spec: FormulaSpec | null): AnswerSet {
  const out: AnswerSet = { cells: {}, columns: {}, kinds: { cells: {}, columns: {} } };
  const extras = extrasOf(doc);
  if (extras === null || spec === null) return out;
  const bounds = { maxSteps: spec.maxSteps, maxTextChars: spec.maxTextChars };
  const columnIds = new Set(doc.columns.map((column) => column.id));
  const order = [...doc.headerRows, ...(doc.dimension === 2 ? doc.rows : [])].map((line) => line.id);
  if (doc.dimension === 1) order.push("#0");
  const rowAt = new Map(order.map((id, i) => [id, i]));
  const columnAt = new Map(doc.columns.map((column, i) => [column.id, i]));
  const memo = new Map<string, Value>();
  const running = new Set<string>();

  const formulaText = (key: string): string | undefined => extras.cells[key]?.formula;
  const calculate = (id: string, ownRow: string, stored: string): Value => {
    const known = memo.get(id);
    if (known !== undefined) return known;
    if (running.has(id)) return new XlError("#REF!");
    running.add(id);
    let result: Value;
    try {
      result = evaluate(parse(stored, spec), ownRow, grid, bounds);
    } catch {
      result = new XlError("#VALUE!");
    }
    running.delete(id);
    memo.set(id, result);
    return result;
  };
  const grid: Grid = {
    cell(column, row) {
      if (!columnIds.has(column)) return new XlError("#REF!");
      if (row.startsWith("#")) {
        const stored = columnFormulaOf(doc, column);
        return stored === undefined ? null : calculate(`k:${column}`, row, stored);
      }
      const key = `${row}:${column}`;
      const stored = formulaText(key);
      if (stored !== undefined) return calculate(`c:${key}`, row, stored);
      const cell = doc.cells[key];
      const value = cell?.value;
      // A number written in a fixed cell is a number, as the server reads it (SH5.1): SUM skips text.
      if (typeof value === "string") return PLAIN_NUMBER.test(value.trim()) && value.trim().length <= 40 ? Number(value.trim()) : value;
      return typeof value === "boolean" ? value : null;
    },
    range(fromColumn, fromRow, toColumn, toRow) {
      const c0 = columnAt.get(fromColumn);
      const c1 = columnAt.get(toColumn);
      const r0 = rowAt.get(fromRow);
      const r1 = rowAt.get(toRow);
      if (c0 === undefined || c1 === undefined || r0 === undefined || r1 === undefined) return [new XlError("#REF!")];
      const values: Value[] = [];
      for (let r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) {
        for (let c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) {
          values.push(grid.cell(doc.columns[c]?.id ?? "", order[r] ?? ""));
        }
      }
      return values;
    },
    bodyRows() {
      return doc.dimension === 1 ? ["#0"] : [];
    },
  };
  for (const [key, extra] of Object.entries(extras.cells)) {
    if (extra.formula !== undefined) {
      const value = calculate(`c:${key}`, key.slice(0, key.indexOf(":")), extra.formula);
      out.cells[key] = show(value);
      out.kinds.cells[key] = kindOf(value);
    }
  }
  if (doc.dimension === 1) {
    for (const [column, stored] of Object.entries(extras.columnFormulas ?? {})) {
      const value = calculate(`k:${column}`, "#0", stored);
      out.columns[column] = show(value);
      out.kinds.columns[column] = kindOf(value);
    }
  }
  return out;
}
