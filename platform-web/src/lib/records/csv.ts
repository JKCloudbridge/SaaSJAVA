import type { FormField } from "./types";

/**
 * The little of CSV the browser needs for an import (Sprint 19): the header row, so a person can say which column fills
 * which field before anything is sent. The server reads the file for real (UTF-8, the chosen delimiter, quoted values,
 * ADR-0123); this only looks at the first line, and an unreadable header is a reason to ask the server, never to decide.
 */

/** The text of a file or a part of one: `Blob.text` where there is one, a reader where there is not. */
export function readText(blob: Blob): Promise<string> {
  if (typeof blob.text === "function") {
    return blob.text();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

export type Delimiter = "COMMA" | "SEMICOLON" | "TAB";

export const DELIMITER_CHARACTERS: Record<Delimiter, string> = { COMMA: ",", SEMICOLON: ";", TAB: "\t" };

/** The delimiter the first line seems to use: the one of the three that splits it into the most pieces. */
export function guessDelimiter(firstLine: string): Delimiter {
  let best: Delimiter = "COMMA";
  let most = 0;
  for (const [name, character] of Object.entries(DELIMITER_CHARACTERS) as [Delimiter, string][]) {
    const count = parseHeader(firstLine, character).length;
    if (count > most) {
      best = name;
      most = count;
    }
  }
  return best;
}

/** The first record of a CSV text, split at the delimiter, with quoted values (and doubled quotes) read as one. */
export function parseHeader(text: string, delimiter: string): string[] {
  const cells: string[] = [];
  let current = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text.charAt(index);
    if (quoted) {
      if (character === '"' && text.charAt(index + 1) === '"') {
        current += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        current += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === delimiter) {
      cells.push(current.trim());
      current = "";
    } else if (character === "\n" || character === "\r") {
      break;
    } else {
      current += character;
    }
  }
  cells.push(current.trim());
  return cells.filter((cell, position) => cell !== "" || position < cells.length - 1);
}

/** The fields an import can fill: what the member may type into and an external identifier (which is mapped to find). */
export function importableFields(fields: readonly FormField[]): FormField[] {
  return fields.filter((field) => !field.calculated && field.kind !== "SYSTEM" && (field.editable || field.externalId));
}

function normal(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, "");
}

/** A first guess of which field each column fills: the column is named like the field's API name or its label. */
export function suggestMapping(columns: readonly string[], fields: readonly FormField[]): Record<string, string> {
  const mapping: Record<string, string> = {};
  const taken = new Set<string>();
  for (const column of columns) {
    const wanted = normal(column);
    const field = fields.find(
      (candidate) =>
        !taken.has(candidate.apiName) && (normal(candidate.apiName) === wanted || normal(candidate.label) === wanted),
    );
    if (field) {
      mapping[column] = field.apiName;
      taken.add(field.apiName);
    }
  }
  return mapping;
}
