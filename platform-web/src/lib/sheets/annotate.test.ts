import { describe, expect, it } from "vitest";
import { describePlace, marksOf, placesOfSelection, samePlace, selectionOfPlace } from "./annotate";
import { displayRows } from "./geometry";
import { oneDimension, sampleDoc } from "./fixtures";

const sel = (r: number, c: number) => ({ ar: r, ac: c, fr: r, fc: c });

describe("the places the active cell stands for", () => {
  it("gives the cell, its row and its column on a named row", () => {
    const doc = sampleDoc();
    const rows = displayRows(doc);

    // Display order: h1, h2, r1, r2, r3.
    expect(placesOfSelection(doc, rows, sel(2, 1))).toEqual({
      cell: { rowId: "r1", columnId: "c2" },
      row: { rowId: "r1", columnId: null },
      column: { rowId: null, columnId: "c2" },
    });
  });

  it("counts a header row as a row", () => {
    const doc = sampleDoc();

    expect(placesOfSelection(doc, displayRows(doc), sel(0, 0)).row).toEqual({ rowId: "h1", columnId: null });
  });

  it("gives no cell and no row on a blank sample row, only the column", () => {
    const doc = oneDimension();
    const rows = displayRows(doc);
    const blank = rows.findIndex((row) => row.zone === "blank");

    const places = placesOfSelection(doc, rows, sel(blank, 2));

    expect(places.cell).toBeNull();
    expect(places.row).toBeNull();
    expect(places.column).toEqual({ rowId: null, columnId: "c3" });
  });
});

describe("standing the selection on a place", () => {
  it("finds a cell, a row and a column by their identifiers", () => {
    const doc = sampleDoc();
    const rows = displayRows(doc);

    expect(selectionOfPlace(doc, rows, { rowId: "r2", columnId: "c3" })).toEqual(sel(3, 2));
    expect(selectionOfPlace(doc, rows, { rowId: "r3", columnId: null })).toEqual(sel(4, 0));
    expect(selectionOfPlace(doc, rows, { rowId: null, columnId: "c2" })).toEqual(sel(0, 1));
  });

  it("finds nothing for a place the version does not have, or for a blank sample row", () => {
    const doc = oneDimension();
    const rows = displayRows(doc);

    expect(selectionOfPlace(doc, rows, { rowId: "gone", columnId: null })).toBeNull();
    expect(selectionOfPlace(doc, rows, { rowId: null, columnId: "gone" })).toBeNull();
    expect(selectionOfPlace(doc, rows, { rowId: "blank0", columnId: "c1" })).toBeNull();
  });
});

describe("a place in words", () => {
  it("names a cell, a row, a column and the whole sheet", () => {
    const doc = sampleDoc();
    const rows = displayRows(doc);

    expect(describePlace(doc, rows, { rowId: "r1", columnId: "c2" })).toBe("Cell: column “Amount”, row “First”");
    expect(describePlace(doc, rows, { rowId: "r3", columnId: null })).toBe("Row “Third”");
    expect(describePlace(doc, rows, { rowId: null, columnId: "c1" })).toBe("Column “Item”");
    expect(describePlace(doc, rows, { rowId: null, columnId: null })).toBe("The whole sheet");
  });

  it("falls back to the position for a row or a column with no name", () => {
    const doc = sampleDoc();
    const rows = displayRows(doc);

    expect(describePlace(doc, rows, { rowId: "h1", columnId: null })).toBe("Row number 1");
    expect(describePlace({ ...doc, columns: [{ id: "c1", label: "", type: "text" }] }, rows, { rowId: null, columnId: "c1" }))
      .toBe("Column number 1");
  });
});

describe("the marks of a version", () => {
  it("counts the notes on each cell, row and column, and leaves the whole template out", () => {
    const marks = marksOf([
      { rowId: "r1", columnId: "c1" },
      { rowId: "r1", columnId: "c1" },
      { rowId: "r2", columnId: null },
      { rowId: null, columnId: "c3" },
      { rowId: null, columnId: null },
    ]);

    expect(marks.cells.get("r1:c1")).toBe(2);
    expect(marks.rows.get("r2")).toBe(1);
    expect(marks.columns.get("c3")).toBe(1);
    expect(marks.cells.size + marks.rows.size + marks.columns.size).toBe(3);
  });

  it("compares two places by their identifiers", () => {
    expect(samePlace({ rowId: "r1", columnId: null }, { rowId: "r1", columnId: null })).toBe(true);
    expect(samePlace({ rowId: "r1", columnId: null }, { rowId: "r1", columnId: "c1" })).toBe(false);
  });
});
