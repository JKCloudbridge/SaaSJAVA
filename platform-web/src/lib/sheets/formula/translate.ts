import { referenceToken } from "./parse";
import { DEFAULT_BOUNDS, FormulaRefused } from "./types";

/**
 * Translates between the text a spreadsheet program writes (`SUM(D4:D9)`) and the stored form in which every reference
 * names its column and row by permanent identifier (SH2, ADR-0252). It is a scanner, not a parser: it skips text
 * constants, rewrites each plain reference, and refuses a reference outside the sheet, another sheet, another file, a
 * name, the characters `@ { [ ! '` and a text that is not closed. Inserting, moving or removing a row or column
 * rewrites no stored formula; only the text drawn back changes. The server has the same translators.
 */

/**
 * What the translators need to know about the sheet.
 *
 * `columnIds`: position 0 is column A. `rowIds`: the rows that have an identifier, in order (the header rows, then the
 * named rows of a sheet that names its rows). `growing`: the filler adds all the rows, so the body rows follow the
 * header rows and have no identifier. `bodyRows`: how many body rows are drawn now.
 */
export interface Layout {
  columnIds: readonly string[];
  rowIds: readonly string[];
  growing: boolean;
  bodyRows: number;
}

const MOST_ROW = 9_999_999;

const isLetter = (c: string): boolean => (c >= "a" && c <= "z") || (c >= "A" && c <= "Z");
const isDigit = (c: string): boolean => c >= "0" && c <= "9";
const wordChar = (c: string): boolean => isLetter(c) || isDigit(c) || c === "_" || c === ".";

function wordStart(s: string, i: number): boolean {
  const c = s.charAt(i);
  if (!(isLetter(c) || c === "$" || c === "_")) return false;
  if (i === 0) return true;
  const before = s.charAt(i - 1);
  return !(wordChar(before) || before === "$");
}

function endOfText(s: string, start: number): number {
  for (let k = start + 1; k < s.length; k++) {
    if (s.charAt(k) === '"') {
      if (s.charAt(k + 1) === '"') {
        k++;
        continue;
      }
      return k + 1;
    }
  }
  throw new FormulaRefused("unclosed_text");
}

/** The letters of a column from its 0-based position. */
export function letters(index: number): string {
  let s = "";
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
  return s;
}

function columnIndex(text: string): number {
  let value = 0;
  for (const c of text.toUpperCase()) value = value * 26 + c.charCodeAt(0) - 64;
  return value - 1;
}

interface Found {
  end: number;
  column: number;
  row: number;
  absoluteColumn: boolean;
  absoluteRow: boolean;
}

/** A plain reference (`A1`, `$A$1`) that starts at the position, or null. */
function referenceAt(s: string, i: number): Found | null {
  if (!wordStart(s, i)) return null;
  let k = i;
  let absoluteColumn = false;
  if (s.charAt(k) === "$") {
    absoluteColumn = true;
    k++;
  }
  const lettersStart = k;
  while (k < s.length && isLetter(s.charAt(k)) && k - lettersStart < 3) k++;
  if (k === lettersStart) return null;
  const lettersEnd = k;
  let absoluteRow = false;
  if (s.charAt(k) === "$") {
    absoluteRow = true;
    k++;
  }
  const digitsStart = k;
  while (k < s.length && isDigit(s.charAt(k)) && k - digitsStart < 7) k++;
  if (k === digitsStart) return null;
  const next = s.charAt(k);
  if (next !== "" && (isLetter(next) || isDigit(next) || next === "_" || next === "(" || next === ".")) return null;
  return {
    end: k,
    column: columnIndex(s.slice(lettersStart, lettersEnd)),
    row: Number(s.slice(digitsStart, k)),
    absoluteColumn,
    absoluteRow,
  };
}

function growingRange(a: Found, b: Found, layout: Layout): boolean {
  const first = layout.rowIds.length + 1;
  const last = layout.rowIds.length + layout.bodyRows;
  return (
    layout.growing &&
    layout.bodyRows >= 1 &&
    a.column === b.column &&
    a.column < layout.columnIds.length &&
    !a.absoluteRow &&
    !b.absoluteRow &&
    !a.absoluteColumn &&
    !b.absoluteColumn &&
    a.row === first &&
    b.row === last
  );
}

function token(found: Found, row: number, layout: Layout): string {
  if (found.column >= layout.columnIds.length) throw new FormulaRefused("column_outside_sheet");
  if (found.row < 1) throw new FormulaRefused("row_outside_sheet");
  const same = found.row === row && !found.absoluteRow;
  let rowId: string | null = null;
  if (!same) {
    const id = layout.rowIds[found.row - 1];
    if (id === undefined || id === "") throw new FormulaRefused("row_outside_sheet");
    rowId = id;
  }
  let out = `@{${layout.columnIds[found.column]}`;
  if (rowId !== null) out += `,${rowId}`;
  if (found.absoluteColumn || found.absoluteRow) {
    out += rowId === null ? ",," : ",";
    if (found.absoluteColumn) out += "c";
    if (found.absoluteRow) out += "r";
  }
  return `${out}}`;
}

/**
 * Spreadsheet text to the stored form. `row` is the 1-based position of the cell that holds the formula.
 *
 * @throws FormulaRefused when a reference cannot be named by identifiers, or a character is not allowed
 */
export function toIds(excel: string, row: number, layout: Layout): string {
  if (excel.length > DEFAULT_BOUNDS.maxChars) throw new FormulaRefused("too_long");
  let out = "";
  let i = 0;
  while (i < excel.length) {
    const c = excel.charAt(i);
    if (c === '"') {
      const end = endOfText(excel, i);
      out += excel.slice(i, end);
      i = end;
      continue;
    }
    if ("@{[!'".includes(c)) throw new FormulaRefused("character_not_allowed");
    const found = referenceAt(excel, i);
    if (found !== null) {
      if (excel.charAt(found.end) === ":") {
        const second = referenceAt(excel, found.end + 1);
        if (second !== null && growingRange(found, second, layout)) {
          out += `@{${layout.columnIds[found.column]},*}`;
          i = second.end;
          continue;
        }
      }
      out += token(found, row, layout);
      i = found.end;
      continue;
    }
    if (wordStart(excel, i)) {
      let end = i;
      while (end < excel.length && wordChar(excel.charAt(end))) end++;
      if (end === i) {
        out += c;
        i++;
      } else {
        out += excel.slice(i, end);
        i = end;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/**
 * The stored form drawn as spreadsheet text at a row, with the whole body of a column drawn as the rows that exist now.
 *
 * @throws FormulaRefused when a token names a column or row that does not exist
 */
export function toExcel(stored: string, row: number, layout: Layout): string {
  const columns = new Map(layout.columnIds.map((id, i) => [id, i]));
  const rows = new Map(layout.rowIds.map((id, i) => [id, i]));
  const first = layout.rowIds.length + 1;
  const last = layout.rowIds.length + layout.bodyRows;
  let out = "";
  let i = 0;
  while (i < stored.length) {
    const c = stored.charAt(i);
    if (c === '"') {
      const end = endOfText(stored, i);
      out += stored.slice(i, end);
      i = end;
      continue;
    }
    if (c !== "@") {
      out += c;
      i++;
      continue;
    }
    const close = stored.indexOf("}", i);
    if (!stored.startsWith("@{", i) || close < 0) throw new FormulaRefused("bad_reference_token");
    const ref = referenceToken(stored.slice(i + 2, close));
    const column = columns.get(ref.column);
    if (column === undefined) throw new FormulaRefused("unknown_column");
    const name = letters(column);
    const after = stored.charAt(close + 1);
    const before = out.charAt(out.length - 1);
    if (ref.growing) {
      if (!layout.growing) throw new FormulaRefused("growing_not_allowed");
      if (layout.bodyRows < 1) throw new FormulaRefused("no_body_rows");
      if (after === ":") out += `${name}${first}`;
      else if (before === ":") out += `${name}${last}`;
      else out += `${name}${first}:${name}${last}`;
    } else {
      let position = row;
      if (ref.row !== null) {
        const index = rows.get(ref.row);
        if (index === undefined) throw new FormulaRefused("unknown_row");
        position = index + 1;
      }
      if (position < 1 || position > MOST_ROW) throw new FormulaRefused("row_outside_sheet");
      out += `${ref.absoluteColumn ? "$" : ""}${name}${ref.absoluteRow ? "$" : ""}${position}`;
    }
    i = close + 1;
  }
  return out;
}
