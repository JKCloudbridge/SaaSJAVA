import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * The one file of look cases that the server's tests and the browser's tests both read (SH4, ADR-0251). For tests only:
 * nothing of the product imports it. The file holds no text of a real form; its sections are described at its head.
 */
export const LOOK_CASES = path.resolve(process.cwd(), "../platform-app/src/test/resources/sheets/look-cases.txt");

export interface CaseLine {
  number: number;
  fields: string[];
}

/** The lines of one section, split on the bars (an empty last field is kept). */
export function lookSection(name: string): CaseLine[] {
  const out: CaseLine[] = [];
  let current = "";
  readFileSync(LOOK_CASES, "utf8")
    .split(/\r?\n/)
    .forEach((line, index) => {
      if (line.trim() === "" || line.startsWith("#")) return;
      if (line.startsWith("[") && line.endsWith("]")) {
        current = line.slice(1, -1);
        return;
      }
      if (current === name) out.push({ number: index + 1, fields: line.split("|").map((field) => field.trim()) });
    });
  return out;
}
