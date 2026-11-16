import { describe, expect, it } from "vitest";
import { cellAddress, columnLetters, rowNumber } from "./addresses";
import { toExcel, type Layout } from "./formula/translate";

describe("the addresses drawn beside a sheet (SH5.1)", () => {
  it("counts columns as a spreadsheet does", () => {
    expect([0, 1, 25, 26, 27, 51, 52, 701, 702].map(columnLetters)).toEqual([
      "A", "B", "Z", "AA", "AB", "AZ", "BA", "ZZ", "AAA",
    ]);
    expect(rowNumber(0)).toBe(1);
    expect(cellAddress(11, 1)).toBe("B12");
  });

  it("names every cell exactly as the formula bar writes a reference to it", () => {
    // two header rows and three named rows; the grid draws them in this order
    const layout: Layout = {
      columnIds: ["c1", "c2", "c3", "c4"],
      rowIds: ["h1", "h2", "r1", "r2", "r3"],
      growing: false,
      bodyRows: 0,
    };
    layout.rowIds.forEach((rowId, row) => {
      layout.columnIds.forEach((columnId, column) => {
        expect(toExcel(`@{${columnId},${rowId}}`, 1, layout)).toBe(cellAddress(row, column));
      });
    });
  });

  it("numbers the rows a filler adds after the header rows, as the formula bar does", () => {
    const layout: Layout = { columnIds: ["c1", "c2"], rowIds: ["h1"], growing: true, bodyRows: 3 };
    // the whole column is the rows after the one header row: rows 2 to 4
    expect(toExcel("SUM(@{c2,*})", 1, layout)).toBe(`SUM(${cellAddress(1, 1)}:${cellAddress(3, 1)})`);
  });
});
