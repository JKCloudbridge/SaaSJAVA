/**
 * The formulas of a sheet in the browser (SH2, ADR-0252): the types the translators, the gate, the printer and the
 * interpreter share. The browser's copy only checks a formula while a person types and shows a result; the server
 * decides what is accepted and what the answer is, and both are held to one file of cases.
 */

/** What the server says formulas may be: the table of functions and the bounds, from the list of versions. */
export interface FormulaSpec {
  /** The functions a formula may call, by name in capitals, with the fewest and the most arguments. */
  functions: Record<string, readonly [number, number]>;
  /** The formats the second argument of TEXT may be. */
  textFormats: readonly string[];
  maxChars: number;
  maxDepth: number;
  maxNodes: number;
  maxSteps: number;
  maxTextChars: number;
}

/** The bounds of the server's defaults, for the tests and for a server that does not say. */
export const DEFAULT_BOUNDS = { maxChars: 4000, maxDepth: 32, maxNodes: 1000, maxSteps: 100_000, maxTextChars: 100_000 };

/** A formula was refused, with a stable reason. It carries nothing the person typed. */
export class FormulaRefused extends Error {
  readonly reason: string;

  constructor(reason: string) {
    super(reason);
    this.reason = reason;
  }
}

/** An error value of a formula, shown as its code and never hidden. */
export class XlError {
  readonly code: string;

  constructor(code: string) {
    this.code = code;
  }

  toString(): string {
    return this.code;
  }
}

export const E = {
  VALUE: new XlError("#VALUE!"),
  DIV0: new XlError("#DIV/0!"),
  NUM: new XlError("#NUM!"),
  NAME: new XlError("#NAME?"),
  NA: new XlError("#N/A"),
  LIMIT: new XlError("#LIMIT!"),
  REF: new XlError("#REF!"),
} as const;

/** A value: null is an empty cell. */
export type Value = number | string | boolean | XlError | null;

export interface Ref {
  t: "ref";
  column: string;
  /** The identifier of the row, or null for the formula's own row (and for the whole body of a column). */
  row: string | null;
  absoluteColumn: boolean;
  absoluteRow: boolean;
  /** The column over every body row, however many the filler adds. */
  growing: boolean;
}

export type Node =
  | { t: "num"; value: number; text: string }
  | { t: "text"; value: string }
  | { t: "bool"; value: boolean }
  | Ref
  | { t: "range"; from: Ref; to: Ref }
  | { t: "neg"; operand: Node }
  | { t: "pct"; operand: Node }
  | { t: "bin"; op: string; left: Node; right: Node }
  | { t: "group"; inner: Node }
  | { t: "call"; name: string; args: Node[] };

/** Where the values come from. A body row is a token the grid makes up (`#0`, `#1`, ...). */
export interface Grid {
  cell(column: string, row: string): Value;
  range(fromColumn: string, fromRow: string, toColumn: string, toRow: string): Value[];
  bodyRows(): string[];
}

/** The fixed words for every reason a formula is refused. They repeat nothing that was typed. */
export const WORDS: Record<string, string> = {
  too_long: "A formula is longer than the allowed length.",
  too_deep: "A formula is nested deeper than the allowed number of levels.",
  too_complex: "A formula has more parts than the allowed number.",
  character_not_allowed:
    "A formula uses a character that is not allowed. Another sheet, another file, a name and joining text with & are not supported.",
  unclosed_text: "A formula has a text in quotes that is not closed.",
  bad_reference_token: "A formula holds a reference that is not written correctly.",
  unknown_column: "A formula points at a column that does not exist.",
  unknown_row: "A formula points at a row that does not exist.",
  column_outside_sheet: "A formula points at a column outside the sheet.",
  row_outside_sheet: "A formula points at a row outside the sheet, or at a row of a repeating sheet that cannot be named.",
  name_not_allowed: "A formula uses a name. Names are not supported.",
  function_not_allowed: "A formula uses a function that is not supported.",
  wrong_argument_count: "A formula gives a function the wrong number of arguments.",
  count_needs_cells: "COUNT takes only cells and ranges.",
  text_format_not_allowed: "TEXT takes one of the listed formats as a text in quotes.",
  bad_range: "A formula holds a range that is not written correctly.",
  unexpected_token: "A formula is not written correctly.",
  unexpected_end: "A formula ends before it is complete.",
  missing_bracket: "A formula has a bracket that is not closed.",
  empty_argument: "A formula leaves an argument empty.",
  number_range: "A formula holds a number that is too long or too far from zero.",
  unsafe_text: "A text in a formula holds markup or a control character.",
  growing_not_allowed: "The whole body of a column can be named only in a sheet where the filler adds all the rows.",
  circular: "A formula depends on itself, directly or through other formulas.",
  chain_too_deep: "Formulas depend on each other through more steps than the allowed number.",
  not_a_fixed_cell: "A cell with a formula is a fixed cell: it holds no text of its own and is not an input cell.",
  column_not_allowed: "A column can hold a formula only in a sheet where the filler adds all the rows.",
  too_many_formulas: "A sheet has more formulas than the allowed number.",
  no_body_rows: "The sheet has no rows to draw this formula at.",
  empty_formula: "A formula is empty.",
};

/** The words of a reason. */
export function wordsOf(reason: string): string {
  return WORDS[reason] ?? "A formula is not accepted.";
}
