/**
 * A decimal number kept as the text a person typed, so it reaches the API exactly. The API reads a number as an exact
 * decimal (up to 38 digits) and refuses a number written as text; a JavaScript number would round anything beyond 15 or
 * 16 digits, so a value that matters (an amount, an identifier made of digits) must travel as the digits that were typed.
 *
 * <p>It has no `toJSON` on purpose: `JSON.stringify` would call it and the digits would already be a rounded number. A
 * body written by anything but {@link stringifyBody} carries an object where a number belongs, which the API refuses
 * loudly instead of storing a rounded value.
 */
export class DecimalText {
  readonly text: string;

  constructor(text: string) {
    this.text = text;
  }
}

const PLAIN_DECIMAL = /^-?\d+(\.\d+)?$/;

function write(value: unknown): string | undefined {
  if (value instanceof DecimalText) {
    // Only the characters of a plain decimal number are ever written outside quotes.
    if (!PLAIN_DECIMAL.test(value.text)) {
      throw new Error("A decimal must be written with digits only.");
    }
    return value.text;
  }
  if (value === undefined || typeof value === "function" || typeof value === "symbol") {
    return undefined;
  }
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => write(item) ?? "null").join(",")}]`;
  }
  const parts: string[] = [];
  for (const [key, item] of Object.entries(value)) {
    const text = write(item);
    if (text !== undefined) {
      parts.push(`${JSON.stringify(key)}:${text}`);
    }
  }
  return `{${parts.join(",")}}`;
}

/**
 * The JSON text of a request body in which each {@link DecimalText} is written as a number with exactly its digits.
 * The tree is written by hand, so no text a person typed can ever be taken for a number: every string goes through
 * `JSON.stringify` and only a `DecimalText` (checked first) is written bare.
 */
export function stringifyBody(body: unknown): string {
  return write(body) ?? "null";
}

/** A number written without an exponent, for a field that shows or edits it as text. */
export function plainNumber(value: number): string {
  if (!Number.isFinite(value)) {
    return "";
  }
  const asText = String(value);
  if (!/e/i.test(asText)) {
    return asText;
  }
  return value.toLocaleString("en-US", { useGrouping: false, maximumFractionDigits: 20 });
}
