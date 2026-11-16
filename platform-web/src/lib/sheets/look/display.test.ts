import { describe, expect, it } from "vitest";
import type { GridDoc } from "../types";
import { attachmentWords, displayOf, typeOf } from "./display";
import { readLook } from "./look";
import { isLooked, answerMaps, lookSource, INPUT_LINES } from "./sheetLook";

function twoDoc(): GridDoc {
  return {
    formatVersion: 2,
    dimension: 2,
    rowsMode: "FIXED",
    columns: [
      { id: "c1", label: "Name", type: "text" },
      { id: "c2", label: "Amount", type: "number" },
      { id: "c3", label: "Done", type: "boolean" },
      { id: "c4", label: "Grade", type: "picklist", picklist: { values: ["A", "B", "C", "D", "E", "F", "G"] } },
      { id: "c5", label: "Files", type: "text" },
    ],
    headerRows: [{ id: "h1" }],
    rows: [{ id: "r1" }],
    cells: { "h1:c1": { value: "Name" }, "r1:c1": { value: "Pump", kind: "fixed" } },
    merges: [],
    sheet: { key: "main", cells: {}, inputs: { "r1:c5": { type: "attachment" } } },
  };
}

const none = readLook(undefined);

// What a cell shows, in words (SH4).
describe("what a cell shows", () => {
  it("shows a formula's answer in its number format, an error as its code and a text as it is", () => {
    const doc = twoDoc();
    const percent = readLook({ numberFormat: "PERCENT" });

    expect(displayOf(doc, "r1", "c2", "body", undefined, percent, { text: "0.256", kind: "number" }, false)).toMatchObject({ text: "26%", number: 0.256 });
    expect(displayOf(doc, "r1", "c2", "body", undefined, percent, { text: "#DIV/0!", kind: "error" }, false)).toMatchObject({ text: "#DIV/0!", number: null });
    expect(displayOf(doc, "r1", "c2", "body", undefined, percent, { text: "0.5", kind: "text" }, false)).toMatchObject({ text: "0.5", number: null });
  });

  it("shows yes and no in words, a picklist value as it is and nothing for a formula without an answer", () => {
    const doc = twoDoc();

    expect(displayOf(doc, "r1", "c3", "body", { value: true, kind: "input" }, none, undefined, false).text).toBe("Yes");
    expect(displayOf(doc, "r1", "c3", "body", { value: false, kind: "input" }, none, undefined, false).text).toBe("No");
    expect(displayOf(doc, "r1", "c4", "body", { value: "B", kind: "input" }, none, undefined, false).text).toBe("B");
    expect(displayOf(doc, "r1", "c1", "body", undefined, none, undefined, false).text).toBe("");
  });

  it("formats a number typed into a number column or a cell with a number format, and leaves a text alone", () => {
    const doc = twoDoc();
    const money = readLook({ numberFormat: "THOUSANDS_TWO_DECIMALS" });

    expect(displayOf(doc, "r1", "c2", "body", { value: "1234.5", kind: "input" }, money, undefined, false)).toMatchObject({ text: "1,234.50", number: 1234.5 });
    expect(displayOf(doc, "r1", "c2", "body", { value: "12", kind: "input" }, none, undefined, false)).toMatchObject({ text: "12", number: 12 });
    // a text column with the general format keeps its digits as text: an identifier is not a number
    expect(displayOf(doc, "r1", "c1", "body", { value: "007", kind: "fixed" }, none, undefined, false)).toMatchObject({ text: "007", number: null });
    // a date is text, whatever its format key says
    expect(displayOf(doc, "r1", "c1", "body", { value: "2025-12-01", kind: "fixed" }, readLook({ numberFormat: "SHORT_DATE" }), undefined, false).text).toBe("2025-12-01");
  });

  it("shows a quiet placeholder with the count in fixed words for an attachment cell", () => {
    const doc = twoDoc();

    expect(displayOf(doc, "r1", "c5", "body", undefined, none, undefined, false)).toMatchObject({ text: "(no attachments yet)", quiet: true, attachment: true });
    // the fill-in's grid draws the count of the files in the cell (SH5.1): shown as it is, not as the placeholder
    expect(displayOf(doc, "r1", "c5", "body", { value: "(2 attachments)", kind: "input" }, none, undefined, false)).toMatchObject({
      text: "(2 attachments)",
      quiet: false,
      attachment: true,
    });
    expect(attachmentWords(0)).toBe("(no attachments yet)");
    expect(attachmentWords(1)).toBe("(1 attachment)");
    expect(attachmentWords(3)).toBe("(3 attachments)");
  });

  it("gives the list of a yes/no or picklist input cell to the author's grid only, five values at most", () => {
    const doc = twoDoc();

    expect(displayOf(doc, "r1", "c3", "body", undefined, none, undefined, true).list).toBe("List: Yes, No");
    expect(displayOf(doc, "r1", "c3", "body", undefined, none, undefined, false).list).toBeNull();
    expect(displayOf(doc, "r1", "c4", "body", undefined, none, undefined, true).list).toBe("List: A, B, C, D, E, and more");
    expect(displayOf(doc, "r1", "c1", "body", undefined, none, undefined, true).list).toBeNull();
  });

  it("knows the type of an input cell from its own statement, else its column, and none for a fixed cell", () => {
    const doc = twoDoc();

    expect(typeOf(doc, "r1", "c5", "body", undefined)).toBe("attachment");
    expect(typeOf(doc, "r1", "c2", "body", undefined)).toBe("number");
    expect(typeOf(doc, "r1", "c3", "body", undefined)).toBe("boolean");
    expect(typeOf(doc, "r1", "c1", "body", { value: "Pump", kind: "fixed" })).toBeNull();
    expect(typeOf(doc, "h1", "c1", "header", undefined)).toBeNull();
    expect(typeOf(doc, "blank0", "c2", "blank", undefined)).toBe("number");
  });
});

describe("what the screen and the print view share", () => {
  it("draws a sheet from its look only in format version 2", () => {
    expect(isLooked(twoDoc())).toBe(true);
    expect(isLooked({ ...twoDoc(), formatVersion: 1, sheet: undefined })).toBe(false);
  });

  it("gives an input cell that states no look the thin lines the file out draws, and a fixed cell none", () => {
    const doc = twoDoc();
    const rows = [
      { id: "h1", zone: "header" as const, height: 24 },
      { id: "r1", zone: "body" as const, height: 24 },
    ];
    const source = lookSource(doc, rows);

    expect(source.look(1, 1)).toBe(INPUT_LINES);
    expect(source.look(1, 0)).toBeUndefined();
    expect(source.look(0, 1)).toBeUndefined();
  });

  it("lays a calculated column's answer out at every blank row", () => {
    const rows = [
      { id: "h1", zone: "header" as const, height: 24 },
      { id: "blank0", zone: "blank" as const, height: 24 },
      { id: "blank1", zone: "blank" as const, height: 24 },
    ];
    const maps = answerMaps({ cells: { "h1:c1": "x" }, columns: { c2: "7" }, kinds: { cells: { "h1:c1": "text" }, columns: { c2: "number" } } }, rows);

    expect(maps.text).toEqual({ "h1:c1": "x", "blank0:c2": "7", "blank1:c2": "7" });
    expect(maps.kinds["blank1:c2"]).toBe("number");
  });
});
