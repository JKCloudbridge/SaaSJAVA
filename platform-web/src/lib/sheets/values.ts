import type { ColumnType, GridColumn } from "./types";

/**
 * The rules for the value of a cell, for the person's convenience (ADR-0173 section 2). The server has the same rules
 * and decides again on every save; these only let the screen say so before the request. The words are fixed.
 */

/** A less-than sign followed by a letter, a slash, an exclamation mark or a question mark starts markup. */
const MARKUP = /<[A-Za-z/!?]/;
/** Control characters other than tab and line feed, the line and paragraph separators and the text-reordering marks. */
// Built from a string so that the line and paragraph separators are written as escapes and never as the characters.
const CONTROL = new RegExp(
  "[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u2028\u2029\u202a-\u202e\u2066-\u2069]",
);
const NUMBER = /^-?[0-9]{1,38}(\.[0-9]{1,18})?$/;
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;

export const TEXT_UNSAFE = "A text contains markup or a control character.";

/** Whether the text holds markup or a control character; it is refused, never escaped. */
export function unsafeText(text: string): boolean {
  return MARKUP.test(text) || CONTROL.test(text);
}

/** Whether the text is a date that exists, written year-month-day. */
export function isRealDate(text: string): boolean {
  if (!DATE.test(text)) {
    return false;
  }
  const date = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(text);
}

/** Why the value does not fit the column, or null when it does. */
export function valueProblem(column: Pick<GridColumn, "type" | "picklist">, value: unknown): string | null {
  const type: ColumnType = column.type;
  switch (type) {
    case "text":
      return typeof value === "string" ? null : "A text column holds text.";
    case "number":
      return typeof value === "string" && NUMBER.test(value)
        ? null
        : "A number column holds digits, with an optional sign and decimals.";
    case "date":
      return typeof value === "string" && isRealDate(value) ? null : "A date column holds a date written year-month-day.";
    case "boolean":
      return typeof value === "boolean" ? null : "A yes or no column holds yes or no.";
    case "picklist":
      return typeof value === "string" && (column.picklist?.values ?? []).includes(value)
        ? null
        : "A list column holds one of its listed values.";
  }
}
