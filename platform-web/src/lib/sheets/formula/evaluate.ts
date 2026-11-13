import {
  compare,
  criterion,
  finite,
  formatNumber,
  isPercentBody,
  numberText,
  power,
  roundAway,
  roundHalfUp,
  roundToward,
  Signal,
  toBool,
  toNumber,
  toText,
} from "./values";
import { E, XlError, type Grid, type Node, type Value } from "./types";

/**
 * Calculates a formula by interpreting its tree (SH2, ADR-0252). Nothing a person typed is ever run as code: the tree
 * has five kinds of part, the functions are a fixed table, and every step is counted against a bound. The browser's
 * copy only shows a result while an author types; the server's answer is the one that counts, and both are held to one
 * file of cases. A bound that is reached gives `#LIMIT!`.
 */

class LimitReached extends Error {}

/** The values of a range or of the whole body of a column. */
interface Cells {
  cells: Value[];
}

type Raw = Value | Cells;

const isCells = (v: Raw): v is Cells => typeof v === "object" && v !== null && "cells" in v;

export interface Bounds {
  maxSteps: number;
  maxTextChars: number;
}

/**
 * Calculates a tree.
 *
 * @param ownRow the row the formula is drawn at (what a reference without a row means)
 * @returns the value (an empty answer is 0)
 */
export function evaluate(tree: Node, ownRow: string, grid: Grid, bounds: Bounds): Value {
  let steps = 0;
  const tick = (count: number): void => {
    steps += count;
    if (steps > bounds.maxSteps) throw new LimitReached();
  };
  const bare = (node: Node): Node => {
    let n = node;
    while (n.t === "group") n = n.inner;
    return n;
  };
  const scalar = (v: Raw): Value => {
    if (isCells(v)) return v.cells.length === 1 ? (v.cells[0] ?? null) : E.VALUE;
    return v;
  };
  const growing = (column: string): Raw => {
    const body = grid.bodyRows();
    if (body.length === 0) return { cells: [] };
    const first = body[0] as string;
    const last = body[body.length - 1] as string;
    const cells = grid.range(column, first, column, last);
    tick(cells.length);
    return { cells };
  };
  const rangeOf = (node: Extract<Node, { t: "range" }>): Raw => {
    const body = grid.bodyRows();
    const open = node.from.growing || node.to.growing;
    const rowOf = (end: typeof node.from, isStart: boolean): string | null => {
      if (end.growing) return body.length === 0 ? null : (body[isStart ? 0 : body.length - 1] as string);
      return end.row ?? ownRow;
    };
    const first = rowOf(node.from, true);
    const last = rowOf(node.to, false);
    if (open && (first === null || last === null)) return { cells: [] };
    const cells = grid.range(node.from.column, first ?? ownRow, node.to.column, last ?? ownRow);
    tick(cells.length);
    return { cells };
  };

  const ev = (node: Node): Raw => {
    tick(1);
    switch (node.t) {
      case "num":
        return node.value;
      case "text":
        return node.value;
      case "bool":
        return node.value;
      case "group":
        return ev(node.inner);
      case "ref":
        return node.growing ? growing(node.column) : grid.cell(node.column, node.row ?? ownRow);
      case "range":
        return rangeOf(node);
      case "neg":
        try {
          return -toNumber(scalar(ev(node.operand)));
        } catch (e) {
          if (e instanceof Signal) return e.error;
          throw e;
        }
      case "pct":
        try {
          return toNumber(scalar(ev(node.operand))) / 100;
        } catch (e) {
          if (e instanceof Signal) return e.error;
          throw e;
        }
      case "bin":
        return binary(node);
      case "call":
        try {
          return fn(node);
        } catch (e) {
          if (e instanceof Signal) return e.error;
          throw e;
        }
    }
  };

  const binary = (n: Extract<Node, { t: "bin" }>): Raw => {
    const left = scalar(ev(n.left));
    const right = scalar(ev(n.right));
    try {
      switch (n.op) {
        case "=":
        case "<>":
        case "<":
        case "<=":
        case ">":
        case ">=": {
          const c = compare(left, right);
          return n.op === "=" ? c === 0 : n.op === "<>" ? c !== 0 : n.op === "<" ? c < 0 : n.op === "<=" ? c <= 0 : n.op === ">" ? c > 0 : c >= 0;
        }
        default: {
          const x = toNumber(left);
          const y = toNumber(right);
          switch (n.op) {
            case "+":
              return finite(x + y);
            case "-":
              return finite(x - y);
            case "*":
              return finite(x * y);
            case "/":
              if (y === 0) throw new Signal(E.DIV0);
              return finite(x / y);
            default:
              return power(x, y);
          }
        }
      }
    } catch (e) {
      if (e instanceof Signal) return e.error;
      throw e;
    }
  };

  const num = (node: Node): number => toNumber(scalar(ev(node)));

  /** The numbers an aggregate reads: in a reference or range only numbers count; a direct argument is converted. */
  const numbersOf = (args: Node[]): number[] => {
    const out: number[] = [];
    for (const a of args) {
      const v = ev(a);
      if (isCells(v)) {
        for (const c of v.cells) {
          if (c instanceof XlError) throw new Signal(c);
          if (typeof c === "number") out.push(c);
        }
      } else if (bare(a).t === "ref") {
        if (v instanceof XlError) throw new Signal(v);
        if (typeof v === "number") out.push(v);
      } else {
        out.push(toNumber(v));
      }
    }
    return out;
  };

  const logicalsOf = (args: Node[]): boolean[] => {
    const out: boolean[] = [];
    for (const a of args) {
      const v = ev(a);
      const items: Value[] = isCells(v) ? v.cells : [v];
      const fromCells = bare(a).t === "ref" || isCells(v);
      for (const c of items) {
        if (c instanceof XlError) throw new Signal(c);
        if (fromCells && (typeof c === "string" || c === null)) continue;
        out.push(toBool(c));
      }
    }
    if (out.length === 0) throw new Signal(E.VALUE);
    return out;
  };

  const rangeArg = (node: Node): Value[] => {
    const v = ev(node);
    if (isCells(v)) return v.cells;
    if (bare(node).t === "ref") return [v];
    throw new Signal(E.VALUE);
  };

  const conditional = (a: Node[], kind: string): Value => {
    const range = rangeArg(a[0] as Node);
    const test = criterion(scalar(ev(a[1] as Node)));
    const target = a.length === 3 ? rangeArg(a[2] as Node) : range;
    let count = 0;
    let sum = 0;
    let n = 0;
    range.forEach((cell, i) => {
      if (cell instanceof XlError || !test(cell)) return;
      count++;
      const t = target[i] ?? null;
      if (typeof t === "number") {
        sum += t;
        n++;
      }
    });
    if (kind === "COUNTIF") return count;
    if (kind === "SUMIF") return sum;
    if (n === 0) throw new Signal(E.DIV0);
    return sum / n;
  };

  const valueOf = (v: Value): Value => {
    if (v instanceof XlError) return v;
    if (v === null) return 0;
    if (typeof v === "number") return v;
    if (typeof v === "boolean") return E.VALUE;
    const s = v.trim();
    if (s.endsWith("%")) {
      const body = s.slice(0, -1).replaceAll(",", "");
      return isPercentBody(body) ? Number(body) / 100 : E.VALUE;
    }
    try {
      return toNumber(s);
    } catch (e) {
      if (e instanceof Signal) return e.error;
      throw e;
    }
  };

  const fn = (n: Extract<Node, { t: "call" }>): Raw => {
    const a = n.args;
    const arg = (i: number): Node => a[i] as Node;
    switch (n.name) {
      case "IF":
        if (toBool(scalar(ev(arg(0))))) return scalar(ev(arg(1)));
        return a.length === 3 ? scalar(ev(arg(2))) : false;
      case "IFERROR": {
        const v = scalar(ev(arg(0)));
        return v instanceof XlError ? scalar(ev(arg(1))) : v;
      }
      case "IFS": {
        if (a.length % 2 !== 0) return E.NA;
        for (let i = 0; i < a.length; i += 2) {
          if (toBool(scalar(ev(arg(i))))) return scalar(ev(arg(i + 1)));
        }
        return E.NA;
      }
      case "SWITCH": {
        const x = scalar(ev(arg(0)));
        if (x instanceof XlError) return x;
        const pairs = Math.floor((a.length - 1) / 2);
        const hasDefault = (a.length - 1) % 2 === 1;
        for (let i = 0; i < pairs; i++) {
          const candidate = scalar(ev(arg(1 + 2 * i)));
          if (!(candidate instanceof XlError) && compare(x, candidate) === 0) return scalar(ev(arg(2 + 2 * i)));
        }
        return hasDefault ? scalar(ev(arg(a.length - 1))) : E.NA;
      }
      case "ABS":
        return Math.abs(num(arg(0)));
      case "INT":
        return Math.floor(num(arg(0)));
      case "SQRT": {
        const x = num(arg(0));
        if (x < 0) throw new Signal(E.NUM);
        return Math.sqrt(x);
      }
      case "MOD": {
        const x = num(arg(0));
        const d = num(arg(1));
        if (d === 0) throw new Signal(E.DIV0);
        return finite(x - d * Math.floor(x / d));
      }
      case "POWER": {
        const x = num(arg(0));
        const y = num(arg(1));
        return power(x, y);
      }
      case "ROUND":
      case "ROUNDUP":
      case "ROUNDDOWN": {
        const x = num(arg(0));
        const d = num(arg(1));
        return n.name === "ROUND" ? roundHalfUp(x, d) : n.name === "ROUNDUP" ? roundAway(x, d) : roundToward(x, d);
      }
      case "PRODUCT": {
        const x = numbersOf(a);
        let product = 1;
        for (const v of x) product = finite(product * v);
        return x.length > 0 ? product : 0;
      }
      case "SUM": {
        let sum = 0;
        for (const v of numbersOf(a)) sum = finite(sum + v);
        return sum;
      }
      case "MIN": {
        const x = numbersOf(a);
        return x.length > 0 ? x.reduce((m, v) => (v < m ? v : m)) : 0;
      }
      case "MAX": {
        const x = numbersOf(a);
        return x.length > 0 ? x.reduce((m, v) => (v > m ? v : m)) : 0;
      }
      case "AVERAGE": {
        const x = numbersOf(a);
        if (x.length === 0) throw new Signal(E.DIV0);
        let sum = 0;
        for (const v of x) sum = finite(sum + v);
        return sum / x.length;
      }
      case "MEDIAN": {
        const x = numbersOf(a);
        if (x.length === 0) throw new Signal(E.NUM);
        const sorted = [...x].sort((p, q) => p - q);
        const m = sorted.length >> 1;
        return sorted.length % 2 === 1 ? (sorted[m] as number) : ((sorted[m - 1] as number) + (sorted[m] as number)) / 2;
      }
      case "MODE": {
        const x = numbersOf(a);
        const frequency = new Map<number, number>();
        for (const v of x) frequency.set(v, (frequency.get(v) ?? 0) + 1);
        let best: number | null = null;
        let top = 1;
        for (const v of x) {
          const f = frequency.get(v) ?? 0;
          if (f > top) {
            top = f;
            best = v;
          }
        }
        if (best === null) throw new Signal(E.NA);
        return best;
      }
      case "COUNT": {
        let count = 0;
        for (const node of a) {
          const v = ev(node);
          for (const cell of isCells(v) ? v.cells : [v]) if (typeof cell === "number") count++;
        }
        return count;
      }
      case "COUNTA": {
        let count = 0;
        for (const node of a) {
          const v = ev(node);
          for (const cell of isCells(v) ? v.cells : [v]) if (cell !== null) count++;
        }
        return count;
      }
      case "LARGE":
      case "SMALL": {
        const r = rangeArg(arg(0));
        const xs: number[] = [];
        for (const c of r) {
          if (c instanceof XlError) throw new Signal(c);
          if (typeof c === "number") xs.push(c);
        }
        const k = Math.ceil(num(arg(1)));
        if (k < 1 || k > xs.length) throw new Signal(E.NUM);
        const sorted = [...xs].sort((p, q) => p - q);
        return n.name === "LARGE" ? (sorted[sorted.length - k] as number) : (sorted[k - 1] as number);
      }
      case "AND":
        return logicalsOf(a).every(Boolean);
      case "OR":
        return logicalsOf(a).some(Boolean);
      case "NOT":
        return !toBool(scalar(ev(arg(0))));
      case "ISBLANK": {
        const inner = bare(arg(0));
        if (inner.t !== "ref" || inner.growing) return false;
        return ev(arg(0)) === null;
      }
      case "ISNUMBER":
        return typeof scalar(ev(arg(0))) === "number";
      case "ISTEXT":
        return typeof scalar(ev(arg(0))) === "string";
      case "SUMIF":
      case "COUNTIF":
      case "AVERAGEIF":
        return conditional(a, n.name);
      case "VALUE":
        return valueOf(scalar(ev(arg(0))));
      case "TEXT": {
        const v = scalar(ev(arg(0)));
        if (v instanceof XlError) return v;
        if (typeof v === "string") return v;
        if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
        const format = arg(1);
        return formatNumber(v === null ? 0 : v, format.t === "text" ? format.value : "0");
      }
      case "CONCATENATE": {
        let out = "";
        for (const node of a) {
          const v = ev(node);
          if (isCells(v)) return E.VALUE;
          out += toText(v);
          if (out.length > bounds.maxTextChars) throw new LimitReached();
        }
        return out;
      }
      case "LEFT":
      case "RIGHT": {
        const t = toText(scalar(ev(arg(0))));
        const k = a.length === 2 ? num(arg(1)) : 1;
        if (k < 0) throw new Signal(E.VALUE);
        const c = Math.floor(k);
        if (n.name === "LEFT") return t.slice(0, c);
        return c === 0 ? "" : t.slice(-c);
      }
      case "LEN":
        return [...toText(scalar(ev(arg(0))))].length;
      case "UPPER":
        return toText(scalar(ev(arg(0)))).toUpperCase();
      case "TRIM":
        return toText(scalar(ev(arg(0)))).replace(/ +/g, " ").trim();
      default:
        return E.NAME;
    }
  };

  try {
    const result = scalar(ev(tree));
    return result === null ? 0 : result;
  } catch (e) {
    if (e instanceof Signal) return e.error;
    if (e instanceof LimitReached) return E.LIMIT;
    throw e;
  }
}

/** A value as a cell shows it before any format (an error as its code). */
export function show(v: Value): string {
  if (v === null) return "";
  if (v instanceof XlError) return v.code;
  if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
  if (typeof v === "number") return numberText(v);
  return v;
}
