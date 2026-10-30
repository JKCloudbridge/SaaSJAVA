import { describe, expect, it } from "vitest";
import { sampleDoc } from "./fixtures";
import { orientationOf, PORTRAIT_WIDTH, printModel, sheetWidth } from "./print";
import type { GridDoc } from "./types";

// The print view's model (FA7, ADR-0201): what the page draws, tested without a browser.
describe("the print model of a sheet", () => {
  it("repeats the column labels and the header rows on every page and keeps the body apart", () => {
    const model = printModel(sampleDoc());

    expect(model.head.map((row) => row.id)).toEqual(["labels", "h1", "h2"]);
    expect(model.head[0]?.cells.map((cell) => cell.text)).toEqual(["Item", "Amount", "Result"]);
    expect(model.head[1]?.cells[0]).toMatchObject({ text: "Inspection", header: true, style: { bold: true, fontSize: "TITLE" } });
    expect(model.body.map((row) => row.id)).toEqual(["r1", "r2", "r3"]);
  });

  it("leaves out the band of column labels when asked to, and keeps the header rows (FA10)", () => {
    const model = printModel(sampleDoc(), { labels: false });

    expect(model.head.map((row) => row.id)).toEqual(["h1", "h2"]);
    expect(model.body.map((row) => row.id)).toEqual(["r1", "r2", "r3"]);
  });

  it("shows the cells a filler types into empty and the sheet's own text as it is", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      cells: {
        ...sampleDoc().cells,
        "r1:c2": { value: "typed by a filler", kind: "input" },
        "r2:c2": { value: "fixed by the sheet", kind: "fixed" },
        "r3:c2": { value: true, kind: "fixed" },
      },
    };
    const body = printModel(doc).body;

    expect(body[0]?.cells[0]?.text).toBe("Fire exit");
    expect(body[0]?.cells[1]?.text).toBe("");
    expect(body[1]?.cells[1]?.text).toBe("fixed by the sheet");
    expect(body[2]?.cells[1]?.text).toBe("Yes");
  });

  it("shows what a filler typed into the input cells when asked to (a fill-in)", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      cells: { ...sampleDoc().cells, "r1:c2": { value: "typed by a filler", kind: "input" } },
    };

    expect(printModel(doc, { showInput: true }).body[0]?.cells[1]?.text).toBe("typed by a filler");
    expect(printModel(doc).body[0]?.cells[1]?.text).toBe("");
  });

  it("gives the columns shares of the page width that add up to the whole, so the sheet is fitted to it", () => {
    const model = printModel(sampleDoc());

    expect(model.columns.reduce((sum, column) => sum + column.percent, 0)).toBeCloseTo(100, 6);
    expect(model.columns[0]?.percent).toBeCloseTo(40, 6);
  });

  it("draws a merged area once, spanning its rows and columns, and leaves out the cells under it", () => {
    const doc: GridDoc = { ...sampleDoc(), merges: [{ from: "r1:c1", to: "r2:c2" }] };
    const body = printModel(doc).body;

    expect(body[0]?.cells[0]).toMatchObject({ rowSpan: 2, colSpan: 2 });
    expect(body[0]?.cells).toHaveLength(2);
    expect(body[1]?.cells).toHaveLength(1);
    expect(body[2]?.cells).toHaveLength(3);
  });

  it("shows the same blank sample rows as the screen when the filler adds the rows", () => {
    const doc: GridDoc = { ...sampleDoc(), dimension: 1, rowsMode: "REPEATABLE", rows: [] };

    expect(printModel(doc).body).toHaveLength(10);
  });

  it("keeps the height of a row and the style of a cell", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      rows: [{ id: "r1", label: "First", height: 60 }],
      cells: { "r1:c1": { value: "Door", style: { italic: true, alignment: "CENTER" } } },
    };
    const row = printModel(doc).body[0];

    expect(row?.height).toBe(60);
    expect(row?.cells[0]?.style).toEqual({ italic: true, alignment: "CENTER" });
  });
});

describe("the page of a sheet", () => {
  it("is portrait for a narrow sheet and landscape for a wide one", () => {
    expect(sheetWidth(sampleDoc())).toBe(400);
    expect(orientationOf(sampleDoc())).toBe("portrait");
    const wide: GridDoc = {
      ...sampleDoc(),
      columns: sampleDoc().columns.map((column) => ({ ...column, width: PORTRAIT_WIDTH })),
    };
    expect(orientationOf(wide)).toBe("landscape");
    expect(printModel(wide).orientation).toBe("landscape");
  });

  it("is portrait at exactly the width a portrait page holds", () => {
    const edge: GridDoc = {
      ...sampleDoc(),
      columns: [{ id: "c1", label: "Only", type: "text", width: PORTRAIT_WIDTH }],
    };
    expect(orientationOf(edge)).toBe("portrait");
  });
});
