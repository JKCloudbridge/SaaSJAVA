import type { ReportRequest } from "./fillIns";

/**
 * The model of the report page (Functional track FF2, ADR-0193): which comparisons a column offers and how what a person
 * typed becomes the question the API is asked. Pure, so it is tested without a browser. It is a convenience only: the
 * server checks the column, the comparison and every value again, and says what is wrong in its own words.
 */

export type ColumnType = "text" | "number" | "date" | "picklist" | "boolean";
export type Operator = "EQUALS" | "ONE_OF" | "BETWEEN";

export const OPERATOR_WORDS: Record<Operator, string> = {
  EQUALS: "is",
  ONE_OF: "is one of",
  BETWEEN: "is between",
};

/** The comparisons a column of this type offers. */
export function operatorsFor(type: ColumnType): Operator[] {
  switch (type) {
    case "number":
    case "date":
      return ["EQUALS", "ONE_OF", "BETWEEN"];
    case "boolean":
      return ["EQUALS"];
    default:
      return ["EQUALS", "ONE_OF"];
  }
}

/** What the person has chosen and typed on the report page. */
export interface Question {
  templateId: string;
  versionNo?: number;
  columnId: string;
  type: ColumnType;
  operator: Operator;
  /** The value of "is". */
  value: string;
  /** The values of "is one of", one on each line. */
  many: string;
  from: string;
  to: string;
  status: string;
}

/** The lines of a box, trimmed, without the empty ones and without repeats. */
export function splitMany(text: string): string[] {
  const seen = new Set<string>();
  for (const line of text.split(/\r?\n/)) {
    const value = line.trim();
    if (value !== "") {
      seen.add(value);
    }
  }
  return [...seen];
}

/** The question for the API, or the words that say what is still missing. */
export function toRequest(question: Question): { ok: true; request: ReportRequest } | { ok: false; error: string } {
  if (question.templateId === "" || question.columnId === "") {
    return { ok: false, error: "Choose a template and a column." };
  }
  const base = {
    templateId: question.templateId,
    versionNo: question.versionNo,
    columnId: question.columnId,
    operator: question.operator,
    status: question.status === "" ? undefined : question.status,
  };
  switch (question.operator) {
    case "EQUALS":
      return question.value.trim() === ""
        ? { ok: false, error: "Give the value to look for." }
        : { ok: true, request: { ...base, values: [question.value.trim()] } };
    case "ONE_OF": {
      const values = splitMany(question.many);
      return values.length === 0
        ? { ok: false, error: "Give at least one value, one on each line." }
        : { ok: true, request: { ...base, values } };
    }
    default: {
      const from = question.from.trim();
      const to = question.to.trim();
      return from === "" && to === ""
        ? { ok: false, error: "Give a lower bound, an upper bound or both." }
        : { ok: true, request: { ...base, from: from === "" ? undefined : from, to: to === "" ? undefined : to } };
    }
  }
}
