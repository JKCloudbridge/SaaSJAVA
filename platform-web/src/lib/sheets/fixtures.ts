import type { GridDoc, SheetLimits } from "./types";

/** The caps of the product, as the API states them, for tests. */
export const LIMITS: SheetLimits = {
  maxBytes: 1_000_000,
  maxRows: 500,
  maxColumns: 100,
  maxHeaderRows: 10,
  maxCellChars: 1000,
  maxLabelChars: 200,
  maxPicklistValues: 100,
  maxMerges: 2000,
};

/**
 * A small sheet that names its rows: three columns (a text, a number, a list), two header rows, three named rows.
 * Row labels are fixed text in the first column; the rest are input cells. No merge.
 */
export function sampleDoc(): GridDoc {
  return {
    formatVersion: 1,
    dimension: 2,
    rowsMode: "FIXED",
    columns: [
      { id: "c1", label: "Item", type: "text", width: 160 },
      { id: "c2", label: "Amount", type: "number", required: true },
      { id: "c3", label: "Result", type: "picklist", picklist: { values: ["Pass", "Fail"] } },
    ],
    headerRows: [{ id: "h1" }, { id: "h2" }],
    rows: [
      { id: "r1", label: "First" },
      { id: "r2", label: "Second" },
      { id: "r3", label: "Third" },
    ],
    cells: {
      "h1:c1": { value: "Inspection", style: { bold: true, fontSize: "TITLE" } },
      "r1:c1": { value: "Fire exit" },
      "r2:c1": { value: "Alarm panel", style: { italic: true } },
    },
    merges: [],
  };
}

/** The same sheet when the filler adds the rows. */
export function oneDimension(): GridDoc {
  return {
    ...sampleDoc(),
    dimension: 1,
    rowsMode: "REPEATABLE",
    rows: [],
    cells: { "h1:c1": { value: "Findings", style: { bold: true } } },
  };
}
