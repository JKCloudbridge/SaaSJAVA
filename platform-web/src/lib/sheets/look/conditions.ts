import { checkedColour } from "./look";

/**
 * The conditional colours of a sheet (SH1B stored them, SH3 wrote them into the file, SH4 draws them). A rule is a closed
 * comparison of the displayed number of a cell against one or two plain numbers, and a colour. Nothing in a rule is ever
 * run; this is display only, and the server stays the authority for the values. Only a number meets a comparison: text, a
 * blank and an error never do.
 */

export const OPERATORS = ["LT", "LTE", "EQ", "NEQ", "GTE", "GT", "BETWEEN", "NOT_BETWEEN"] as const;
export type Operator = (typeof OPERATORS)[number];

const DECIMAL = /^-?[0-9]{1,15}(\.[0-9]{1,10})?$/;

export interface ConditionRule {
  /** The range in display positions, both ends included. */
  r0: number;
  c0: number;
  r1: number;
  c1: number;
  operator: Operator;
  value: number;
  /** The text the number was stored as (the words repeat it exactly). */
  valueText: string;
  value2?: number;
  value2Text?: string;
  fill?: string;
  colour?: string;
}

/** What a rule does to one cell. */
export interface ConditionHit {
  fill?: string;
  colour?: string;
  /** The fixed words for a screen reader, for the first rule that met the cell. */
  words: string;
}

/** The fixed words of a rule, as the screen reader gets them. */
export function wordsOf(operator: Operator, valueText: string, value2Text?: string): string {
  const low = valueText;
  const high = value2Text ?? valueText;
  switch (operator) {
    case "LT":
      return `highlighted: below ${low}`;
    case "LTE":
      return `highlighted: at most ${low}`;
    case "EQ":
      return `highlighted: equal to ${low}`;
    case "NEQ":
      return `highlighted: not equal to ${low}`;
    case "GTE":
      return `highlighted: at least ${low}`;
    case "GT":
      return `highlighted: above ${low}`;
    case "BETWEEN":
      return `highlighted: between ${low} and ${high}`;
    case "NOT_BETWEEN":
      return `highlighted: outside ${low} to ${high}`;
  }
}

/** Whether a number meets a comparison. For a range the order of the two numbers does not matter. */
export function meets(operator: Operator, value: number, value2: number | undefined, cell: number): boolean {
  switch (operator) {
    case "LT":
      return cell < value;
    case "LTE":
      return cell <= value;
    case "EQ":
      return cell === value;
    case "NEQ":
      return cell !== value;
    case "GTE":
      return cell >= value;
    case "GT":
      return cell > value;
    case "BETWEEN":
    case "NOT_BETWEEN": {
      const low = Math.min(value, value2 ?? value);
      const high = Math.max(value, value2 ?? value);
      const inside = cell >= low && cell <= high;
      return operator === "BETWEEN" ? inside : !inside;
    }
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

/**
 * Reads the stored rules into ranges of display positions. A rule that is not exactly what the format allows (an operator
 * not in the list, a number not in the plain shape, a range that is not in the sheet, a colour not in the one shape, no
 * colour at all) is left out. `rowAt` and `columnAt` give the display position of a row or column identifier.
 */
export function readConditions(
  raw: readonly Record<string, unknown>[] | undefined,
  rowAt: (rowId: string) => number | undefined,
  columnAt: (columnId: string) => number | undefined,
): ConditionRule[] {
  const rules: ConditionRule[] = [];
  for (const item of raw ?? []) {
    const rule = asRecord(item);
    const operator = OPERATORS.find((candidate) => candidate === rule.operator);
    const from = typeof rule.from === "string" ? rule.from.split(":") : [];
    const to = typeof rule.to === "string" ? rule.to.split(":") : [];
    const fromRow = from[0] === undefined ? undefined : rowAt(from[0]);
    const fromColumn = from[1] === undefined ? undefined : columnAt(from[1]);
    const toRow = to[0] === undefined ? undefined : rowAt(to[0]);
    const toColumn = to[1] === undefined ? undefined : columnAt(to[1]);
    const valueText = rule.value;
    const value2Text = rule.value2;
    if (
      !operator ||
      fromRow === undefined ||
      fromColumn === undefined ||
      toRow === undefined ||
      toColumn === undefined ||
      typeof valueText !== "string" ||
      !DECIMAL.test(valueText)
    ) {
      continue;
    }
    const range = operator === "BETWEEN" || operator === "NOT_BETWEEN";
    if (range && !(typeof value2Text === "string" && DECIMAL.test(value2Text))) {
      continue;
    }
    const fill = checkedColour(rule.fill);
    const colour = checkedColour(rule.colour);
    if (!fill && !colour) {
      continue;
    }
    rules.push({
      r0: Math.min(fromRow, toRow),
      c0: Math.min(fromColumn, toColumn),
      r1: Math.max(fromRow, toRow),
      c1: Math.max(fromColumn, toColumn),
      operator,
      value: Number(valueText),
      valueText,
      ...(range && typeof value2Text === "string" ? { value2: Number(value2Text), value2Text } : {}),
      ...(fill ? { fill } : {}),
      ...(colour ? { colour } : {}),
    });
  }
  return rules;
}

/**
 * What the rules do to the cell at a display position whose displayed number is `cell` (null when it is not a number). The
 * first rule that gives a fill and the first that gives a text colour win, as the rules are applied in their stored order;
 * the words are those of the first rule that met the cell.
 */
export function judge(rules: readonly ConditionRule[], row: number, column: number, cell: number | null): ConditionHit | null {
  if (cell === null || rules.length === 0) {
    return null;
  }
  let hit: ConditionHit | null = null;
  for (const rule of rules) {
    if (row < rule.r0 || row > rule.r1 || column < rule.c0 || column > rule.c1) continue;
    if (!meets(rule.operator, rule.value, rule.value2, cell)) continue;
    if (hit === null) {
      hit = { words: wordsOf(rule.operator, rule.valueText, rule.value2Text) };
    }
    if (hit.fill === undefined && rule.fill) hit.fill = rule.fill;
    if (hit.colour === undefined && rule.colour) hit.colour = rule.colour;
  }
  return hit;
}
