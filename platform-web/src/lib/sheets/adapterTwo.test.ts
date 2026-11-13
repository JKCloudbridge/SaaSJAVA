import { describe, expect, it } from "vitest";
import { canonicalText, fromApi, sameDocument, toApi } from "./adapter";
import { removeRow, setCellText, setTextColour } from "./gridModel";
import type { GridDoc, SheetLimits } from "./types";

/**
 * A sheet in format version 2 passes through the present editor without losing what the editor does not know
 * (SH1A, ADR-0251): its look, its links, its stated input cells, its panes, its page set-up, its title, its other
 * sheets. The editor changes a text, a width or a merge; the rest comes back exactly as it was.
 */

const LIMITS: SheetLimits = {
  maxBytes: 1_000_000,
  maxRows: 500,
  maxColumns: 100,
  maxHeaderRows: 10,
  maxCellChars: 1000,
  maxLabelChars: 200,
  maxPicklistValues: 100,
  maxMerges: 2000,
};

/** A document as the server writes it: every part of format version 2 in use, colours in lower case. */
function rich(): Record<string, unknown> {
  return {
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        title: "Plant activity",
        freeze: { rows: 1, columns: 1 },
        print: { paper: "A4", orientation: "LANDSCAPE", fitToWidth: true, repeatHeaderRows: true },
        inputs: { "r1:c1": { type: "attachment", required: true, maxFiles: 3 } },
        conditions: [{ from: "r1:c2", to: "r2:c2", operator: "LT", value: "0.85", fill: "#ffc7ce", colour: "#9c0006" }],
        dimension: 2,
        rowsMode: "FIXED",
        columns: [
          { id: "c1", label: "Item", type: "text", width: 8 },
          { id: "c2", label: "Amount", type: "number" },
        ],
        headerRows: [{ id: "h1" }],
        rows: [
          { id: "r1", label: "First", height: 4 },
          { id: "r2", label: "Second" },
        ],
        cells: {
          "h1:c1": {
            value: "Item",
            style: { bold: true },
            look: {
              borders: { bottom: "DOUBLE", top: "THIN" },
              colour: "#112233",
              fill: "#ffeecc",
              font: "SANS_NEUTRAL",
              size: 12,
              wrap: true,
            },
          },
          "h1:c2": { value: "Open the guide", link: "https://example.test/guide" },
          "r1:c2": { look: { fill: "#aabbcc", numberFormat: "TWO_DECIMALS", verticalAlignment: "TOP" } },
          "r2:c1": { value: "Fixed note", look: { borders: { left: "THICK" } } },
        },
        merges: [],
      },
    ],
  };
}

function fresh(): GridDoc {
  return fromApi(JSON.parse(JSON.stringify(rich())));
}

function sheetOf(sent: unknown): Record<string, unknown> {
  return (sent as { sheets: Record<string, unknown>[] }).sheets[0] ?? {};
}

function cellsOf(sent: unknown): Record<string, Record<string, unknown> | undefined> {
  return sheetOf(sent).cells as Record<string, Record<string, unknown> | undefined>;
}

function ok(result: { ok: boolean; doc?: GridDoc }): GridDoc {
  expect(result.ok).toBe(true);
  return result.doc as GridDoc;
}

describe("a sheet in format version 2 in the editor", () => {
  it("is read as a document of format version 2 with what the editor does not know kept beside it", () => {
    const doc = fresh();

    expect(doc.formatVersion).toBe(2);
    expect(doc.sheet?.key).toBe("main");
    expect(doc.sheet?.title).toBe("Plant activity");
    expect(Object.keys(doc.sheet?.cells ?? {}).sort()).toEqual(["h1:c1", "h1:c2", "r1:c2", "r2:c1"]);
    expect(doc.columns[0]?.width).toBe(8);
    expect(doc.rows[0]?.height).toBe(4);
  });

  it("is written back exactly as it was when nothing was changed", () => {
    const sent = JSON.parse(JSON.stringify(toApi(fresh())));

    expect(sent).toEqual(rich());
    expect(sameDocument(fresh(), fromApi(JSON.parse(JSON.stringify(rich()))))).toBe(true);
  });

  it("keeps everything else exactly when one text is edited", () => {
    const edited = ok(setCellText(fresh(), "r2", "c1", "Changed note", LIMITS));

    const sent = JSON.parse(JSON.stringify(toApi(edited)));

    const expected = rich();
    Object.assign(cellsOf(expected)["r2:c1"] ?? {}, { value: "Changed note" });
    expect(sent).toEqual(expected);
    expect(sameDocument(edited, fresh())).toBe(false);
  });

  it("writes the look of a cell whose text was cleared, and drops a link whose text was cleared", () => {
    const cleared = ok(setCellText(ok(setCellText(fresh(), "r2", "c1", "", LIMITS)), "h1", "c2", "", LIMITS));

    const cells = cellsOf(JSON.parse(JSON.stringify(toApi(cleared))));

    expect(cells["r2:c1"]).toEqual({ look: { borders: { left: "THICK" } } });
    expect(cells["h1:c2"]).toBeUndefined();
  });

  it("drops what belongs to a row that was removed, and nothing else", () => {
    const removed = ok(removeRow(fresh(), "r1"));

    const sheet = sheetOf(JSON.parse(JSON.stringify(toApi(removed))));

    expect(sheet.inputs).toBeUndefined();
    expect(sheet.conditions).toBeUndefined();
    expect(Object.keys(sheet.cells as object).sort()).toEqual(["h1:c1", "h1:c2", "r2:c1"]);
    expect(sheet.freeze).toEqual({ rows: 1, columns: 1 });
    expect(sheet.print).toEqual(sheetOf(rich()).print);
    expect(sheet.title).toBe("Plant activity");
  });

  it("lets the author's new text colour win over the colour of the look, so the server is never asked twice", () => {
    const coloured = ok(setTextColour(fresh(), ["h1:c1"], "DANGER"));

    const cell = cellsOf(JSON.parse(JSON.stringify(toApi(coloured))))["h1:c1"];

    expect(cell?.style).toEqual({ bold: true, colour: "DANGER" });
    expect(cell?.look).toEqual({
      borders: { bottom: "DOUBLE", top: "THIN" },
      fill: "#ffeecc",
      font: "SANS_NEUTRAL",
      size: 12,
      wrap: true,
    });
  });

  it("keeps the sheets after the first untouched", () => {
    const raw = rich();
    const second = { ...sheetOf(rich()), key: "second", title: "Second" };
    (raw.sheets as unknown[]).push(second);

    const sent = JSON.parse(JSON.stringify(toApi(fromApi(JSON.parse(JSON.stringify(raw))))));

    expect(sent).toEqual(raw);
  });

  it("tells that something changed only when something did", () => {
    const doc = fresh();

    expect(canonicalText(doc)).toBe(canonicalText(fresh()));
    expect(canonicalText(ok(setCellText(doc, "r2", "c1", "Changed note", LIMITS)))).not.toBe(canonicalText(doc));
  });

  it("leaves a sheet in format version 1 exactly as it was", () => {
    const v1 = {
      formatVersion: 1,
      dimension: 2,
      rowsMode: "FIXED",
      columns: [{ id: "c1", label: "Item", type: "text" }],
      headerRows: [{ id: "h1" }],
      rows: [{ id: "r1", label: "First" }],
      cells: { "h1:c1": { value: "Item", style: { bold: true } } },
      merges: [],
    };

    const doc = fromApi(v1);

    expect(doc.formatVersion).toBe(1);
    expect(doc.sheet).toBeUndefined();
    expect(JSON.parse(JSON.stringify(toApi(doc)))).toEqual(v1);
  });
});
