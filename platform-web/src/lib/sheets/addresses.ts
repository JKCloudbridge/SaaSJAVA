import { letters } from "./formula/translate";

/**
 * The addresses a person reads on a sheet in format version 2 (SH5.1, ADR-0263): the letters over the columns and the
 * numbers beside the rows. They are counted exactly as the formula bar counts, from the same function, so a heading and
 * a formula can never disagree: column A is the first column of the sheet, and row 1 is the first row the grid draws (the
 * header rows first, then the named rows, or the rows the filler adds). Nothing stored uses these addresses: a stored
 * formula names its columns and rows by permanent identifier.
 */

/** The letters of a column from its position counted from zero: A, B, ... Z, AA. */
export function columnLetters(position: number): string {
  return letters(position);
}

/** The number of a row from its position counted from zero: 1, 2, 3. */
export function rowNumber(position: number): number {
  return position + 1;
}

/** The address of a cell, such as B12, from its row and column positions counted from zero. */
export function cellAddress(row: number, column: number): string {
  return `${columnLetters(column)}${rowNumber(row)}`;
}
