import { E, XlError, type Value } from "./types";

/**
 * How values are converted, compared, rounded and shown (SH2). A value is null (an empty cell), a number, a string, a
 * boolean or an XlError. The rules follow the documented behaviour of the spreadsheet functions and are the same as the
 * server's (`FormulaValues`): one file of cases holds both to them. There is no locale: a dot is the decimal point and
 * the nine formats of TEXT are fixed.
 */

/** An error that stops the function being calculated and becomes its value. */
export class Signal extends Error {
  readonly error: XlError;

  constructor(error: XlError) {
    super(error.code);
    this.error = error;
  }
}

const PLAIN_NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i;
const PERCENT_BODY = /^[+-]?(\d+\.?\d*|\.\d+)$/;

export const isNumberText = (s: string): boolean => PLAIN_NUMBER.test(s);
export const isPercentBody = (s: string): boolean => PERCENT_BODY.test(s);

export function toNumber(v: Value): number {
  if (v instanceof XlError) throw new Signal(v);
  if (v === null) return 0;
  if (typeof v === "boolean") return v ? 1 : 0;
  if (typeof v === "number") return v;
  const s = v.trim();
  if (s === "") throw new Signal(E.VALUE);
  const plain = s.replaceAll(",", "");
  if (!isNumberText(plain)) throw new Signal(E.VALUE);
  return Number(plain);
}

export function toBool(v: Value): boolean {
  if (v instanceof XlError) throw new Signal(v);
  if (v === null) return false;
  if (typeof v === "boolean") return v;
  if (typeof v === "number") return v !== 0;
  const s = v.toUpperCase();
  if (s === "TRUE") return true;
  if (s === "FALSE") return false;
  throw new Signal(E.VALUE);
}

/** A number as a cell shows it before any format: fifteen significant digits, no trailing zeros. */
export function numberText(n: number): string {
  if (n === 0 || !Number.isFinite(n)) return "0";
  return String(Number(n.toPrecision(15)));
}

export function toText(v: Value): string {
  if (v instanceof XlError) throw new Signal(v);
  if (v === null) return "";
  if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
  if (typeof v === "number") return numberText(v);
  return v;
}

const rank = (v: Value): number => (typeof v === "number" ? 1 : typeof v === "string" ? 2 : 3);

/** Orders two values as a comparison does: an empty cell takes the kind of the other side, kinds rank by number, text, logical. */
export function compare(a: Value, b: Value): number {
  if (a instanceof XlError) throw new Signal(a);
  if (b instanceof XlError) throw new Signal(b);
  let x: Value = a;
  let y: Value = b;
  if (x === null) x = typeof y === "string" ? "" : typeof y === "boolean" ? false : 0;
  if (y === null) y = typeof x === "string" ? "" : typeof x === "boolean" ? false : 0;
  if (rank(x) !== rank(y)) return rank(x) - rank(y);
  if (typeof x === "string" && typeof y === "string") {
    const p = x.toLowerCase();
    const q = y.toLowerCase();
    return p < q ? -1 : p > q ? 1 : 0;
  }
  if (typeof x === "number" && typeof y === "number") return x < y ? -1 : x > y ? 1 : 0;
  return x === y ? 0 : x ? 1 : -1;
}

/** A result of arithmetic that left the range of numbers is an error, not an infinity. */
export function finite(x: number): number {
  if (!Number.isFinite(x)) throw new Signal(E.NUM);
  return x;
}

/** x moved by d decimal places without the binary error of x * 10^d (2.675 stays 2.675). */
export function shift(x: number, d: number): number {
  if (x === 0 || !Number.isFinite(x)) return x;
  const [m, e] = x.toExponential(14).split("e");
  return Number(`${m}e${Number(e) + d}`);
}

const digits = (d: number): number => Math.max(-30, Math.min(30, Math.trunc(d)));

export function roundHalfUp(x: number, places: number): number {
  const n = digits(places);
  const sign = x < 0 ? -1 : 1;
  return sign * shift(Math.round(shift(Math.abs(x), n)), -n);
}

export function roundAway(x: number, places: number): number {
  const n = digits(places);
  const sign = x < 0 ? -1 : 1;
  return sign * shift(Math.ceil(shift(Math.abs(x), n) - 1e-12), -n);
}

export function roundToward(x: number, places: number): number {
  const n = digits(places);
  const sign = x < 0 ? -1 : 1;
  return sign * shift(Math.floor(shift(Math.abs(x), n) + 1e-12), -n);
}

export function power(x: number, y: number): number {
  if (x === 0 && y === 0) throw new Signal(E.NUM);
  if (x === 0 && y < 0) throw new Signal(E.DIV0);
  return finite(Math.pow(x, y));
}

/** The number formatted as one of the nine formats of TEXT. */
export function formatNumber(x: number, format: string): string {
  const percent = format.endsWith("%");
  const body = percent ? format.slice(0, -1) : format;
  const comma = body.includes(",");
  const places = body.includes(".") ? body.length - body.indexOf(".") - 1 : 0;
  const v = roundHalfUp(percent ? x * 100 : x, places);
  let s = Math.abs(v) >= 1e21 ? numberText(Math.abs(v)) : Math.abs(v).toFixed(places);
  if (comma) {
    const dot = s.indexOf(".");
    const whole = dot < 0 ? s : s.slice(0, dot);
    const fraction = dot < 0 ? "" : s.slice(dot);
    s = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + fraction;
  }
  const negative = v < 0 && Number(s.replaceAll(",", "")) !== 0;
  return `${negative ? "-" : ""}${s}${percent ? "%" : ""}`;
}

/** Whether the text matches the pattern, where * is any run and ? any one character (no backtracking blow-up). */
export function glob(pattern: string, text: string): boolean {
  let p = 0;
  let t = 0;
  let star = -1;
  let mark = 0;
  while (t < text.length) {
    if (p < pattern.length && pattern.charAt(p) !== "*" && (pattern.charAt(p) === "?" || pattern.charAt(p) === text.charAt(t))) {
      p++;
      t++;
    } else if (p < pattern.length && pattern.charAt(p) === "*") {
      star = p++;
      mark = t;
    } else if (star >= 0) {
      p = star + 1;
      t = ++mark;
    } else {
      return false;
    }
  }
  while (p < pattern.length && pattern.charAt(p) === "*") p++;
  return p === pattern.length;
}

export type Test = (value: Value) => boolean;

/** The test a criterion of SUMIF, COUNTIF and AVERAGEIF stands for: an operator, then a number or a text that may hold * and ?. */
export function criterion(c: Value): Test {
  if (c instanceof XlError) throw new Signal(c);
  if (typeof c === "number") return (v) => typeof v === "number" && v === c;
  if (typeof c === "boolean") return (v) => v === c;
  if (c === null) return (v) => typeof v === "number" && v === 0;
  let rest = c;
  let op = "=";
  for (const o of [">=", "<=", "<>", "=", ">", "<"]) {
    if (rest.startsWith(o)) {
      op = o;
      rest = rest.slice(o.length);
      break;
    }
  }
  const trimmed = rest.trim();
  if (trimmed !== "" && isNumberText(trimmed)) {
    const number = Number(rest);
    return (v) => {
      const numeric = typeof v === "number";
      switch (op) {
        case "=":
          return numeric && v === number;
        case "<>":
          return !(numeric && v === number);
        case ">":
          return numeric && v > number;
        case ">=":
          return numeric && v >= number;
        case "<":
          return numeric && v < number;
        default:
          return numeric && v <= number;
      }
    };
  }
  const pattern = rest.toLowerCase();
  if (op === "=") return (v) => typeof v === "string" && glob(pattern, v.toLowerCase());
  if (op === "<>") return (v) => !(typeof v === "string" && glob(pattern, v.toLowerCase()));
  return (v) => {
    if (typeof v !== "string") return false;
    const x = v.toLowerCase();
    return op === ">" ? x > pattern : op === ">=" ? x >= pattern : op === "<" ? x < pattern : x <= pattern;
  };
}
