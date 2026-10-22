import { splitKey, zoneOfRow } from "./geometry";
import type { GridDoc, Problem, SheetLimits } from "./types";
import { TEXT_UNSAFE, unsafeText, valueProblem } from "./values";

/**
 * Hints for the form view (ADR-0186): what the server would refuse, said before the request. They use the places and
 * the words of the server's answers (`grid.columns[1].label`), so a hint and a refusal from the API are shown by the
 * same code. A hint decides nothing: the server runs every check again on every save.
 */

const MAX_HINTS = 50;

export function hintsFor(doc: GridDoc, limits: SheetLimits): Problem[] {
  const out: Problem[] = [];
  const add = (place: string, message: string) => {
    if (out.length < MAX_HINTS) {
      out.push({ place, message });
    }
  };
  if (doc.columns.length > limits.maxColumns) {
    add("grid.columns", `There are more columns than the allowed ${limits.maxColumns}.`);
  }
  if (doc.rows.length > limits.maxRows) {
    add("grid.rows", `There are more rows than the allowed ${limits.maxRows}.`);
  }
  if (doc.headerRows.length > limits.maxHeaderRows) {
    add("grid.headerRows", `There are more header rows than the allowed ${limits.maxHeaderRows}.`);
  }
  doc.columns.forEach((column, i) => {
    const place = `grid.columns[${i}]`;
    if (column.label.length > limits.maxLabelChars) {
      add(`${place}.label`, `A label is longer than the allowed ${limits.maxLabelChars} characters.`);
    }
    if (unsafeText(column.label)) {
      add(`${place}.label`, TEXT_UNSAFE);
    }
    if (column.type === "picklist") {
      const values = column.picklist?.values ?? [];
      if (values.length === 0) {
        add(`${place}.picklist`, "A list column needs its values.");
      }
      if (values.length > limits.maxPicklistValues) {
        add(`${place}.picklist`, `A list has more values than the allowed ${limits.maxPicklistValues}.`);
      }
      if (new Set(values).size !== values.length) {
        add(`${place}.picklist`, "A list has the same value twice.");
      }
      values.forEach((value, j) => {
        if (value.trim() === "") {
          add(`${place}.picklist.values[${j}]`, "A list value cannot be empty.");
        } else if (unsafeText(value)) {
          add(`${place}.picklist.values[${j}]`, TEXT_UNSAFE);
        }
      });
    }
    if (column.default !== undefined && column.default !== null) {
      const problem = valueProblem(column, column.default);
      if (problem) {
        add(`${place}.default`, problem);
      }
    }
  });
  const duplicates = new Set<string>();
  const seen = new Set<string>();
  for (const id of [...doc.columns, ...doc.headerRows, ...doc.rows].map((item) => item.id)) {
    if (seen.has(id)) {
      duplicates.add(id);
    }
    seen.add(id);
  }
  if (duplicates.size > 0) {
    add("grid", "An identifier is used twice.");
  }
  doc.rows.forEach((row, i) => {
    if (row.label !== undefined && row.label.length > limits.maxLabelChars) {
      add(`grid.rows[${i}].label`, `A label is longer than the allowed ${limits.maxLabelChars} characters.`);
    }
  });
  for (const [key, cell] of Object.entries(doc.cells)) {
    const [rowId, columnId] = splitKey(key);
    if (zoneOfRow(doc, rowId) === undefined || !doc.columns.some((column) => column.id === columnId)) {
      add(`grid.cells.${key}`, "A cell points at a row or column that does not exist.");
    } else if (typeof cell.value === "string" && cell.value.length > limits.maxCellChars) {
      add(`grid.cells.${key}`, `A text is longer than the allowed ${limits.maxCellChars} characters.`);
    }
  }
  return out;
}

/** The hints or refusals that belong to a place or to something inside it (`grid.columns[1]` includes `.label`). */
export function problemsAt(problems: readonly Problem[], place: string): string[] {
  return problems
    .filter((problem) => problem.place === place || problem.place.startsWith(`${place}.`) || problem.place.startsWith(`${place}[`))
    .map((problem) => problem.message);
}

/** The problems of an API answer (a map from place to words) as a list. */
export function problemsFromFields(fields: Record<string, string[]> | undefined): Problem[] {
  return Object.entries(fields ?? {}).flatMap(([place, messages]) => messages.map((message) => ({ place, message })));
}

/**
 * Says where a place is, for a person: `grid.columns[2].label` is "Column 3, label"; a cell is named by the position of its
 * row and column in this document, never by an identifier. A place the document does not explain is shown as it is.
 */
export function explainPlace(doc: GridDoc, place: string): string {
  const indexed = /^grid\.(columns|rows|headerRows|merges)\[(\d+)\](?:\.(.+))?$/.exec(place);
  if (indexed) {
    const names: Record<string, string> = { columns: "Column", rows: "Row", headerRows: "Header row", merges: "Merged area" };
    const what = `${names[indexed[1] ?? ""]} ${Number(indexed[2]) + 1}`;
    const rest = indexed[3];
    return rest ? `${what}, ${rest.replace(/\[\d+\]/g, "").split(".").join(", ")}` : what;
  }
  const cell = /^grid\.cells\.([^.]+)(?:\..+)?$/.exec(place);
  const reference = cell?.[1];
  if (reference?.includes(":")) {
    const [rowId, columnId] = splitKey(reference);
    const rowAt = [...doc.headerRows, ...doc.rows].findIndex((row) => row.id === rowId);
    const columnAt = doc.columns.findIndex((column) => column.id === columnId);
    if (rowAt >= 0 && columnAt >= 0) {
      return `${rowAt < doc.headerRows.length ? "Header row" : "Row"} ${
        rowAt < doc.headerRows.length ? rowAt + 1 : rowAt - doc.headerRows.length + 1
      }, column ${columnAt + 1}`;
    }
    return "A cell";
  }
  return place === "grid" ? "The grid" : place.replace(/^grid\./, "");
}
