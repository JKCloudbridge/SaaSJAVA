// POC 1, WP3/WP4 (browser side, throwaway): the evaluator for the whitelisted formulas, working on the ID-BASED stored form
// (the same form the Java translator writes: @{COL} same row, @{COL,ROW} another row, @{COL,ROW,cr} with absolute markers).
// Plus the two translators between Excel-style text and the stored form, so the formula bar can show and take Excel-style text.
// Whitelist: IF, MIN, MAX, SUM, ROUND, AND, OR, NOT, ISBLANK; arithmetic, comparison, percent, unary minus. Nothing is ever run as code:
// the text is parsed into a small tree and the tree is interpreted. Bounds as the expression engine: 4000 characters, 32 levels.

export const MAX_CHARS = 4000;
export const MAX_DEPTH = 32;
export const FUNCTIONS = new Set(["IF", "MIN", "MAX", "SUM", "ROUND", "AND", "OR", "NOT", "ISBLANK"]);

export class XlError { constructor(code) { this.code = code; } toString() { return this.code; } }
export const E = { VALUE: new XlError("#VALUE!"), DIV0: new XlError("#DIV/0!"), NUM: new XlError("#NUM!"), NAME: new XlError("#NAME?"), NA: new XlError("#N/A") };
export class FormulaRefused extends Error { constructor(key) { super(key); this.key = key; } }

// ---------------------------------------------------------------------------------------------------------------- translators

const A1 = /^(\$?)([A-Za-z]{1,3})(\$?)([0-9]{1,7})/;
const letters = (i) => { let s = ""; for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s; return s; };
const colIndex = (L) => [...L.toUpperCase()].reduce((v, ch) => v * 26 + ch.charCodeAt(0) - 64, 0) - 1;
const wordStart = (s, i) => /[A-Za-z$_]/.test(s[i]) && (i === 0 || !/[A-Za-z0-9_.$]/.test(s[i - 1]));
function endOfText(s, i) {
  for (let k = i + 1; k < s.length; k++) if (s[k] === '"') { if (s[k + 1] === '"') { k++; continue; } return k + 1; }
  throw new FormulaRefused("unclosed_text");
}

/** Excel-style -> stored form. layout = { colIds: [...], rowIds: [...] } (position 0 = column A / row 1); row = 1-based row of the cell. */
export function toIds(excel, row, layout) {
  if (excel.length > MAX_CHARS) throw new FormulaRefused("too_long");
  let out = ""; let i = 0;
  while (i < excel.length) {
    const ch = excel[i];
    if (ch === '"') { const e = endOfText(excel, i); out += excel.slice(i, e); i = e; continue; }
    if ("@{[!'".includes(ch)) throw new FormulaRefused("character_not_allowed");
    if (wordStart(excel, i)) {
      const m = A1.exec(excel.slice(i));
      if (m) {
        const end = i + m[0].length; const nx = excel[end];
        if (!(nx && /[A-Za-z0-9_(.]/.test(nx))) {
          const col = colIndex(m[2]); const rowNo = Number(m[4]);
          if (col >= layout.colIds.length) throw new FormulaRefused("column_outside_sheet");
          if (rowNo < 1 || rowNo > layout.rowIds.length) throw new FormulaRefused("row_outside_sheet");
          const absC = m[1] !== ""; const absR = m[3] !== ""; const same = rowNo === row && !absR;
          out += `@{${layout.colIds[col]}${same ? "" : "," + layout.rowIds[rowNo - 1]}${absC || absR ? (same ? ",," : ",") + (absC ? "c" : "") + (absR ? "r" : "") : ""}}`;
          i = end; continue;
        }
      }
      let e = i; while (e < excel.length && /[A-Za-z0-9_.]/.test(excel[e])) e++;
      out += excel.slice(i, e); i = Math.max(e, i + 1); continue;
    }
    out += ch; i++;
  }
  return out;
}

/** Stored form -> Excel-style text drawn at the given 1-based row. */
export function toExcel(ids, row, layout) {
  const cols = new Map(layout.colIds.map((id, i) => [id, i])); const rows = new Map(layout.rowIds.map((id, i) => [id, i]));
  let out = ""; let i = 0;
  while (i < ids.length) {
    if (ids[i] === '"') { const e = endOfText(ids, i); out += ids.slice(i, e); i = e; continue; }
    if (ids[i] === "@") {
      const m = /^@\{([A-Za-z0-9_-]{1,40})(?:,([A-Za-z0-9_-]{0,40}))?(?:,([cr]{0,2}))?}/.exec(ids.slice(i));
      if (!m) throw new FormulaRefused("bad_reference_token");
      if (!cols.has(m[1])) throw new FormulaRefused("unknown_column");
      let rowNo = row;
      if (m[2]) { if (!rows.has(m[2])) throw new FormulaRefused("unknown_row"); rowNo = rows.get(m[2]) + 1; }
      const f = m[3] ?? "";
      out += `${f.includes("c") ? "$" : ""}${letters(cols.get(m[1]))}${f.includes("r") ? "$" : ""}${rowNo}`;
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
      const m = /^@\{([A-Za-z0-9_-]{1,40})(?:,([A-Za-z0-9_-]{0,40}))?(?:,([cr]{0,2}))?}/.exec(src.slice(i));
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
    if (t.k === "ref") { if (isOp(":")) { p++; const t2 = toks[p++]; if (t2?.k !== "ref") throw new FormulaRefused("bad_range"); return { t: "range", a: t, b: t2 }; } return { t: "ref", col: t.col, row: t.row }; }
    if (t.k === "op" && t.v === "(") { enter(); const x = cmp(); depth--; if (!isOp(")")) throw new FormulaRefused("missing_bracket"); p++; return x; }
    if (t.k === "id") {
      if (t.v === "TRUE" || t.v === "FALSE") return { t: "bool", v: t.v === "TRUE" };
      if (!isOp("(")) throw new FormulaRefused("name_not_allowed");
      if (!FUNCTIONS.has(t.v)) throw new FormulaRefused("function_not_allowed");
      p++; enter(); const args = [];
      if (!isOp(")")) { for (;;) { args.push(isOp(",") || isOp(")") ? { t: "missing" } : cmp()); if (isOp(",")) { p++; continue; } break; } }
      depth--; if (!isOp(")")) throw new FormulaRefused("missing_bracket"); p++;
      return { t: "call", name: t.v, args };
    }
    throw new FormulaRefused("unexpected_token");
  }
  const tree = cmp();
  if (p < toks.length) throw new FormulaRefused("unexpected_token");
  return tree;
}

// ---------------------------------------------------------------------------------------------------------------- evaluation

const isErr = (v) => v instanceof XlError;
function toNumber(v) {
  if (isErr(v)) return v; if (v === undefined || v === null) return 0; if (typeof v === "boolean") return v ? 1 : 0;
  if (typeof v === "number") return v;
  const s = String(v).trim(); if (s === "") return E.VALUE;
  const n = Number(s.replace(/,/g, "")); return Number.isNaN(n) ? E.VALUE : n;
}
function toBool(v) {
  if (isErr(v)) return v; if (v === undefined || v === null) return false; if (typeof v === "boolean") return v; if (typeof v === "number") return v !== 0;
  const s = String(v).toUpperCase(); if (s === "TRUE") return true; if (s === "FALSE") return false; return E.VALUE;
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
function round(x, n) { // decimal view, half away from zero, as the spreadsheet does (2.675 -> 2.68, 1.005 -> 1.01)
  const sign = x < 0 ? -1 : 1; const s = `${Math.abs(x)}e${n}`; const r = Math.round(Number(s));
  return sign * Number(`${r}e${-n}`);
}

/** ctx: { row: rowId of the cell being calculated, cell(colId,rowId): raw value or undefined, range(a,b): array of raw values } */
export function evaluate(tree, ctx) {
  const ev = (n) => {
    switch (n.t) {
      case "num": case "str": case "bool": return n.v;
      case "missing": return undefined;
      case "ref": return ctx.cell(n.col, n.row ?? ctx.row);
      case "range": return { range: ctx.range({ col: n.a.col, row: n.a.row ?? ctx.row }, { col: n.b.col, row: n.b.row ?? ctx.row }) };
      case "neg": { const x = scalar(ev(n.x)); const v = toNumber(x); return isErr(v) ? v : -v; }
      case "pct": { const v = toNumber(scalar(ev(n.x))); return isErr(v) ? v : v / 100; }
      case "bin": return binary(n);
      case "call": return call(n);
      default: throw new FormulaRefused("bad_tree");
    }
  };
  const scalar = (v) => (v && typeof v === "object" && "range" in v ? (v.range.length === 1 ? v.range[0] : E.VALUE) : v);
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
      case "^": { const r = x ** y; return Number.isFinite(r) ? r : E.NUM; }
      default: throw new FormulaRefused("bad_operator");
    }
  }
  function numbersOf(args, { skipTextInRanges = true } = {}) {
    const out = [];
    for (const a of args) {
      const v = ev(a);
      if (v && typeof v === "object" && "range" in v) { for (const c of v.range) { if (isErr(c)) return c; if (typeof c === "number") out.push(c); } }
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
      const items = v && typeof v === "object" && "range" in v ? v.range : [v]; const isRef = a.t === "ref" || (v && typeof v === "object" && "range" in v);
      for (const c of items) {
        if (isErr(c)) return c;
        if (isRef && (typeof c === "string" || c === undefined || c === null)) continue;
        const b = toBool(c); if (isErr(b)) return b; out.push(b);
      }
    }
    return out.length ? out : E.VALUE;
  }
  function call(n) {
    const a = n.args;
    switch (n.name) {
      case "IF": {
        if (a.length < 2 || a.length > 3) return E.NA;
        const c = toBool(scalar(ev(a[0]))); if (isErr(c)) return c;
        if (c) return scalar(ev(a[1]));
        return a.length === 3 ? scalar(ev(a[2])) : false;
      }
      case "SUM": { const x = numbersOf(a); return isErr(x) ? x : x.reduce((s, v) => s + v, 0); }
      case "MIN": { const x = numbersOf(a); return isErr(x) ? x : x.length ? Math.min(...x) : 0; }
      case "MAX": { const x = numbersOf(a); return isErr(x) ? x : x.length ? Math.max(...x) : 0; }
      case "ROUND": { if (a.length !== 2) return E.NA; const x = toNumber(scalar(ev(a[0]))); if (isErr(x)) return x; const d = toNumber(scalar(ev(a[1]))); if (isErr(d)) return d; return round(x, Math.trunc(d)); }
      case "AND": { const l = logicalsOf(a); return isErr(l) ? l : l.every(Boolean); }
      case "OR": { const l = logicalsOf(a); return isErr(l) ? l : l.some(Boolean); }
      case "NOT": { if (a.length !== 1) return E.NA; const b = toBool(scalar(ev(a[0]))); return isErr(b) ? b : !b; }
      case "ISBLANK": { if (a.length !== 1) return E.NA; if (a[0].t !== "ref") return false; const v = ev(a[0]); return v === undefined || v === null; }
      default: return E.NAME;
    }
  }
  const r = ev(tree);
  return scalar(r);
}

/** Result as text the way a cell shows it before number formats (errors as their code). */
export function show(v) { return v === undefined || v === null ? "" : isErr(v) ? v.code : typeof v === "boolean" ? (v ? "TRUE" : "FALSE") : String(v); }
