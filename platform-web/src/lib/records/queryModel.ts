import { displayType, instantOfLocalInput, localInputOfInstant } from "./fieldValue";
import type { FormField, ListViewDefinition, ObjectForm, QueryFilterNode, QueryRequest } from "./types";

/**
 * The list screen's model of a question put to the records (Sprint 19, ADR-0129, ADR-0130): filter rows a person edits,
 * the order, the columns. It turns into the closed query model of the API (`QueryRequest`, `ListViewDefinition`) and
 * back; it holds no rule about what a field accepts, because the server says which comparisons each field takes
 * (`FormField.filterOperators`) and judges the values when the query runs.
 */

/** The words for the comparisons of the API. */
export const OPERATOR_LABELS: Record<string, string> = {
  EQ: "is",
  NE: "is not",
  GT: "is greater than",
  GTE: "is at least",
  LT: "is less than",
  LTE: "is at most",
  BETWEEN: "is between",
  IN: "is one of",
  IS_EMPTY: "is empty",
  IS_NOT_EMPTY: "has a value",
  STARTS_WITH: "starts with",
  CONTAINS: "contains",
};

/** One condition a person is building: the field, the comparison and the text of its value(s). */
export interface FilterRow {
  key: string;
  field: string;
  op: string;
  /** The first value (or the list of an "is one of", one per line or separated by commas). */
  text: string;
  /** The second value of "is between". */
  text2: string;
}

export interface SortKey {
  field: string;
  descending: boolean;
}

/** What a list shows and how it asks. `raw` holds a filter this screen cannot edit (an OR, a group inside a group). */
export interface ListQuery {
  filters: FilterRow[];
  sort: SortKey[];
  columns: string[];
  raw?: QueryFilterNode;
}

let counter = 0;

/** A row key that is unique for the life of the page (it is a React key, never sent anywhere). */
export function newRowKey(): string {
  counter += 1;
  return `row-${counter}`;
}

/** A row for a field, with the first comparison the server accepts for it. */
export function newFilterRow(field: FormField): FilterRow {
  return { key: newRowKey(), field: field.apiName, op: field.filterOperators[0] ?? "EQ", text: "", text2: "" };
}

/** The fields a filter can be built on: the ones the server says take at least one comparison. */
export function filterableFields(form: ObjectForm): FormField[] {
  return form.fields.filter((field) => field.filterOperators.length > 0);
}

/** The fields a list can be ordered by. */
export function sortableFields(form: ObjectForm): FormField[] {
  return form.fields.filter((field) => field.sortable);
}

const ENVELOPE = new Set(["id", "ownerId", "createdAt", "updatedAt", "createdById", "updatedById"]);

/** The fields of a record's own values a query should bring: the ones that sit beside the values come anyway. */
export function projectionOf(columns: readonly string[]): string[] {
  return columns.filter((name) => !ENVELOPE.has(name));
}

/**
 * The columns a list starts with: what names a record, then the fields of the layout in the order an administrator
 * placed them, a handful in all. A calculated or system field is only added when there is nothing else, so a new object
 * still has a readable list.
 */
export function defaultColumns(form: ObjectForm): string[] {
  const known = new Map(form.fields.map((field) => [field.apiName, field]));
  const wanted: string[] = [];
  const add = (name: string | null | undefined) => {
    if (name && known.has(name) && !wanted.includes(name)) {
      wanted.push(name);
    }
  };
  add(form.titleField);
  for (const section of form.layout.sections) {
    for (const name of section.fields) {
      if (known.get(name)?.kind !== "SYSTEM") {
        add(name);
      }
    }
  }
  const columns = wanted.slice(0, 5);
  if (columns.length === 0) {
    add("sequence");
    add("ownerId");
    return wanted.slice(0, 3);
  }
  return columns;
}

// ---- values <-> text ----

function splitList(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((part) => part.trim())
    .filter((part) => part !== "");
}

/** The API value for the text of a filter, by what the field is. */
function coerce(field: FormField, text: string): unknown {
  const trimmed = text.trim();
  switch (displayType(field)) {
    case "BOOLEAN":
      return trimmed === "true";
    case "DATETIME":
      return instantOfLocalInput(trimmed) ?? trimmed;
    default:
      return trimmed;
  }
}

/** The values of a row for the API; null while the row is not complete enough to ask. */
export function valuesOf(row: FilterRow, field: FormField): unknown[] | null {
  switch (row.op) {
    case "IS_EMPTY":
    case "IS_NOT_EMPTY":
      return [];
    case "BETWEEN":
      return row.text.trim() === "" || row.text2.trim() === "" ? null : [coerce(field, row.text), coerce(field, row.text2)];
    case "IN": {
      const parts = splitList(row.text);
      return parts.length === 0 ? null : parts.map((part) => coerce(field, part));
    }
    default:
      return row.text.trim() === "" && displayType(field) !== "BOOLEAN" ? null : [coerce(field, row.text)];
  }
}

/** The filter of the API for the rows that are complete; undefined when there is none. Incomplete rows are left out. */
export function filterNodeOf(rows: readonly FilterRow[], fields: ReadonlyMap<string, FormField>, raw?: QueryFilterNode): QueryFilterNode | undefined {
  if (raw) {
    return raw;
  }
  const items: QueryFilterNode[] = [];
  for (const row of rows) {
    const field = fields.get(row.field);
    const values = field ? valuesOf(row, field) : null;
    if (field && values) {
      items.push({ op: row.op, field: row.field, values: values as QueryFilterNode["values"] });
    }
  }
  if (items.length === 0) {
    return undefined;
  }
  return items.length === 1 ? items[0] : { op: "AND", items };
}

/** The request of the API for the list as the person set it up. */
export function queryRequestOf(query: ListQuery, form: ObjectForm, limit: number, cursor?: string): QueryRequest {
  const fields = new Map(form.fields.map((field) => [field.apiName, field]));
  const sortable = new Set(sortableFields(form).map((field) => field.apiName));
  const filter = filterNodeOf(query.filters, fields, query.raw);
  return {
    ...(filter ? { filter } : {}),
    sort: query.sort.filter((key) => sortable.has(key.field)).slice(0, 2).map((key) => ({ field: key.field, descending: key.descending })),
    fields: projectionOf(query.columns.filter((name) => fields.has(name))),
    limit,
    ...(cursor ? { cursor } : {}),
  };
}

// ---- a saved view <-> the list ----

/** The definition to save for the list as it is: only what a view can hold. */
export function definitionOf(query: ListQuery, form: ObjectForm): ListViewDefinition {
  const fields = new Map(form.fields.map((field) => [field.apiName, field]));
  const filter = filterNodeOf(query.filters, fields, query.raw);
  return {
    ...(filter ? { filter } : {}),
    sort: query.sort.slice(0, 2),
    columns: query.columns.filter((name) => fields.has(name)),
  };
}

function textOfValue(field: FormField, value: unknown): string {
  if (value === undefined || value === null) {
    return "";
  }
  if (displayType(field) === "DATETIME") {
    return localInputOfInstant(String(value));
  }
  return String(value);
}

function rowOf(node: QueryFilterNode, fields: ReadonlyMap<string, FormField>): FilterRow | null {
  if (!node.field || node.items?.length) {
    return null;
  }
  const field = fields.get(node.field);
  if (!field) {
    return null;
  }
  const values = node.values ?? [];
  const text = node.op === "IN" ? values.map((value) => textOfValue(field, value)).join(", ") : textOfValue(field, values[0]);
  return { key: newRowKey(), field: node.field, op: node.op, text, text2: node.op === "BETWEEN" ? textOfValue(field, values[1]) : "" };
}

/**
 * The list a saved view stands for. A filter that is one condition, or an AND of conditions, becomes rows a person can
 * edit; anything else (an OR, a group within a group, a field this member cannot see) is kept as it is in `raw`, still
 * run, and cleared as a whole. Columns the member cannot see are dropped: a screen shows what the form description lists.
 */
export function queryOfView(definition: ListViewDefinition, form: ObjectForm): ListQuery {
  const fields = new Map(form.fields.map((field) => [field.apiName, field]));
  const sortable = new Set(sortableFields(form).map((field) => field.apiName));
  const columns = (definition.columns ?? []).filter((name) => fields.has(name));
  const base: ListQuery = {
    filters: [],
    sort: (definition.sort ?? []).filter((key) => sortable.has(key.field)).map((key) => ({ field: key.field, descending: key.descending === true })),
    columns: columns.length > 0 ? columns : defaultColumns(form),
  };
  const filter = definition.filter;
  if (!filter) {
    return base;
  }
  const nodes = filter.op === "AND" && filter.items ? filter.items : [filter];
  const rows = nodes.map((node) => rowOf(node, fields));
  if (rows.every((row): row is FilterRow => row !== null)) {
    return { ...base, filters: rows };
  }
  return { ...base, raw: filter };
}

/** A sentence for what a filter row asks, for a chip: "Status is Open". */
export function describeRow(row: FilterRow, field: FormField, label?: string): string {
  const op = OPERATOR_LABELS[row.op] ?? row.op;
  if (row.op === "IS_EMPTY" || row.op === "IS_NOT_EMPTY") {
    return `${field.label} ${op}`;
  }
  if (row.op === "BETWEEN") {
    return `${field.label} ${op} ${row.text} and ${row.text2}`;
  }
  return `${field.label} ${op} ${label ?? row.text}`;
}
