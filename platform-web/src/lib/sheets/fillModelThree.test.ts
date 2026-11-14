import { describe, expect, it } from "vitest";
import {
  answersByCell,
  canEditAs,
  cellSpec,
  fromApiFill,
  hasFormulas,
  isInputCell,
  missingRequired,
  reapplyFill,
  setFillFiles,
  setFillValue,
  toApiFill,
  toWire,
} from "./fillModel";
import type { FillDoc, FillLimits } from "./fillTypes";
import type { GridDoc } from "./types";

/**
 * Fill format 3 on the browser's side (SH5): the document goes to the server nested in its sheet, a cell states its own
 * type, the two classes of cells are typed by different people, files are named by identifier, and the server's answers of
 * the formulas land on their own rows. The rules are the server's (it decides again on every save).
 */

const LIMITS: FillLimits = { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000 };
const FILE_A = "11111111-1111-4111-8111-111111111111";
const FILE_B = "22222222-2222-4222-8222-222222222222";

function sheet(): GridDoc {
  return {
    formatVersion: 2,
    dimension: 2,
    rowsMode: "FIXED",
    columns: [
      { id: "c1", label: "Quantity", type: "number" },
      { id: "c2", label: "Notes", type: "text" },
    ],
    headerRows: [{ id: "h1" }],
    rows: [{ id: "r1" }, { id: "r2" }, { id: "r3" }],
    cells: {},
    merges: [],
    sheet: {
      key: "main",
      cells: {},
      inputs: {
        "r1:c1": { type: "attachment", required: true, maxFiles: 2 },
        "r2:c1": { class: "reviewer", required: true },
        "r3:c2": { type: "picklist", picklist: { values: ["Good", "Poor"] } },
      },
    },
  };
}

function empty(): FillDoc {
  return { formatVersion: 3, sheetKey: "main", rows: [] };
}

describe("fill format 3 in the browser", () => {
  it("reads a document of format 3 and writes it nested in its sheet", () => {
    const wire = {
      formatVersion: 3,
      sheets: [{ key: "main", rows: [{ id: "r1", cells: { c1: [FILE_A] } }, { id: "r3", cells: { c2: "Good" } }] }],
    };

    const read = fromApiFill(wire);

    expect(read.formatVersion).toBe(3);
    expect(read.sheetKey).toBe("main");
    expect(read.rows[0]?.cells.c1).toEqual([FILE_A]);
    expect(toWire(sheet(), read)).toEqual(wire);
  });

  it("writes the rows of the sheet first, in its order, and drops a row with nothing in it", () => {
    const fill: FillDoc = {
      formatVersion: 3,
      sheetKey: "main",
      rows: [{ id: "r3", cells: { c2: "Poor" } }, { id: "r1", cells: { c1: [FILE_A] } }, { id: "r2", cells: {} }],
    };

    expect(toApiFill(sheet(), fill).rows.map((row) => row.id)).toEqual(["r1", "r3"]);
  });

  it("a fill-in on a sheet of the first format is written as it always was", () => {
    const grid: GridDoc = { ...sheet(), formatVersion: 1, sheet: undefined };
    const fill: FillDoc = { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "x" } }] };

    expect(toWire(grid, fill)).toEqual({ formatVersion: 1, rows: [{ id: "r1", cells: { c2: "x" } }] });
  });

  it("a cell states its own type and the column's is used otherwise", () => {
    const grid = sheet();

    expect(cellSpec(grid, "r1", "c1")).toMatchObject({ type: "attachment", required: true, maxFiles: 2, cls: "filler" });
    expect(cellSpec(grid, "r1", "c2")).toMatchObject({ type: "text", required: false, maxFiles: 0 });
    expect(cellSpec(grid, "r3", "c2")).toMatchObject({ type: "picklist", picklist: ["Good", "Poor"] });
    expect(cellSpec(grid, "r2", "c1")).toMatchObject({ type: "number", cls: "reviewer", required: true });
  });

  it("a list cell takes its own listed values and a typed attachment is refused", () => {
    const grid = sheet();

    expect(setFillValue(grid, empty(), "r3", "c2", "Good", LIMITS).ok).toBe(true);
    expect(setFillValue(grid, empty(), "r3", "c2", "Fair", LIMITS).ok).toBe(false);
    expect(setFillValue(grid, empty(), "r1", "c1", "text", LIMITS)).toMatchObject({ ok: false });
  });

  it("names the files of an attachment cell, up to its limit, and an empty list clears it", () => {
    const grid = sheet();
    const one = setFillFiles(grid, empty(), "r1", "c1", [FILE_A]);
    expect(one.ok && one.fill.rows[0]?.cells.c1).toEqual([FILE_A]);

    expect(setFillFiles(grid, empty(), "r1", "c1", [FILE_A, FILE_B, "33333333-3333-4333-8333-333333333333"]).ok).toBe(
      false,
    );
    expect(setFillFiles(grid, empty(), "r3", "c2", [FILE_A]).ok).toBe(false);
    const cleared = one.ok ? setFillFiles(grid, one.fill, "r1", "c1", []) : one;
    expect(cleared.ok && cleared.fill.rows).toEqual([]);
  });

  it("the filler types the filler's cells, the reviewer the reviewer's, a corrector any", () => {
    const grid = sheet();

    expect(canEditAs(grid, "FILLER", "r3", "c2")).toBe(true);
    expect(canEditAs(grid, "FILLER", "r2", "c1")).toBe(false);
    expect(canEditAs(grid, "REVIEWER", "r2", "c1")).toBe(true);
    expect(canEditAs(grid, "REVIEWER", "r3", "c2")).toBe(false);
    expect(canEditAs(grid, "CORRECTOR", "r2", "c1")).toBe(true);
  });

  it("a calculated column and a cell with a formula are never typed into", () => {
    const grid = sheet();
    grid.sheet = { ...grid.sheet!, columnFormulas: { c2: "@{c1}*2" }, cells: { "r2:c2": { formula: "1+1" } } };

    expect(isInputCell(grid, "r3", "c2")).toBe(false);
    expect(isInputCell(grid, "r3", "c1")).toBe(true);
    expect(hasFormulas(grid)).toBe(true);
    expect(hasFormulas(sheet())).toBe(false);
  });

  it("asks only for the filler's required cells", () => {
    const grid = sheet();

    expect(missingRequired(grid, empty())).toEqual([{ rowId: "r1", columnId: "c1" }]);
  });

  it("puts the answers of a calculated column on the rows the server calculated them for", () => {
    const answers = answersByCell(
      {
        cells: [{ cell: "r9:c1", answer: { kind: "number", value: "7" } }],
        columns: [{ column: "c2", rows: [{ kind: "number", value: "5" }, { kind: "error", value: "#DIV/0!" }] }],
      },
      ["x1", "x2"],
    );

    expect(answers.text).toEqual({ "r9:c1": "7", "x1:c2": "5", "x2:c2": "#DIV/0!" });
    expect(answers.kinds["x2:c2"]).toBe("error");
  });

  it("reapplies a local change of files over a remote one without losing either", () => {
    const base: FillDoc = { formatVersion: 3, sheetKey: "main", rows: [{ id: "r1", cells: { c1: [FILE_A] } }] };
    const local: FillDoc = { ...base, rows: [{ id: "r1", cells: { c1: [FILE_A, FILE_B] } }] };
    const remote: FillDoc = { ...base, rows: [{ id: "r1", cells: { c1: [FILE_A] } }, { id: "r3", cells: { c2: "x" } }] };

    const merged = reapplyFill(base, local, remote, sheet());

    expect(merged.formatVersion).toBe(3);
    expect(merged.rows.find((row) => row.id === "r1")?.cells.c1).toEqual([FILE_A, FILE_B]);
    expect(merged.rows.find((row) => row.id === "r3")?.cells.c2).toBe("x");
  });
});
