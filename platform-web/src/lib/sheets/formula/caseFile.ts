import { readFileSync } from "node:fs";
import path from "node:path";
import type { FormulaLimitsWire } from "../types";
import type { Layout } from "./translate";
import { DEFAULT_BOUNDS, E, XlError, type FormulaSpec, type Grid, type Value } from "./types";

/**
 * The one file of formula cases that the server's tests and the browser's tests both read (SH2, ADR-0252). This helper
 * is for tests only: nothing of the product imports it. The file holds no text of a real form; its sections are
 * described at its head.
 */
export const CASE_FILE = path.resolve(process.cwd(), "../platform-app/src/test/resources/sheets/formula-cases.txt");

export interface Line {
  section: string;
  number: number;
  fields: string[];
}

export function section(name: string): Line[] {
  const out: Line[] = [];
  let current = "eval";
  readFileSync(CASE_FILE, "utf8")
    .split(/\r?\n/)
    .forEach((line, index) => {
      if (line.trim() === "" || line.startsWith("#")) return;
      if (line.startsWith("[") && line.endsWith("]")) {
        current = line.slice(1, -1);
        return;
      }
      if (current === name) out.push({ section: current, number: index + 1, fields: line.split("|").map((f) => f.trim()) });
    });
  return out;
}

/** The functions and the formats of the case file, as the server's list of versions would tell them. */
export function wireFromCases(): FormulaLimitsWire {
  return {
    ...DEFAULT_BOUNDS,
    functions: section("functions").map((line) => ({
      name: line.fields[0] ?? "",
      least: Number(line.fields[1]),
      most: Number(line.fields[2]),
    })),
    textFormats: section("formats").map((line) => (line.fields[0] ?? "").slice(1, -1)),
  };
}

export function specFromCases(): FormulaSpec {
  const wire = wireFromCases();
  const functions: Record<string, readonly [number, number]> = {};
  for (const one of wire.functions) functions[one.name] = [one.least, one.most];
  return { ...DEFAULT_BOUNDS, functions, textFormats: wire.textFormats };
}

/** Thirty columns c1..c30 and sixty rows r1..r60, the formula at K1. */
export function flatLayout(): Layout {
  return {
    columnIds: Array.from({ length: 30 }, (_, i) => `c${i + 1}`),
    rowIds: Array.from({ length: 60 }, (_, i) => `r${i + 1}`),
    growing: false,
    bodyRows: 0,
  };
}

/** One header row h1, thirty columns, and a number of body rows the filler added. */
export function growingLayout(bodyRows: number): Layout {
  return { columnIds: Array.from({ length: 30 }, (_, i) => `c${i + 1}`), rowIds: ["h1"], growing: true, bodyRows };
}

function splitInputs(spec: string): string[] {
  const parts: string[] = [];
  let current = "";
  let quoted = false;
  for (const c of spec) {
    if (c === '"') quoted = !quoted;
    if (c === ";" && !quoted) {
      parts.push(current.trim());
      current = "";
    } else current += c;
  }
  if (current.trim() !== "") parts.push(current.trim());
  return parts;
}

/** A value written in the file: "text", TRUE, FALSE, #CODE or a number. */
export function valueOf(text: string): Value {
  if (text.startsWith('"')) return text.slice(1, -1);
  if (text === "TRUE") return true;
  if (text === "FALSE") return false;
  if (text.startsWith("#")) return new XlError(text);
  return Number(text);
}

/** The cells of a case by "c1|r1". */
export function inputs(spec: string): Map<string, Value> {
  const cells = new Map<string, Value>();
  for (const part of splitInputs(spec)) {
    const equals = part.indexOf("=");
    const ref = part.slice(0, equals).trim();
    const column = (ref.charCodeAt(0) ?? 65) - 64;
    cells.set(`c${column}|r${Number(ref.slice(1))}`, valueOf(part.slice(equals + 1).trim()));
  }
  return cells;
}

export function same(expected: string, got: Value): boolean {
  const want = valueOf(expected);
  if (typeof want === "number") return typeof got === "number" && Math.abs(got - want) <= 1e-9;
  if (want instanceof XlError) return got instanceof XlError && got.code === want.code;
  return want === got;
}

/** A grid over the cells of a case. */
export function gridOf(cells: Map<string, Value>, bodyRows: string[] = []): Grid {
  const index = (id: string): number => Number(id.slice(1));
  return {
    cell: (column, row) => cells.get(`${column}|${row}`) ?? null,
    range(fromColumn, fromRow, toColumn, toRow) {
      const c0 = Math.min(index(fromColumn), index(toColumn));
      const c1 = Math.max(index(fromColumn), index(toColumn));
      const r0 = Math.min(index(fromRow), index(toRow));
      const r1 = Math.max(index(fromRow), index(toRow));
      const out: Value[] = [];
      for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) out.push(cells.get(`c${c}|r${r}`) ?? null);
      return out;
    },
    bodyRows: () => bodyRows,
  };
}

export { E };
