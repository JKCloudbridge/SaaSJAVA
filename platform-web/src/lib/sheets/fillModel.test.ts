import { describe, expect, it } from "vitest";
import {
  addFillRow,
  canonicalFillText,
  cellOfPlace,
  clearFillCells,
  displayDoc,
  explainFillPlace,
  fillDisplayRows,
  fromApiFill,
  gridDisplayRows,
  isInputCell,
  missingRequired,
  needsARow,
  parseYesNo,
  pasteFill,
  problemsByCell,
  removeFillRow,
  sameFill,
  setFillValue,
  toApiFill,
} from "./fillModel";
import type { FillDoc, FillLimits } from "./fillTypes";
import { oneDimension, sampleDoc } from "./fixtures";
import type { GridDoc } from "./types";

const LIMITS: FillLimits = { maxBytes: 1_000_000, maxRows: 5, maxCellChars: 20 };
const EMPTY: FillDoc = { formatVersion: 1, rows: [] };

/** The sample sheet that lets the filler add rows after the three it names. */
function repeatableNamed(): GridDoc {
  return { ...sampleDoc(), rowsMode: "FIXED_AND_REPEATABLE" };
}

function fill(rows: FillDoc["rows"]): FillDoc {
  return { formatVersion: 1, rows };
}

describe("which cells take a value", () => {
  it("is an input cell unless the sheet fixes it", () => {
    const grid = sampleDoc();

    expect(isInputCell(grid, "r1", "c1")).toBe(false); // fixed text
    expect(isInputCell(grid, "r1", "c2")).toBe(true);
    expect(isInputCell(grid, "r3", "c1")).toBe(true); // no text, so an input cell
  });

  it("never takes a value in a header row, a column or a row the sheet does not know", () => {
    const grid = sampleDoc();

    expect(isInputCell(grid, "h1", "c2")).toBe(false);
    expect(isInputCell(grid, "r1", "nope")).toBe(false);
    expect(isInputCell(grid, "r9", "c2")).toBe(true); // a row the filler added has no cell the sheet fixes
  });

  it("covers the cells of a merged area but its top left cell", () => {
    const grid: GridDoc = { ...sampleDoc(), merges: [{ from: "r3:c2", to: "r3:c3" }] };

    expect(isInputCell(grid, "r3", "c2")).toBe(true);
    expect(isInputCell(grid, "r3", "c3")).toBe(false);
  });

  it("honours a cell the sheet says is fixed or input", () => {
    const grid: GridDoc = {
      ...sampleDoc(),
      cells: { ...sampleDoc().cells, "r3:c2": { kind: "fixed" }, "r1:c1": { value: "text", kind: "input" } },
    };

    expect(isInputCell(grid, "r3", "c2")).toBe(false);
    expect(isInputCell(grid, "r1", "c1")).toBe(true);
  });
});

describe("typing a value", () => {
  it("accepts a value that fits the column and keeps it as the typed digits", () => {
    const result = setFillValue(sampleDoc(), EMPTY, "r1", "c2", "12.50", LIMITS);

    expect(result.ok && result.fill.rows).toEqual([{ id: "r1", cells: { c2: "12.50" } }]);
  });

  it("refuses text that does not fit, in words, and changes nothing", () => {
    const grid = sampleDoc();

    for (const [column, text] of [["c2", "12a"], ["c2", "1,5"], ["c3", "Maybe"]] as const) {
      const result = setFillValue(grid, EMPTY, "r1", column, text, LIMITS);
      expect(result.ok).toBe(false);
    }
  });

  it("matches a list value whatever its case and writes the listed one", () => {
    const result = setFillValue(sampleDoc(), EMPTY, "r2", "c3", "pass", LIMITS);

    expect(result.ok && result.fill.rows[0]?.cells.c3).toBe("Pass");
  });

  it("reads yes and no the ways people write them", () => {
    expect(parseYesNo("Yes")).toBe(true);
    expect(parseYesNo(" n ")).toBe(false);
    expect(parseYesNo("maybe")).toBeNull();
    const grid: GridDoc = {
      ...sampleDoc(),
      columns: [...sampleDoc().columns, { id: "c4", label: "Done", type: "boolean" }],
    };
    const result = setFillValue(grid, EMPTY, "r2", "c4", "no", LIMITS);
    expect(result.ok && result.fill.rows[0]?.cells.c4).toBe(false);
    expect(setFillValue(grid, EMPTY, "r2", "c4", "perhaps", LIMITS)).toEqual({ ok: false, error: "Type yes or no." });
  });

  it("refuses a cell the sheet fixes, a row that does not exist and markup", () => {
    const grid = repeatableNamed();

    expect(setFillValue(grid, EMPTY, "r1", "c1", "mine", LIMITS)).toEqual({
      ok: false,
      error: "This cell is fixed text and cannot be typed into.",
    });
    expect(setFillValue(grid, EMPTY, "added", "c1", "x", LIMITS)).toEqual({ ok: false, error: "This row does not exist." });
    expect(setFillValue(grid, EMPTY, "r3", "c1", "<script>", LIMITS).ok).toBe(false);
    expect(setFillValue(grid, EMPTY, "r3", "c1", "x".repeat(21), LIMITS).ok).toBe(false);
    expect(setFillValue(grid, EMPTY, "r3", "c1", "x".repeat(20), LIMITS).ok).toBe(true);
  });

  it("clears a cell with no text and drops a row of the sheet that holds nothing", () => {
    const grid = sampleDoc();
    const typed = setFillValue(grid, EMPTY, "r1", "c2", "5", LIMITS);
    const cleared = typed.ok ? setFillValue(grid, typed.fill, "r1", "c2", "  ", LIMITS) : typed;

    expect(cleared.ok && cleared.fill.rows).toEqual([]);
  });

  it("keeps a row the filler added even when it holds nothing", () => {
    const grid = oneDimension();
    const added = addFillRow(grid, EMPTY, LIMITS);
    const id = added.ok ? (added.fill.rows[0]?.id ?? "") : "";
    const typed = setFillValue(grid, added.ok ? added.fill : EMPTY, id, "c2", "5", LIMITS);
    const cleared = typed.ok ? setFillValue(grid, typed.fill, id, "c2", "", LIMITS) : typed;

    expect(cleared.ok && cleared.fill.rows.map((row) => row.id)).toEqual([id]);
  });
});

describe("rows", () => {
  it("adds a row only where the sheet allows it, with each column's default", () => {
    const grid: GridDoc = oneDimension();
    grid.columns = grid.columns.map((column) => (column.id === "c3" ? { ...column, default: "Pass" } : column));

    const added = addFillRow(grid, EMPTY, LIMITS);

    expect(added.ok && added.fill.rows[0]?.cells).toEqual({ c3: "Pass" });
    expect(addFillRow(sampleDoc(), EMPTY, LIMITS)).toEqual({
      ok: false,
      error: "The rows of this sheet are fixed: a row cannot be added.",
    });
  });

  it("stops at the most rows, counting the rows the sheet names", () => {
    const grid = repeatableNamed(); // three named rows, a limit of five
    let current = EMPTY;
    for (let i = 0; i < 2; i++) {
      const next = addFillRow(grid, current, LIMITS);
      expect(next.ok).toBe(true);
      current = next.ok ? next.fill : current;
    }

    const over = addFillRow(grid, current, LIMITS);

    expect(over.ok).toBe(false);
  });

  it("gives every new row an identifier no column, header row or row uses", () => {
    const grid = oneDimension();
    const ids = new Set<string>();
    let current = EMPTY;
    for (let i = 0; i < 4; i++) {
      const next = addFillRow(grid, current, LIMITS);
      current = next.ok ? next.fill : current;
    }
    for (const row of current.rows) {
      expect(ids.has(row.id)).toBe(false);
      ids.add(row.id);
      expect(row.id).toMatch(/^[A-Za-z0-9_-]{1,40}$/);
      expect(["c1", "c2", "c3", "h1", "h2"]).not.toContain(row.id);
    }
  });

  it("removes a row the filler added and never a row of the sheet", () => {
    const grid = repeatableNamed();
    const added = addFillRow(grid, EMPTY, LIMITS);
    const id = added.ok ? (added.fill.rows[0]?.id ?? "") : "";

    expect(removeFillRow(grid, added.ok ? added.fill : EMPTY, id)).toEqual({ ok: true, fill: EMPTY });
    expect(removeFillRow(grid, EMPTY, "r1")).toEqual({ ok: false, error: "A row of the sheet cannot be removed." });
    expect(removeFillRow(grid, EMPTY, "nope")).toEqual({ ok: false, error: "This row does not exist." });
  });

  it("draws the rows of the sheet and then the added ones, below the header rows", () => {
    const grid = repeatableNamed();
    const current = fill([{ id: "x1", cells: { c2: "1" } }, { id: "r2", cells: { c2: "2" } }]);

    expect(fillDisplayRows(grid, current).map((row) => [row.id, row.added])).toEqual([
      ["r1", false],
      ["r2", false],
      ["r3", false],
      ["x1", true],
    ]);
    expect(gridDisplayRows(grid, current).map((row) => row.zone)).toEqual([
      "header", "header", "body", "body", "body", "body",
    ]);
  });

  it("says that a sheet whose filler adds every row needs one", () => {
    expect(needsARow(oneDimension(), EMPTY)).toBe(true);
    expect(needsARow(oneDimension(), fill([{ id: "a", cells: {} }]))).toBe(false);
    expect(needsARow(sampleDoc(), EMPTY)).toBe(false);
  });
});

describe("the form the server is sent", () => {
  it("puts the rows of the sheet first in its order, then the added rows in theirs, and leaves out what is empty", () => {
    const grid = repeatableNamed();
    const current = fill([
      { id: "zz", cells: { c2: "9" } },
      { id: "r3", cells: { c2: "3" } },
      { id: "empty", cells: {} },
      { id: "r1", cells: { c2: "1" } },
      { id: "r2", cells: {} },
    ]);

    expect(toApiFill(grid, current).rows.map((row) => row.id)).toEqual(["r1", "r3", "zz", "empty"]);
  });

  it("is the same text whatever the order of the keys", () => {
    const grid = sampleDoc();
    const one = fill([{ id: "r1", cells: { c2: "1", c3: "Pass" } }]);
    const other = fill([{ id: "r1", cells: { c3: "Pass", c2: "1" } }]);

    expect(canonicalFillText(grid, one)).toBe(canonicalFillText(grid, other));
    expect(sameFill(grid, one, other)).toBe(true);
    expect(sameFill(grid, one, fill([{ id: "r1", cells: { c2: "2", c3: "Pass" } }]))).toBe(false);
  });

  it("reads an answer of the API and ignores what it does not know", () => {
    const read = fromApiFill({
      rows: [
        { id: "a", cells: { c1: "x", c2: false, c3: 7, c4: null } },
        { cells: { c1: "no id" } },
        "not a row",
      ],
    });

    expect(read).toEqual({ formatVersion: 1, rows: [{ id: "a", cells: { c1: "x", c2: false } }] });
    expect(fromApiFill(null)).toEqual(EMPTY);
    expect(fromApiFill({ rows: "x" })).toEqual(EMPTY);
  });
});

describe("what the grid draws", () => {
  it("lays the values over the sheet as input cells and drops what an author left in an input cell", () => {
    const grid: GridDoc = {
      ...sampleDoc(),
      cells: { ...sampleDoc().cells, "r3:c2": { value: "2", kind: "input" } },
    };
    const current = fill([{ id: "r1", cells: { c2: "5" } }, { id: "add", cells: { c1: "mine" } }]);

    const doc = displayDoc(grid, current);

    expect(doc.rows.map((row) => row.id)).toEqual(["r1", "r2", "r3", "add"]);
    expect(doc.cells["r1:c2"]).toEqual({ value: "5", kind: "input" });
    expect(doc.cells["add:c1"]).toEqual({ value: "mine", kind: "input" });
    expect(doc.cells["r1:c1"]).toEqual({ value: "Fire exit" });
    expect(doc.cells["r3:c2"]).toBeUndefined();
    expect(doc.cells["h1:c1"]).toBeDefined();
  });

  it("clears the input cells in the rows and columns asked and leaves the fixed ones", () => {
    const grid = sampleDoc();
    const current = fill([{ id: "r1", cells: { c2: "1", c3: "Pass" } }, { id: "r2", cells: { c2: "2" } }]);

    const next = clearFillCells(grid, current, ["r1", "r2"], ["c1", "c2"]);

    expect(next.rows).toEqual([{ id: "r1", cells: { c3: "Pass" } }]);
  });
});

describe("pasting", () => {
  it("fills the input cells it lands on and counts what it skipped", () => {
    const grid = sampleDoc();

    const pasted = pasteFill(grid, EMPTY, gridDisplayRows(grid, EMPTY), 2, 0, [["x", "1", "Pass"], ["y", "z", "Fail"]], LIMITS);

    // Display row 2 is r1 (after the two header rows); its first cell is fixed text.
    expect(pasted.fill.rows).toEqual([
      { id: "r1", cells: { c2: "1", c3: "Pass" } },
      { id: "r2", cells: { c3: "Fail" } },
    ]);
    expect(pasted.applied).toBe(3);
    expect(pasted.skipped).toBe(3);
    expect(pasted.problems.length).toBeGreaterThan(0);
  });

  it("adds rows past the last one where the sheet lets the filler add them", () => {
    const grid = oneDimension();

    const pasted = pasteFill(grid, EMPTY, gridDisplayRows(grid, EMPTY), 2, 1, [["1"], ["2"], ["3"]], LIMITS);

    expect(pasted.fill.rows.map((row) => row.cells.c2)).toEqual(["1", "2", "3"]);
    expect(pasted.skipped).toBe(0);
  });

  it("skips what lands in a header row or past the last column", () => {
    const grid = sampleDoc();

    const pasted = pasteFill(grid, EMPTY, gridDisplayRows(grid, EMPTY), 0, 2, [["a", "b"]], LIMITS);

    expect(pasted.fill).toEqual(EMPTY);
    expect(pasted.skipped).toBe(2);
  });
});

describe("what is still to fill in", () => {
  it("asks for a required value in every row, the rows of the sheet that hold nothing included", () => {
    const grid = repeatableNamed();
    const current = fill([{ id: "r1", cells: { c2: "1" } }, { id: "x", cells: { c3: "Pass" } }]);

    expect(missingRequired(grid, current)).toEqual([
      { rowId: "r2", columnId: "c2" },
      { rowId: "r3", columnId: "c2" },
      { rowId: "x", columnId: "c2" },
    ]);
  });

  it("does not ask for a cell the sheet fixes", () => {
    const grid: GridDoc = { ...sampleDoc(), cells: { ...sampleDoc().cells, "r2:c2": { value: "n/a" } } };

    expect(missingRequired(grid, EMPTY).map((missing) => missing.rowId)).toEqual(["r1", "r3"]);
  });
});

describe("the places of the server's refusals", () => {
  const grid = repeatableNamed();
  const sent = fill([{ id: "r3", cells: { c2: "x" } }, { id: "added", cells: { c2: "y" } }]);

  it("finds the cell a position or a row identifier names, counting in the document that was sent", () => {
    expect(cellOfPlace(grid, sent, "cells.rows[0].cells.c2")).toEqual({ rowId: "r3", columnId: "c2" });
    expect(cellOfPlace(grid, sent, "cells.rows[1].cells.c2")).toEqual({ rowId: "added", columnId: "c2" });
    expect(cellOfPlace(grid, sent, "cells.rows.r2.cells.c2")).toEqual({ rowId: "r2", columnId: "c2" });
    expect(cellOfPlace(grid, sent, "cells.rows[9].cells.c2")).toBeNull();
    expect(cellOfPlace(grid, sent, "cells.rows")).toBeNull();
  });

  it("says where a place is in the words of the sheet", () => {
    expect(explainFillPlace(grid, sent, "cells.rows[0].cells.c2")).toBe("Third, Amount");
    expect(explainFillPlace(grid, sent, "cells.rows[1].cells.c2")).toBe("Row 4, Amount");
    expect(explainFillPlace(grid, sent, "cells")).toBe("The fill-in");
    expect(explainFillPlace(grid, sent, "cells.rows")).toBe("rows");
  });

  it("gathers the refusals by cell for the form view and returns the rest apart", () => {
    const result = problemsByCell(grid, sent, [
      { place: "cells.rows[0].cells.c2", message: "A number column holds digits." },
      { place: "cells.rows[0].cells.c2", message: "Another." },
      { place: "cells.rows", message: "Too many rows." },
    ]);

    expect(result.cells.get("r3:c2")).toEqual(["A number column holds digits.", "Another."]);
    expect(result.other).toEqual([{ place: "cells.rows", message: "Too many rows." }]);
  });
});
