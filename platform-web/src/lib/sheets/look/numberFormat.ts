import type { NumberFormatKey } from "./look";

/**
 * The ten number formats of a sheet (SH4, ADR-0251), formatted for a number. The rule is the documented behaviour of a
 * spreadsheet program for the format code the file out writes (`NumberFormats.codeOf`): `look-cases.txt` holds the cases
 * that this code and the server's tests both read. Rounding is half away from zero on the DECIMAL digits of the number as
 * the server gives it (at most fifteen significant), never on a binary value, so 2.675 is 2.68. The locale is fixed (a dot
 * for decimals, a comma for thousands) whatever the browser's language. Dates are text in a sheet, so a date key shows
 * the value as it is.
 */

export type AnswerKind = "number" | "text" | "boolean" | "error" | "blank";

const PLAIN = /^([+-])?(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;
const MAX_EXPONENT = 400;
const SIGNIFICANT = 15;

interface Decimal {
  negative: boolean;
  /** Digits with no leading zeros ("" for zero). */
  digits: string;
  /** Where the point is: the number of digits before it (may be negative or beyond the digits). */
  point: number;
}

function parse(text: string): Decimal | null {
  const found = PLAIN.exec(text.trim());
  if (!found) return null;
  const [, sign, whole = "", fraction = "", exponent] = found;
  if (whole === "" && fraction === "") return null;
  const shift = exponent === undefined ? 0 : Number(exponent);
  if (!Number.isFinite(shift) || Math.abs(shift) > MAX_EXPONENT) return null;
  const all = whole + fraction;
  const lead = all.length - all.replace(/^0+/, "").length;
  const digits = all.slice(lead).replace(/0+$/, "");
  return { negative: sign === "-", digits, point: whole.length - lead + shift };
}

/** Adds one to the last digit of a run of digits; returns the new digits (one longer when it carried out). */
function increment(digits: string): string {
  const chars = digits.split("");
  let at = chars.length - 1;
  while (at >= 0) {
    if (chars[at] === "9") {
      chars[at] = "0";
      at--;
    } else {
      chars[at] = String(Number(chars[at]) + 1);
      return chars.join("");
    }
  }
  return `1${chars.join("")}`;
}

/** Keeps the first `keep` digits of the run, rounding half up on the next one. Returns the digits and whether it grew a place. */
function roundDigits(digits: string, keep: number): { digits: string; grew: boolean } {
  if (keep >= digits.length) return { digits, grew: false };
  if (keep < 0) return { digits: "", grew: false };
  const head = digits.slice(0, keep);
  if (digits.charCodeAt(keep) < 53) return { digits: head, grew: false };
  const up = increment(head === "" ? "0" : head);
  if (head === "") return { digits: up === "1" ? "1" : up, grew: up.length > 0 };
  return { digits: up, grew: up.length > head.length };
}

function group(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** The size of the number written with exactly `places` decimals (the sign is the caller's), and whether it came to zero. */
function fixed(value: Decimal, places: number, grouped: boolean): { text: string; zero: boolean } {
  // at most fifteen significant digits count, as in the program
  let { digits, point } = value;
  if (digits.length > SIGNIFICANT) {
    const cut = roundDigits(digits, SIGNIFICANT);
    digits = cut.digits.replace(/0+$/, "");
    if (cut.grew) point += 1;
  }
  const keep = point + places;
  let kept = digits;
  let at = point;
  if (keep < digits.length) {
    if (keep < 0) {
      kept = "";
    } else {
      const cut = roundDigits(digits, keep);
      kept = cut.digits;
      if (cut.grew) at += 1;
    }
  }
  // lay the digits out: whole part and a fraction of exactly `places` digits
  const full = kept === "" ? "" : kept;
  let whole: string;
  let fraction: string;
  if (full === "") {
    whole = "0";
    fraction = "0".repeat(places);
  } else if (at <= 0) {
    whole = "0";
    fraction = ("0".repeat(-at) + full).slice(0, places).padEnd(places, "0");
  } else if (at >= full.length) {
    whole = full + "0".repeat(at - full.length);
    fraction = "0".repeat(places);
  } else {
    whole = full.slice(0, at);
    fraction = full.slice(at).padEnd(places, "0").slice(0, places);
  }
  const zero = /^0*$/.test(whole) && /^0*$/.test(fraction);
  const body = `${grouped ? group(whole) : whole}${places > 0 ? `.${fraction}` : ""}`;
  return { text: body, zero };
}

const SHAPES: Record<string, { places: number; grouped: boolean; percent: boolean }> = {
  WHOLE: { places: 0, grouped: false, percent: false },
  TWO_DECIMALS: { places: 2, grouped: false, percent: false },
  PERCENT: { places: 0, grouped: false, percent: true },
  PERCENT_TWO_DECIMALS: { places: 2, grouped: false, percent: true },
  THOUSANDS: { places: 0, grouped: true, percent: false },
  THOUSANDS_TWO_DECIMALS: { places: 2, grouped: true, percent: false },
  ACCOUNTING: { places: 2, grouped: true, percent: false },
};

/** Whether a key of the closed list formats numbers (the date keys and the general format do not). */
export function formatsNumbers(key: NumberFormatKey): boolean {
  return key in SHAPES;
}

/**
 * A number, as the server gives it (plain digits, perhaps with an exponent), written in the format. A text that is not a
 * number, and every key that does not format numbers, give the text back as it is.
 */
export function formatNumber(key: NumberFormatKey, text: string): string {
  const shape = SHAPES[key];
  if (!shape) return text;
  const parsed = parse(text);
  if (!parsed) return text;
  const isZero = parsed.digits === "";
  const scaled: Decimal = shape.percent ? { ...parsed, point: parsed.point + 2 } : parsed;
  const done = fixed(scaled, shape.places, shape.grouped);
  if (key === "ACCOUNTING") {
    if (isZero) return "-";
    return parsed.negative ? `(${done.text})` : done.text;
  }
  const signed = parsed.negative && !isZero ? `-${done.text}` : done.text;
  return shape.percent ? `${signed}%` : signed;
}

/** What a cell shows for a value of a kind: only a number is formatted; everything else is shown as it is. */
export function formatShown(key: NumberFormatKey, kind: AnswerKind, text: string): string {
  return kind === "number" ? formatNumber(key, text) : text;
}

/** Whether a text is a plain number (the shape the server gives and a person types: digits, a point, perhaps an exponent). */
export function isPlainNumber(text: string): boolean {
  return /^-?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text);
}
