import { describe, expect, it } from "vitest";
import type { ImportReport } from "./api";
import { changedOnTheWay, kindOfFile, leftOut, limitWords, refusalBeforeSending, reportSummary } from "./upload";

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
});
