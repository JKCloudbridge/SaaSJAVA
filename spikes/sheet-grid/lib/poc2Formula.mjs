// POC 2, WP5 (browser side, throwaway): the evaluator of POC 1 (lib/poc1Formula.mjs, which stays as it was) extended with
// - the larger function set (arithmetic, conditional, statistical, casting and text),
// - the GROWING RANGE token: @{COL,*} is "column COL over every body row of the sheet, first to last, however many rows the filler
//   added", and @{COL,ROW}:@{COL,*} / @{COL,*}:@{COL,ROW} are ranges with an open end. The total row is not part of its own range
//   because a row flagged as a total is not a body row; a formula in a body row may not name its own column with the token.
// The stored form is otherwise the one of ADR-0252. Nothing is ever run as code: the text is parsed into a tree and the tree is
// interpreted. Bounds as the expression engine: 4000 characters, 32 levels.

export const MAX_CHARS = 4000;
export const MAX_DEPTH = 32;
/** name -> [fewest arguments, most arguments]. The allowlist of the gate; anything else is refused with function_not_allowed. */
export const ARITY = {
  ABS: [1, 1], MOD: [2, 2], INT: [1, 1], ROUND: [2, 2], ROUNDUP: [2, 2], ROUNDDOWN: [2, 2], SQRT: [1, 1], POWER: [2, 2], PRODUCT: [1, 255],
  IF: [2, 3], IFERROR: [2, 2], AND: [1, 255], OR: [1, 255], NOT: [1, 1], ISNUMBER: [1, 1], ISTEXT: [1, 1], ISBLANK: [1, 1],
  SUMIF: [2, 3], COUNTIF: [2, 2], AVERAGEIF: [2, 3], IFS: [2, 254], SWITCH: [3, 253],
  SUM: [1, 255], AVERAGE: [1, 255], MEDIAN: [1, 255], MODE: [1, 255], STDEV: [1, 255], VAR: [1, 255], COUNT: [1, 255], COUNTA: [1, 255],
  MIN: [1, 255], MAX: [1, 255], LARGE: [2, 2], SMALL: [2, 2], RANK: [2, 3],
  VALUE: [1, 1], TEXT: [2, 2], CONCATENATE: [1, 255], LEFT: [1, 2], RIGHT: [1, 2], LEN: [1, 1], UPPER: [1, 1], TRIM: [1, 1],
};
export const FUNCTIONS = new Set(Object.keys(ARITY));
export const TEXT_FORMATS = new Set(["0", "0.0", "0.00", "0.000", "0%", "0.0%", "0.00%", "#,##0", "#,##0.00"]);

export class XlError { constructor(code) { this.code = code; } toString() { return this.code; } }
export const E = { VALUE: new XlError("#VALUE!"), DIV0: new XlError("#DIV/0!"), NUM: new XlError("#NUM!"), NAME: new XlError("#NAME?"), NA: new XlError("#N/A") };
export class FormulaRefused extends Error { constructor(key) { super(key); this.key = key; } }

// ---------------------------------------------------------------------------------------------------------------- translators

const A1 = /^(\$?)([A-Za-z]{1,3})(\$?)([0-9]{1,7})/;
const TOKEN = /^@\{([A-Za-z0-9_-]{1,40})(?:,([A-Za-z0-9_-]{0,40}|\*))?(?:,([cr]{0,2}))?}/;
const letters = (i) => { let s = ""; for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s; return s; };
const colIndex = (L) => [...L.toUpperCase()].reduce((v, ch) => v * 26 + ch.charCodeAt(0) - 64, 0) - 1;
const wordStart = (s, i) => /[A-Za-z$_]/.test(s[i]) && (i === 0 || !/[A-Za-z0-9_.$]/.test(s[i - 1]));
function endOfText(s, i) {
  for (let k = i + 1; k < s.length; k++) if (s[k] === '"') { if (s[k + 1] === '"') { k++; continue; } return k + 1; }
  throw new FormulaRefused("unclosed_text");
}
function refAt(excel, i) { // a plain reference at i, or null
  if (!wordStart(excel, i)) return null;
  const m = A1.exec(excel.slice(i)); if (!m) return null;
  const end = i + m[0].length; const nx = excel[end];
  if (nx && /[A-Za-z0-9_(.]/.test(nx)) return null;
  return { end, col: colIndex(m[2]), row: Number(m[4]), absC: m[1] !== "", absR: m[3] !== "" };
}

/**
 * Excel-style -> stored form. layout = { colIds, rowIds, bodyRowIds? } (position 0 = column A / row 1); row = 1-based row of the cell.
 * A range over EXACTLY every body row of one column (first body row to last) is stored as the growing token @{COL,*}. Any other range
 * keeps its two row identifiers.
 */
export function toIds(excel, row, layout) {
  if (excel.length > MAX_CHARS) throw new FormulaRefused("too_long");
  const body = layout.bodyRowIds ?? null;
  const posOf = (id) => layout.rowIds.indexOf(id) + 1;
  const bodyFirst = body && body.length ? posOf(body[0]) : null; const bodyLast = body && body.length ? posOf(body[body.length - 1]) : null;
  const token = (r, withRow) => {
    if (r.col >= layout.colIds.length) throw new FormulaRefused("column_outside_sheet");
    if (r.row < 1 || r.row > layout.rowIds.length) throw new FormulaRefused("row_outside_sheet");
    const same = r.row === row && !r.absR;
    return `@{${layout.colIds[r.col]}${same ? "" : "," + layout.rowIds[r.row - 1]}${r.absC || r.absR ? (same ? ",," : ",") + (r.absC ? "c" : "") + (r.absR ? "r" : "") : ""}}`;
  };
  let out = ""; let i = 0;
  while (i < excel.length) {
    const ch = excel[i];
    if (ch === '"') { const e = endOfText(excel, i); out += excel.slice(i, e); i = e; continue; }
    if ("@{[!'".includes(ch)) throw new FormulaRefused("character_not_allowed");
    const r = refAt(excel, i);
    if (r) {
      if (excel[r.end] === ":") {
        const r2 = refAt(excel, r.end + 1);
        if (r2 && r2.col === r.col && body && bodyFirst !== null && r.row === bodyFirst && r2.row === bodyLast && r.col < layout.colIds.length) {
          out += `@{${layout.colIds[r.col]},*}`; i = r2.end; continue;
        }
      }
      out += token(r); i = r.end; continue;
    }
    if (wordStart(excel, i)) { let e = i; while (e < excel.length && /[A-Za-z0-9_.]/.test(excel[e])) e++; out += excel.slice(i, e); i = Math.max(e, i + 1); continue; }
    out += ch; i++;
  }
  return out;
}

/** Stored form -> Excel-style text drawn at the given 1-based row, with the growing token drawn as the rows that exist now. */
export function toExcel(ids, row, layout) {
  const cols = new Map(layout.colIds.map((id, i) => [id, i])); const rows = new Map(layout.rowIds.map((id, i) => [id, i]));
  const body = layout.bodyRowIds ?? [];
  const firstNo = body.length ? rows.get(body[0]) + 1 : null; const lastNo = body.length ? rows.get(body[body.length - 1]) + 1 : null;
  let out = ""; let i = 0;
  while (i < ids.length) {
    if (ids[i] === '"') { const e = endOfText(ids, i); out += ids.slice(i, e); i = e; continue; }
    if (ids[i] === "@") {
      const m = TOKEN.exec(ids.slice(i));
      if (!m) throw new FormulaRefused("bad_reference_token");
      if (!cols.has(m[1])) throw new FormulaRefused("unknown_column");
      const L = letters(cols.get(m[1])); const f = m[3] ?? "";
      const after = ids[i + m[0].length]; const before = out[out.length - 1];
      if (m[2] === "*") {
        if (firstNo === null) throw new FormulaRefused("no_body_rows");
        if (after === ":") out += `${L}${firstNo}`;          // start of a range that runs from the first body row
        else if (before === ":") out += `${L}${lastNo}`;     // end of a range that runs to the last body row
        else out += `${L}${firstNo}:${L}${lastNo}`;           // the whole body of the column
      } else {
        let rowNo = row;
        if (m[2]) { if (!rows.has(m[2])) throw new FormulaRefused("unknown_row"); rowNo = rows.get(m[2]) + 1; }
        out += `${f.includes("c") ? "$" : ""}${L}${f.includes("r") ? "$" : ""}${rowNo}`;
      }
      i += m[0].length; continue;
    }
    out += ids[i++];
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------- parser

function tokenize(src) {
  const t = []; let i = 0;
  while (i < src.length) {
    const ch = src[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (ch === '"') { const e = endOfText(src, i); t.push({ k: "str", v: src.slice(i + 1, e - 1).replace(/""/g, '"') }); i = e; continue; }
    if (ch === "@") {
      const m = TOKEN.exec(src.slice(i));
      if (!m) throw new FormulaRefused("bad_reference_token");
      t.push({ k: "ref", col: m[1], row: m[2] || null }); i += m[0].length; continue;
    }
    const num = /^(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/.exec(src.slice(i));
    if (num) { t.push({ k: "num", v: Number(num[0]) }); i += num[0].length; continue; }
    const id = /^[A-Za-z][A-Za-z0-9_.]*/.exec(src.slice(i));
    if (id) { t.push({ k: "id", v: id[0].toUpperCase() }); i += id[0].length; continue; }
    const op = /^(<=|>=|<>|[-+*/^%=<>(),:])/.exec(src.slice(i));
    if (op) { t.push({ k: "op", v: op[0] }); i += op[0].length; continue; }
    throw new FormulaRefused("character_not_allowed");
  }
  return t;
}

export function parse(src) {
  if (src.length > MAX_CHARS) throw new FormulaRefused("too_long");
  const toks = tokenize(src); let p = 0; let depth = 0;
  const peek = () => toks[p]; const isOp = (v) => peek()?.k === "op" && peek().v === v;
  const enter = () => { if (++depth > MAX_DEPTH) throw new FormulaRefused("too_deep"); };
  function cmp() { let l = add(); while (peek()?.k === "op" && ["=", "<>", "<", "<=", ">", ">="].includes(peek().v)) { const op = toks[p++].v; l = { t: "bin", op, l, r: add() }; } return l; }
  function add() { let l = mul(); while (isOp("+") || isOp("-")) { const op = toks[p++].v; l = { t: "bin", op, l, r: mul() }; } return l; }
  function mul() { let l = pow(); while (isOp("*") || isOp("/")) { const op = toks[p++].v; l = { t: "bin", op, l, r: pow() }; } return l; }
  function pow() { let l = unary(); while (isOp("^")) { p++; l = { t: "bin", op: "^", l, r: unary() }; } return l; }
  function unary() { enter(); try { if (isOp("-")) { p++; return { t: "neg", x: unary() }; } if (isOp("+")) { p++; return unary(); } return postfix(); } finally { depth--; } }
  function postfix() { let x = primary(); while (isOp("%")) { p++; x = { t: "pct", x }; } return x; }
  function primary() {
    const t = toks[p++];
    if (!t) throw new FormulaRefused("unexpected_end");
    if (t.k === "num") return { t: "num", v: t.v };
    if (t.k === "str") return { t: "str", v: t.v };
    if (t.k === "ref") {
      if (isOp(":")) {
        p++; const t2 = toks[p++];
        if (t2?.k !== "ref") throw new FormulaRefused("bad_range");
        // an open end (the growing token) belongs to one column; a plain rectangle may span columns
        if ((t.row === "*" || t2.row === "*") && t.col !== t2.col) throw new FormulaRefused("bad_range");
        return { t: "range", a: t, b: t2 };
      }
      if (t.row === "*") return { t: "range", a: { col: t.col, row: "*" }, b: { col: t.col, row: "*" }, growing: true };
      return { t: "ref", col: t.col, row: t.row };
    }
    if (t.k === "op" && t.v === "(") { enter(); const x = cmp(); depth--; if (!isOp(")")) throw new FormulaRefused("missing_bracket"); p++; return x; }
    if (t.k === "id") {
      if (t.v === "TRUE" || t.v === "FALSE") return { t: "bool", v: t.v === "TRUE" };
      if (!isOp("(")) throw new FormulaRefused("name_not_allowed");
      if (!FUNCTIONS.has(t.v)) throw new FormulaRefused("function_not_allowed");
      p++; enter(); const args = [];
      if (!isOp(")")) { for (;;) { args.push(isOp(",") || isOp(")") ? { t: "missing" } : cmp()); if (isOp(",")) { p++; continue; } break; } }
      depth--; if (!isOp(")")) throw new FormulaRefused("missing_bracket"); p++;
      const [lo, hi] = ARITY[t.v];
      if (args.length < lo || args.length > hi) throw new FormulaRefused("wrong_argument_count");
      // the narrowed forms (decided on the evidence of WP5): where the second opinion (POI) disagrees with the documented behaviour
      // on a form that has no use, the form is refused instead of the function being given up
      if (t.v === "COUNT" && args.some((a) => a.t !== "ref" && a.t !== "range")) throw new FormulaRefused("count_needs_cells");
      if (t.v === "TEXT" && !(args[1].t === "str" && TEXT_FORMATS.has(args[1].v))) throw new FormulaRefused("text_format_not_allowed");
      return { t: "call", name: t.v, args };
    }
    throw new FormulaRefused("unexpected_token");
  }
  const tree = cmp();
  if (p < toks.length) throw new FormulaRefused("unexpected_token");
  return tree;
}

/** Refuses a growing token on the formula's own column when the formula sits in a body row (it would be part of its own range). */
export function checkGrowing(tree, ownColId, inBodyRow) {
  const walk = (n) => {
    if (!n || typeof n !== "object") return;
    if (n.t === "range" && (n.a.row === "*" || n.b.row === "*") && inBodyRow && (n.a.col === ownColId)) throw new FormulaRefused("circular");
    for (const k of ["l", "r", "x"]) if (n[k]) walk(n[k]);
    if (n.args) n.args.forEach(walk);
  };
  walk(tree);
}

// ---------------------------------------------------------------------------------------------------------------- evaluation

const isErr = (v) => v instanceof XlError;
const isRange = (v) => v && typeof v === "object" && "range" in v;
function toNumber(v) {
  if (isErr(v)) return v; if (v === undefined || v === null) return 0; if (typeof v === "boolean") return v ? 1 : 0;
  if (typeof v === "number") return v;
  const s = String(v).trim(); if (s === "") return E.VALUE;
  if (!/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(s.replace(/,/g, ""))) return E.VALUE;
  return Number(s.replace(/,/g, ""));
}
function toBool(v) {
  if (isErr(v)) return v; if (v === undefined || v === null) return false; if (typeof v === "boolean") return v; if (typeof v === "number") return v !== 0;
  const s = String(v).toUpperCase(); if (s === "TRUE") return true; if (s === "FALSE") return false; return E.VALUE;
}
/** A number as a cell shows it before any format: 15 significant digits, no trailing zeros. */
export function numberText(n) { return String(Number(n.toPrecision(15))); }
function toText(v) {
  if (isErr(v)) return v; if (v === undefined || v === null) return ""; if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
  if (typeof v === "number") return numberText(v);
  return String(v);
}
const rank = (v) => (typeof v === "number" ? 1 : typeof v === "string" ? 2 : 3);
function compare(a, b) {
  if (isErr(a)) return a; if (isErr(b)) return b;
  if (a === undefined || a === null) a = typeof b === "string" ? "" : typeof b === "boolean" ? false : 0;
  if (b === undefined || b === null) b = typeof a === "string" ? "" : typeof a === "boolean" ? false : 0;
  if (rank(a) !== rank(b)) return rank(a) - rank(b);
  if (typeof a === "string") { const x = a.toLowerCase(); const y = b.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0; }
  return a < b ? -1 : a > b ? 1 : 0;
}
/** x moved by d decimal places without the binary error of x * 10^d (2.675 stays 2.675). */
function shift(x, d) { const [m, e] = x.toExponential(14).split("e"); return Number(`${m}e${Number(e) + d}`); }
function roundHalfUp(x, n) { const s = x < 0 ? -1 : 1; const r = Math.round(shift(Math.abs(x), n)); return s * shift(r, -n); }
function roundAway(x, n) { const s = x < 0 ? -1 : 1; const r = Math.ceil(shift(Math.abs(x), n) - 1e-12); return s * shift(r, -n); }
function roundToward(x, n) { const s = x < 0 ? -1 : 1; const r = Math.floor(shift(Math.abs(x), n) + 1e-12); return s * shift(r, -n); }
function power(x, y) {
  if (x === 0 && y === 0) return E.NUM; if (x === 0 && y < 0) return E.DIV0;
  const r = x ** y; return Number.isFinite(r) ? r : E.NUM;
}
function formatNumber(x, fmt) {
  const pct = fmt.endsWith("%"); const body = pct ? fmt.slice(0, -1) : fmt; const comma = body.includes(",");
  const d = body.includes(".") ? body.length - body.indexOf(".") - 1 : 0;
  const v = roundHalfUp(pct ? x * 100 : x, d);
  let s = Math.abs(v).toFixed(d);
  if (comma) { const [i, f] = s.split("."); s = i.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + (f ? "." + f : ""); }
  return (v < 0 && Number(s.replace(/,/g, "")) !== 0 ? "-" : "") + s + (pct ? "%" : "");
}
/** The test a criterion of SUMIF, COUNTIF and AVERAGEIF stands for: an operator, then a number or a text that may hold * and ?. */
function criterion(c) {
  if (isErr(c)) return c;
  if (typeof c === "number") return (v) => typeof v === "number" && v === c;
  if (typeof c === "boolean") return (v) => v === c;
  if (c === undefined || c === null) return (v) => typeof v === "number" && v === 0;
  let s = String(c); let op = "=";
  for (const o of [">=", "<=", "<>", "=", ">", "<"]) if (s.startsWith(o)) { op = o; s = s.slice(o.length); break; }
  const asNumber = s.trim() !== "" && /^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(s.trim()) ? Number(s) : null;
  if (asNumber !== null) {
    return (v) => {
      const isNum = typeof v === "number";
      switch (op) {
        case "=": return isNum && v === asNumber; case "<>": return !(isNum && v === asNumber);
        case ">": return isNum && v > asNumber; case ">=": return isNum && v >= asNumber;
        case "<": return isNum && v < asNumber; default: return isNum && v <= asNumber;
      }
    };
  }
  const re = new RegExp(`^${s.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".")}$`, "i");
  if (op === "=") return (v) => typeof v === "string" && re.test(v);
  if (op === "<>") return (v) => !(typeof v === "string" && re.test(v));
  const low = s.toLowerCase();
  return (v) => { if (typeof v !== "string") return false; const x = v.toLowerCase(); return op === ">" ? x > low : op === ">=" ? x >= low : op === "<" ? x < low : x <= low; };
}

/**
 * ctx: { row: rowId of the cell being calculated, cell(colId,rowId): raw value or undefined, range(a,b): array of raw values,
 *        bodyRows(): the ids of the body rows in order (a total row is not one of them) }
 */
export function evaluate(tree, ctx) {
  const ev = (n) => {
    switch (n.t) {
      case "num": case "str": case "bool": return n.v;
      case "missing": return undefined;
      case "ref": return ctx.cell(n.col, n.row ?? ctx.row);
      case "range": {
        const body = n.a.row === "*" || n.b.row === "*" ? ctx.bodyRows() : null;
        const rowOf = (end, isStart) => (end.row === "*" ? (body.length ? body[isStart ? 0 : body.length - 1] : null) : end.row ?? ctx.row);
        const ra = rowOf(n.a, true); const rb = rowOf(n.b, false);
        if (ra === null || rb === null) return { range: [] };
        return { range: ctx.range({ col: n.a.col, row: ra }, { col: n.b.col, row: rb }) };
      }
      case "neg": { const x = toNumber(scalar(ev(n.x))); return isErr(x) ? x : -x; }
      case "pct": { const v = toNumber(scalar(ev(n.x))); return isErr(v) ? v : v / 100; }
      case "bin": return binary(n);
      case "call": return call(n);
      default: throw new FormulaRefused("bad_tree");
    }
  };
  const scalar = (v) => (isRange(v) ? (v.range.length === 1 ? v.range[0] : E.VALUE) : v);
  function binary(n) {
    const a = scalar(ev(n.l)); const b = scalar(ev(n.r));
    if (["=", "<>", "<", "<=", ">", ">="].includes(n.op)) {
      const c = compare(a, b); if (isErr(c)) return c;
      return { "=": c === 0, "<>": c !== 0, "<": c < 0, "<=": c <= 0, ">": c > 0, ">=": c >= 0 }[n.op];
    }
    const x = toNumber(a); if (isErr(x)) return x; const y = toNumber(b); if (isErr(y)) return y;
    switch (n.op) {
      case "+": return x + y; case "-": return x - y; case "*": return x * y;
      case "/": return y === 0 ? E.DIV0 : x / y;
      case "^": return power(x, y);
      default: throw new FormulaRefused("bad_operator");
    }
  }
  /** The numbers an aggregate reads: in a reference or a range only numbers count; a direct argument is converted. An error is returned. */
  function numbersOf(args) {
    const out = [];
    for (const a of args) {
      const v = ev(a);
      if (isRange(v)) { for (const c of v.range) { if (isErr(c)) return c; if (typeof c === "number") out.push(c); } }
      else if (a.t === "ref") { if (isErr(v)) return v; if (typeof v === "number") out.push(v); }
      else if (a.t === "missing") out.push(0);
      else { const n = toNumber(v); if (isErr(n)) return n; out.push(n); }
    }
    return out;
  }
  function logicalsOf(args) {
    const out = [];
    for (const a of args) {
      const v = ev(a);
      const items = isRange(v) ? v.range : [v]; const isRef = a.t === "ref" || isRange(v);
      for (const c of items) {
        if (isErr(c)) return c;
        if (isRef && (typeof c === "string" || c === undefined || c === null)) continue;
        const b = toBool(c); if (isErr(b)) return b; out.push(b);
      }
    }
    return out.length ? out : E.VALUE;
  }
  const num = (node) => toNumber(scalar(ev(node)));
  const rangeArg = (node) => { const v = ev(node); return isRange(v) ? v.range : node.t === "ref" ? [v] : E.VALUE; };
  function conditional(a, kind) {
    const rng = rangeArg(a[0]); if (isErr(rng)) return rng;
    const test = criterion(scalar(ev(a[1]))); if (isErr(test)) return test;
    const target = a.length === 3 ? rangeArg(a[2]) : rng; if (isErr(target)) return target;
    let count = 0; let sum = 0; let n = 0;
    rng.forEach((cell, i) => {
      if (isErr(cell) || !test(cell)) return;
      count++;
      const t = target[i]; if (typeof t === "number") { sum += t; n++; }
    });
    if (kind === "COUNTIF") return count;
    if (kind === "SUMIF") return sum;
    return n === 0 ? E.DIV0 : sum / n;
  }
  function call(n) {
    const a = n.args;
    switch (n.name) {
      case "IF": {
        const c = toBool(scalar(ev(a[0]))); if (isErr(c)) return c;
        if (c) return scalar(ev(a[1]));
        return a.length === 3 ? scalar(ev(a[2])) : false;
      }
      case "IFERROR": { const v = scalar(ev(a[0])); return isErr(v) ? scalar(ev(a[1])) : v; }
      case "IFS": {
        if (a.length % 2 !== 0) return E.NA;
        for (let i = 0; i < a.length; i += 2) { const c = toBool(scalar(ev(a[i]))); if (isErr(c)) return c; if (c) return scalar(ev(a[i + 1])); }
        return E.NA;
      }
      case "SWITCH": {
        const x = scalar(ev(a[0])); if (isErr(x)) return x;
        const pairs = Math.floor((a.length - 1) / 2); const hasDefault = (a.length - 1) % 2 === 1;
        for (let i = 0; i < pairs; i++) { const c = compare(x, scalar(ev(a[1 + 2 * i]))); if (!isErr(c) && c === 0) return scalar(ev(a[2 + 2 * i])); }
        return hasDefault ? scalar(ev(a[a.length - 1])) : E.NA;
      }
      case "ABS": { const x = num(a[0]); return isErr(x) ? x : Math.abs(x); }
      case "INT": { const x = num(a[0]); return isErr(x) ? x : Math.floor(x); }
      case "SQRT": { const x = num(a[0]); return isErr(x) ? x : x < 0 ? E.NUM : Math.sqrt(x); }
      case "MOD": { const x = num(a[0]); if (isErr(x)) return x; const d = num(a[1]); if (isErr(d)) return d; return d === 0 ? E.DIV0 : x - d * Math.floor(x / d); }
      case "POWER": { const x = num(a[0]); if (isErr(x)) return x; const y = num(a[1]); if (isErr(y)) return y; return power(x, y); }
      case "ROUND": case "ROUNDUP": case "ROUNDDOWN": {
        const x = num(a[0]); if (isErr(x)) return x; const d = num(a[1]); if (isErr(d)) return d;
        const f = n.name === "ROUND" ? roundHalfUp : n.name === "ROUNDUP" ? roundAway : roundToward; return f(x, Math.trunc(d));
      }
      case "PRODUCT": { const x = numbersOf(a); return isErr(x) ? x : x.length ? x.reduce((s, v) => s * v, 1) : 0; }
      case "SUM": { const x = numbersOf(a); return isErr(x) ? x : x.reduce((s, v) => s + v, 0); }
      case "MIN": { const x = numbersOf(a); return isErr(x) ? x : x.length ? Math.min(...x) : 0; }
      case "MAX": { const x = numbersOf(a); return isErr(x) ? x : x.length ? Math.max(...x) : 0; }
      case "AVERAGE": { const x = numbersOf(a); return isErr(x) ? x : x.length ? x.reduce((s, v) => s + v, 0) / x.length : E.DIV0; }
      case "MEDIAN": {
        const x = numbersOf(a); if (isErr(x)) return x; if (!x.length) return E.NUM;
        const s = [...x].sort((p, q) => p - q); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
      }
      case "MODE": {
        const x = numbersOf(a); if (isErr(x)) return x;
        const freq = new Map(); for (const v of x) freq.set(v, (freq.get(v) ?? 0) + 1);
        let best = null; let top = 1; for (const v of x) { if (freq.get(v) > top) { top = freq.get(v); best = v; } }
        return best === null ? E.NA : best;
      }
      case "STDEV": case "VAR": {
        const x = numbersOf(a); if (isErr(x)) return x; if (x.length < 2) return E.DIV0;
        const mean = x.reduce((s, v) => s + v, 0) / x.length; const v = x.reduce((s, q) => s + (q - mean) ** 2, 0) / (x.length - 1);
        return n.name === "VAR" ? v : Math.sqrt(v);
      }
      case "COUNT": { let c = 0; for (const arg of a) { const v = ev(arg); for (const cell of isRange(v) ? v.range : [v]) if (typeof cell === "number") c++; } return c; }
      case "COUNTA": {
        let c = 0;
        for (const arg of a) { const v = ev(arg); for (const cell of isRange(v) ? v.range : [v]) if (!(cell === undefined || cell === null)) c++; }
        return c;
      }
      case "LARGE": case "SMALL": {
        const r = rangeArg(a[0]); if (isErr(r)) return r;
        const xs = []; for (const c of r) { if (isErr(c)) return c; if (typeof c === "number") xs.push(c); }
        const k = num(a[1]); if (isErr(k)) return k; const kk = Math.ceil(k);
        if (kk < 1 || kk > xs.length) return E.NUM;
        xs.sort((p, q) => (n.name === "LARGE" ? q - p : p - q)); return xs[kk - 1];
      }
      case "RANK": {
        const x = num(a[0]); if (isErr(x)) return x;
        const r = rangeArg(a[1]); if (isErr(r)) return r;
        const order = a.length === 3 ? num(a[2]) : 0; if (isErr(order)) return order;
        const xs = r.filter((c) => typeof c === "number");
        if (!xs.includes(x)) return E.NA;
        return 1 + xs.filter((c) => (order === 0 ? c > x : c < x)).length;
      }
      case "AND": { const l = logicalsOf(a); return isErr(l) ? l : l.every(Boolean); }
      case "OR": { const l = logicalsOf(a); return isErr(l) ? l : l.some(Boolean); }
      case "NOT": { const b = toBool(scalar(ev(a[0]))); return isErr(b) ? b : !b; }
      case "ISBLANK": { if (a[0].t !== "ref") return false; const v = ev(a[0]); return v === undefined || v === null; }
      case "ISNUMBER": { const v = scalar(ev(a[0])); return typeof v === "number"; }
      case "ISTEXT": { const v = scalar(ev(a[0])); return typeof v === "string"; }
      case "SUMIF": case "COUNTIF": case "AVERAGEIF": return conditional(a, n.name);
      case "VALUE": {
        const v = scalar(ev(a[0])); if (isErr(v)) return v;
        if (v === undefined || v === null) return 0; if (typeof v === "number") return v; if (typeof v === "boolean") return E.VALUE;
        let s = String(v).trim(); if (/%$/.test(s)) { const x = Number(s.slice(0, -1).replace(/,/g, "")); return /^[+-]?(\d+\.?\d*|\.\d+)$/.test(s.slice(0, -1).replace(/,/g, "")) ? x / 100 : E.VALUE; }
        return toNumber(s);
      }
      case "TEXT": {
        const v = scalar(ev(a[0])); if (isErr(v)) return v; if (typeof v === "string") return v;
        const x = v === undefined || v === null ? 0 : typeof v === "boolean" ? (v ? "TRUE" : "FALSE") : v;
        return typeof x === "string" ? x : formatNumber(x, a[1].v);
      }
      case "CONCATENATE": {
        let out = "";
        for (const arg of a) { const v = ev(arg); if (isRange(v)) return E.VALUE; const t = toText(v); if (isErr(t)) return t; out += t; }
        return out;
      }
      case "LEFT": case "RIGHT": {
        const t = toText(scalar(ev(a[0]))); if (isErr(t)) return t;
        const k = a.length === 2 ? num(a[1]) : 1; if (isErr(k)) return k; if (k < 0) return E.VALUE;
        const c = Math.trunc(k); return n.name === "LEFT" ? t.slice(0, c) : c === 0 ? "" : t.slice(-c);
      }
      case "LEN": { const t = toText(scalar(ev(a[0]))); return isErr(t) ? t : [...t].length; }
      case "UPPER": { const t = toText(scalar(ev(a[0]))); return isErr(t) ? t : t.toUpperCase(); }
      case "TRIM": { const t = toText(scalar(ev(a[0]))); return isErr(t) ? t : t.replace(/ +/g, " ").trim(); }
      default: return E.NAME;
    }
  }
  const r = scalar(ev(tree));
  return r === undefined || r === null ? 0 : r; // a formula that ends on an empty cell shows 0
}

/** Result as text the way a cell shows it before number formats (errors as their code). */
export function show(v) { return v === undefined || v === null ? "" : isErr(v) ? v.code : typeof v === "boolean" ? (v ? "TRUE" : "FALSE") : typeof v === "number" ? numberText(v) : String(v); }
