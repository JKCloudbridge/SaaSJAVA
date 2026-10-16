import type { components } from "@/lib/api/generated/schema";

type QueryFilterNode = components["schemas"]["QueryFilterNode"];

/** The comparisons a sharing rule offers: those that an indexed field answers without reading every record. */
export const CRITERIA_OPERATORS: { value: string; text: string; takesValue: boolean }[] = [
  { value: "EQ", text: "is", takesValue: true },
  { value: "NE", text: "is not", takesValue: true },
  { value: "GT", text: "is greater than", takesValue: true },
  { value: "GTE", text: "is at least", takesValue: true },
  { value: "LT", text: "is less than", takesValue: true },
  { value: "LTE", text: "is at most", takesValue: true },
  { value: "IS_EMPTY", text: "is empty", takesValue: false },
  { value: "IS_NOT_EMPTY", text: "is not empty", takesValue: false },
];

export interface CriterionRow {
  field: string;
  op: string;
  value: string;
}

/** The condition of a rule as a form edits it: conditions on fields, all or any of them; or a shape the form keeps whole. */
export type CriteriaDraft =
  | { kind: "rows"; match: "AND" | "OR"; rows: CriterionRow[] }
  | { kind: "kept"; node: QueryFilterNode };

export function emptyCriteria(): CriteriaDraft {
  return { kind: "rows", match: "AND", rows: [{ field: "", op: "EQ", value: "" }] };
}

const SIMPLE = new Set(CRITERIA_OPERATORS.map((one) => one.value));

/**
 * Reads a saved condition. A single condition, or one level of conditions joined by AND or OR, can be edited; anything
 * else (nested groups, other comparisons) is kept as it is and shown in words, never rewritten by the form.
 */
export function criteriaFrom(node: QueryFilterNode | undefined): CriteriaDraft {
  if (!node) {
    return emptyCriteria();
  }
  const single = (one: QueryFilterNode): CriterionRow | undefined =>
    one.field && SIMPLE.has(one.op) && (one.values ?? []).length <= 1 && (one.items ?? []).length === 0
      ? { field: one.field, op: one.op, value: one.values && one.values.length > 0 ? String(one.values[0]) : "" }
      : undefined;
  if (node.op === "AND" || node.op === "OR") {
    const rows = (node.items ?? []).map(single);
    if (rows.length > 0 && rows.every((row): row is CriterionRow => row !== undefined)) {
      return { kind: "rows", match: node.op, rows };
    }
    return { kind: "kept", node };
  }
  const row = single(node);
  return row ? { kind: "rows", match: "AND", rows: [row] } : { kind: "kept", node };
}

/** The value as the engine holds it: a checkbox is true or false, any other value travels as the text typed. */
function valueOf(row: CriterionRow, typeOf: (field: string) => string | undefined): unknown {
  return typeOf(row.field) === "BOOLEAN" ? row.value === "true" : row.value;
}

export function criteriaNode(
  draft: CriteriaDraft,
  typeOf: (field: string) => string | undefined,
): QueryFilterNode | undefined {
  if (draft.kind === "kept") {
    return draft.node;
  }
  const conditions: QueryFilterNode[] = draft.rows.map((row) => {
    const takesValue = CRITERIA_OPERATORS.find((one) => one.value === row.op)?.takesValue ?? true;
    return takesValue
      ? { field: row.field, op: row.op, values: [valueOf(row, typeOf)] }
      : { field: row.field, op: row.op };
  });
  if (conditions.length === 0) {
    return undefined;
  }
  return conditions.length === 1 ? conditions[0] : { op: draft.match, items: conditions };
}

/** A condition in words, for the list of rules. */
export function describeCriteria(node: QueryFilterNode | undefined, labelOf: (field: string) => string): string {
  if (!node) {
    return "";
  }
  if (node.op === "AND" || node.op === "OR") {
    const joiner = node.op === "AND" ? " and " : " or ";
    return (node.items ?? []).map((one) => describeCriteria(one, labelOf)).join(joiner);
  }
  const text = CRITERIA_OPERATORS.find((one) => one.value === node.op)?.text ?? node.op.toLowerCase();
  const value = node.values && node.values.length > 0 ? ` ${String(node.values[0])}` : "";
  return `${labelOf(node.field ?? "")} ${text}${value}`;
}
