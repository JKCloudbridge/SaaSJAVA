import { FormulaRefused, type FormulaSpec, type Node, type Ref } from "./types";

/**
 * The gate (SH2, ADR-0252), in the order the decision fixes: the length and the nesting of brackets are checked on the
 * text before anything parses it; then the text is split into parts and the parser admits only the closed set (numbers,
 * text, TRUE and FALSE, references and ranges, the arithmetic and comparison operators, percent, signs, brackets and
 * the calls of the allowed functions). Anything else is refused with a stable reason that never repeats the formula.
 * It is the same grammar as the server's parser (`FormulaParser`), held to the same file of cases.
 */

type Tok =
  | { k: "num"; text: string }
  | { k: "text"; text: string }
  | { k: "ref"; ref: Ref }
  | { k: "word"; text: string }
  | { k: "op"; text: string };

const digit = (c: string): boolean => c >= "0" && c <= "9";
const letter = (c: string): boolean => (c >= "a" && c <= "z") || (c >= "A" && c <= "Z");
const idChar = (c: string): boolean => digit(c) || letter(c) || c === "_" || c === "-";

function identifier(s: string): boolean {
  if (s.length === 0 || s.length > 40) return false;
  for (const c of s) if (!idChar(c)) return false;
  return true;
}

/** The inside of a reference token: COL, COL,ROW, COL,ROW,cr, COL,,c or COL,*. */
export function referenceToken(inside: string): Ref {
  const parts = inside.split(",");
  const first = parts[0] ?? "";
  if (parts.length > 3 || !identifier(first)) throw new FormulaRefused("bad_reference_token");
  const row = parts.length > 1 ? (parts[1] ?? "") : "";
  const flags = parts.length > 2 ? (parts[2] ?? "") : "";
  if (parts.length === 2 && row === "") throw new FormulaRefused("bad_reference_token");
  if (row === "*") {
    if (parts.length > 2) throw new FormulaRefused("bad_reference_token");
    return { t: "ref", column: first, row: null, absoluteColumn: false, absoluteRow: false, growing: true };
  }
  if (row !== "" && !identifier(row)) throw new FormulaRefused("bad_reference_token");
  if (parts.length > 2 && !(flags === "c" || flags === "r" || flags === "cr")) throw new FormulaRefused("bad_reference_token");
  const absoluteColumn = flags.includes("c");
  const absoluteRow = flags.includes("r");
  if (row === "" && absoluteRow) throw new FormulaRefused("bad_reference_token");
  return { t: "ref", column: first, row: row === "" ? null : row, absoluteColumn, absoluteRow, growing: false };
}

/** The one spelling of a number: plain digits, no leading zero, no trailing zero after the point, no exponent. */
export function canonicalNumber(text: string): string {
  let mantissa = text;
  let exponent = 0;
  const e = Math.max(text.indexOf("e"), text.indexOf("E"));
  if (e >= 0) {
    mantissa = text.slice(0, e);
    const raw = text.slice(e + 1);
    const unsigned = raw.startsWith("+") || raw.startsWith("-") ? raw.slice(1) : raw;
    if (unsigned.length > 4) throw new FormulaRefused("number_range");
    exponent = Number(raw.startsWith("+") ? raw.slice(1) : raw);
  }
  const dot = mantissa.indexOf(".");
  const whole = dot < 0 ? mantissa : mantissa.slice(0, dot);
  const fraction = dot < 0 ? "" : mantissa.slice(dot + 1);
  let digits = whole + fraction;
  let scale = exponent - fraction.length;
  let start = 0;
  while (start < digits.length && digits[start] === "0") start++;
  digits = digits.slice(start);
  if (digits === "") return "0";
  let end = digits.length;
  while (end > 1 && digits[end - 1] === "0") {
    end--;
    scale++;
  }
  digits = digits.slice(0, end);
  if (digits.length > 20) throw new FormulaRefused("number_range");
  if (scale >= 0) {
    if (digits.length + scale > 30) throw new FormulaRefused("number_range");
    return digits + "0".repeat(scale);
  }
  const places = -scale;
  if (places > 30) throw new FormulaRefused("number_range");
  if (digits.length > places) {
    const split = digits.length - places;
    return `${digits.slice(0, split)}.${digits.slice(split)}`;
  }
  return `0.${"0".repeat(places - digits.length)}${digits}`;
}

function bracketDepth(text: string, most: number): void {
  let depth = 0;
  let quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") {
      if (++depth > most) throw new FormulaRefused("too_deep");
    } else if (!quoted && c === ")" && depth > 0) depth--;
  }
}

function tokenize(s: string, spec: FormulaSpec): Tok[] {
  const tokens: Tok[] = [];
  let i = 0;
  while (i < s.length) {
    const c = s.charAt(i);
    if (c === " " || c === "\t" || c === "\r" || c === "\n") {
      i++;
    } else if (c === '"') {
      let value = "";
      let k = i + 1;
      let closed = false;
      while (k < s.length) {
        if (s.charAt(k) === '"') {
          if (s.charAt(k + 1) === '"') {
            value += '"';
            k += 2;
            continue;
          }
          closed = true;
          k++;
          break;
        }
        value += s.charAt(k++);
      }
      if (!closed) throw new FormulaRefused("unclosed_text");
      tokens.push({ k: "text", text: value });
      i = k;
    } else if (c === "@") {
      const close = s.indexOf("}", i);
      if (!s.startsWith("@{", i) || close < 0) throw new FormulaRefused("bad_reference_token");
      tokens.push({ k: "ref", ref: referenceToken(s.slice(i + 2, close)) });
      i = close + 1;
    } else if (digit(c) || (c === "." && digit(s.charAt(i + 1)))) {
      let k = i;
      while (k < s.length && digit(s.charAt(k))) k++;
      if (s.charAt(k) === ".") {
        k++;
        while (k < s.length && digit(s.charAt(k))) k++;
      }
      if (s.charAt(k) === "e" || s.charAt(k) === "E") {
        let m = k + 1;
        if (s.charAt(m) === "+" || s.charAt(m) === "-") m++;
        if (digit(s.charAt(m))) {
          while (m < s.length && digit(s.charAt(m))) m++;
          k = m;
        }
      }
      tokens.push({ k: "num", text: s.slice(i, k) });
      i = k;
    } else if (letter(c)) {
      let k = i;
      while (k < s.length && (letter(s.charAt(k)) || digit(s.charAt(k)) || s.charAt(k) === "_" || s.charAt(k) === ".")) k++;
      tokens.push({ k: "word", text: s.slice(i, k).toUpperCase() });
      i = k;
    } else {
      const two = s.slice(i, i + 2);
      if (two === "<=" || two === ">=" || two === "<>") {
        tokens.push({ k: "op", text: two });
        i += 2;
      } else if ("+-*/^%=<>(),:".includes(c)) {
        tokens.push({ k: "op", text: c });
        i++;
      } else {
        throw new FormulaRefused("character_not_allowed");
      }
    }
  }
  if (tokens.length > spec.maxNodes * 3) throw new FormulaRefused("too_complex");
  return tokens;
}

/**
 * Parses a formula in its stored form (without a leading equals sign).
 *
 * @throws FormulaRefused with the reason, when the formula is not accepted
 */
export function parse(stored: string, spec: FormulaSpec): Node {
  if (stored.trim() === "") throw new FormulaRefused("empty_formula");
  if (stored.length > spec.maxChars) throw new FormulaRefused("too_long");
  bracketDepth(stored, spec.maxDepth);
  const tokens = tokenize(stored, spec);
  let at = 0;
  let depth = 0;
  let nodes = 0;

  const peek = (): Tok | undefined => tokens[at];
  const isOp = (text: string): boolean => {
    const t = peek();
    return t !== undefined && t.k === "op" && t.text === text;
  };
  const enter = (): void => {
    if (++depth > spec.maxDepth) throw new FormulaRefused("too_deep");
  };
  const made = <T extends Node>(node: T): T => {
    if (++nodes > spec.maxNodes) throw new FormulaRefused("too_complex");
    return node;
  };

  const comparison = (): Node => {
    let left = additive();
    for (;;) {
      const t = peek();
      if (t && t.k === "op" && ["=", "<>", "<", "<=", ">", ">="].includes(t.text)) {
        at++;
        left = made({ t: "bin", op: t.text, left, right: additive() });
      } else return left;
    }
  };
  const additive = (): Node => {
    let left = multiplicative();
    while (isOp("+") || isOp("-")) {
      const op = (tokens[at++] as { text: string }).text;
      left = made({ t: "bin", op, left, right: multiplicative() });
    }
    return left;
  };
  const multiplicative = (): Node => {
    let left = power();
    while (isOp("*") || isOp("/")) {
      const op = (tokens[at++] as { text: string }).text;
      left = made({ t: "bin", op, left, right: power() });
    }
    return left;
  };
  const power = (): Node => {
    let left = unary();
    while (isOp("^")) {
      at++;
      left = made({ t: "bin", op: "^", left, right: unary() });
    }
    return left;
  };
  const unary = (): Node => {
    if (isOp("-")) {
      at++;
      enter();
      try {
        return made({ t: "neg", operand: unary() });
      } finally {
        depth--;
      }
    }
    if (isOp("+")) {
      // A plus sign changes nothing: it has no place in the one form of a formula.
      at++;
      enter();
      try {
        return unary();
      } finally {
        depth--;
      }
    }
    return postfix();
  };
  const postfix = (): Node => {
    let operand = primary();
    while (isOp("%")) {
      at++;
      operand = made({ t: "pct", operand });
    }
    return operand;
  };
  const primary = (): Node => {
    const t = peek();
    if (t === undefined) throw new FormulaRefused("unexpected_end");
    at++;
    switch (t.k) {
      case "num": {
        const text = canonicalNumber(t.text);
        return made({ t: "num", value: Number(text), text });
      }
      case "text":
        return made({ t: "text", value: t.text });
      case "ref":
        return reference(t.ref);
      case "word":
        return word(t.text);
      default:
        break;
    }
    if (t.text === "(") {
      enter();
      const inner = comparison();
      depth--;
      if (!isOp(")")) throw new FormulaRefused("missing_bracket");
      at++;
      return made({ t: "group", inner });
    }
    throw new FormulaRefused("unexpected_token");
  };
  const reference = (first: Ref): Node => {
    if (isOp(":")) {
      at++;
      const second = peek();
      if (second === undefined || second.k !== "ref") throw new FormulaRefused("bad_range");
      at++;
      const to = second.ref;
      if ((first.growing || to.growing) && first.column !== to.column) throw new FormulaRefused("bad_range");
      return made({ t: "range", from: first, to });
    }
    return made(first);
  };
  const word = (name: string): Node => {
    if (name === "TRUE" || name === "FALSE") return made({ t: "bool", value: name === "TRUE" });
    if (!isOp("(")) throw new FormulaRefused("name_not_allowed");
    const arity = spec.functions[name];
    if (arity === undefined) throw new FormulaRefused("function_not_allowed");
    at++;
    enter();
    const args: Node[] = [];
    if (!isOp(")")) {
      for (;;) {
        if (isOp(",") || isOp(")")) throw new FormulaRefused("empty_argument");
        args.push(comparison());
        if (isOp(",")) {
          at++;
          continue;
        }
        break;
      }
    }
    depth--;
    if (!isOp(")")) throw new FormulaRefused("missing_bracket");
    at++;
    if (args.length < arity[0] || args.length > arity[1]) throw new FormulaRefused("wrong_argument_count");
    if (name === "COUNT") {
      for (const arg of args) {
        let bare = arg;
        while (bare.t === "group") bare = bare.inner;
        if (bare.t !== "ref" && bare.t !== "range") throw new FormulaRefused("count_needs_cells");
      }
    }
    if (name === "TEXT") {
      const format = args[1];
      if (!(format && format.t === "text" && spec.textFormats.includes(format.value))) {
        throw new FormulaRefused("text_format_not_allowed");
      }
    }
    return made({ t: "call", name, args });
  };

  const tree = comparison();
  if (at < tokens.length) throw new FormulaRefused("unexpected_token");
  return tree;
}
