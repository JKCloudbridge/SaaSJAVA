import { parse } from "./parse";
import type { FormulaSpec, Node, Ref } from "./types";

/**
 * Prints a tree as the one canonical stored text of a formula (SH2, ADR-0252): function names in capitals, no blank
 * outside a text constant, one spelling of each number, TRUE and FALSE in capitals, the parts of a reference token in a
 * fixed order, and the brackets the author wrote. The server prints the same text; the hash of a sheet depends on it.
 */
export function print(node: Node): string {
  switch (node.t) {
    case "num":
      return node.text;
    case "text":
      return `"${node.value.replaceAll('"', '""')}"`;
    case "bool":
      return node.value ? "TRUE" : "FALSE";
    case "ref":
      return token(node);
    case "range":
      return `${token(node.from)}:${token(node.to)}`;
    case "neg":
      return `-${print(node.operand)}`;
    case "pct":
      return `${print(node.operand)}%`;
    case "bin":
      return `${print(node.left)}${node.op}${print(node.right)}`;
    case "group":
      return `(${print(node.inner)})`;
    case "call":
      return `${node.name}(${node.args.map(print).join(",")})`;
  }
}

/** One reference token in its one form. */
export function token(ref: Ref): string {
  let out = `@{${ref.column}`;
  if (ref.growing) {
    out += ",*";
  } else if (ref.row !== null) {
    out += `,${ref.row}`;
    if (ref.absoluteColumn || ref.absoluteRow) out += `,${ref.absoluteColumn ? "c" : ""}${ref.absoluteRow ? "r" : ""}`;
  } else if (ref.absoluteColumn) {
    out += ",,c";
  }
  return `${out}}`;
}

/** The canonical text of a formula in its stored form, or the refusal. */
export function canonical(stored: string, spec: FormulaSpec): string {
  return print(parse(stored, spec));
}
