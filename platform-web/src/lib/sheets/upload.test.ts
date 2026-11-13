import { describe, expect, it } from "vitest";
import type { ImportReport } from "./api";
import {
  changedOnTheWay,
  hasWorksheetChoice,
  kindOfFile,
  leftOut,
  limitWords,
  placesWords,
  refusalBeforeSending,
  reportSummary,
  worksheetWords,
} from "./upload";

const REPORT: ImportReport = {
  columns: 12,
  rows: 80,
  mergedAreas: 3,
  notes: [
    { code: "images", count: 2, leftOut: true, words: "2 images were left out." },
    { code: "dates", count: 4, leftOut: false, words: "4 dates were written year-month-day." },
  ],
};

describe("the kind of file", () => {
  it("is read from the extension, in any case", () => {
    expect(kindOfFile("Checks.XLSX")).toBe("xlsx");
    expect(kindOfFile("list.csv")).toBe("csv");
    expect(kindOfFile(" list.csv ")).toBe("csv");
  });

  it("is nothing for a file the screen does not offer to read", () => {
    for (const name of ["old.xls", "macros.xlsm", "sheet.ods", "notes.txt", "xlsx", ""]) {
      expect(kindOfFile(name)).toBeNull();
    }
  });
});

describe("the limit in words", () => {
  it("uses megabytes, kilobytes and bytes", () => {
    expect(limitWords(5_000_000)).toBe("5 MB");
    expect(limitWords(2_500_000)).toBe("2.5 MB");
    expect(limitWords(64_000)).toBe("64 KB");
    expect(limitWords(900)).toBe("900 bytes");
  });
});

describe("what spares a long upload", () => {
  it("refuses a kind that is not offered, an empty file and one over the size the API states", () => {
    expect(refusalBeforeSending({ name: "a.ods", size: 10 }, 1000)).toMatch(/\.xlsx.*\.csv/);
    expect(refusalBeforeSending({ name: "a.csv", size: 0 }, 1000)).toBe("The file is empty.");
    expect(refusalBeforeSending({ name: "a.csv", size: 1001 }, 1000)).toBe("The file is larger than the allowed 1 KB.");
  });

  it("lets a good file through, and any size when the API stated none", () => {
    expect(refusalBeforeSending({ name: "a.csv", size: 1000 }, 1000)).toBeNull();
    expect(refusalBeforeSending({ name: "a.xlsx", size: 99_999_999 }, undefined)).toBeNull();
  });
});

describe("the words for what a file gives", () => {
  it("says the columns, the rows and the merged areas", () => {
    expect(reportSummary(REPORT)).toBe("12 columns, 80 rows, 3 merged areas");
    expect(reportSummary({ columns: 1, rows: 1, mergedAreas: 0, notes: [] })).toBe("1 column, 1 row");
    expect(reportSummary({ columns: 4, rows: 0, mergedAreas: 1, notes: [] })).toBe(
      "4 columns, no named rows, 1 merged area",
    );
  });

  it("separates what was left out from what came in another form", () => {
    expect(leftOut(REPORT).map((note) => note.code)).toEqual(["images"]);
    expect(changedOnTheWay(REPORT).map((note) => note.code)).toEqual(["dates"]);
  });

  it("shows the kept blank cells and the title row with what came in, never with what was left out", () => {
    const report: ImportReport = {
      ...REPORT,
      firstRowLabels: false,
      firstRowIsTitle: true,
      notes: [
        { code: "bordered_blanks", count: 19, leftOut: false, words: "19 blank cells with a border were kept as cells to be filled in." },
        { code: "title_row", count: 1, leftOut: false, words: "1 title row was found." },
        { code: "images", count: 1, leftOut: true, words: "1 image was left out." },
      ],
    };

    expect(changedOnTheWay(report).map((note) => note.code)).toEqual(["bordered_blanks", "title_row"]);
    expect(leftOut(report).map((note) => note.code)).toEqual(["images"]);
    expect(reportSummary(report)).toBe("12 columns, 80 rows, 3 merged areas");
  });
});

describe("the choice of a worksheet", () => {
  it("is offered only when the workbook has more than one visible worksheet", () => {
    expect(hasWorksheetChoice(REPORT)).toBe(false);
    expect(hasWorksheetChoice({ ...REPORT, worksheets: [{ index: 0, title: "A", rows: 1, columns: 1 }] })).toBe(false);
    expect(
      hasWorksheetChoice({
        ...REPORT,
        worksheets: [
          { index: 0, title: "A", rows: 1, columns: 1 },
          { index: 1, title: "B", rows: 1, columns: 1 },
        ],
      }),
    ).toBe(true);
  });

  it("names a worksheet by its title and the size the file says, or by its number when it has no usable title", () => {
    expect(worksheetWords({ index: 1, title: "Data", rows: 60, columns: 22 })).toBe("Data (about 60 rows, 22 columns)");
    expect(worksheetWords({ index: 1, rows: 1, columns: 1 })).toBe("Worksheet 2 (about 1 row, 1 column)");
    expect(worksheetWords({ index: 0, title: "Empty", rows: 0, columns: 0 })).toBe("Empty");
  });

  it("writes the cells where embedded files stood as a person reads them in the program", () => {
    expect(
      placesWords([
        { row: 12, column: "B" },
        { row: 14, column: "AA" },
      ]),
    ).toBe("B12, AA14");
  });
});
