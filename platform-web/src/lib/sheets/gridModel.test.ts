import { describe, expect, it } from "vitest";
import { at, cellKey, cellKind, displayRows, indexesOf, mergeRects, normalized } from "./geometry";
import {
  addColumn,
  addRow,
  changeableKeys,
  clearCells,
  mergeRect,
  moveColumn,
  moveRow,
  parseClipboard,
  pasteBlock,
  removeColumn,
  removeRow,
  selectionState,
  setCellKind,
  setCellText,
  setFontSize,
  setShape,
  STYLE_NEEDS_TEXT,
  tidyCell,
  toggleStyle,
  unmergeAt,
  updateColumn,
  updateRow,
} from "./gridModel";
import { LIMITS, oneDimension, sampleDoc } from "./fixtures";
import type { GridDoc, OpResult } from "./types";

function doc(result: OpResult): GridDoc {
  if (!result.ok) {
    throw new Error(`Refused: ${result.error}`);
  }
  return result.doc;
}

function refusal(result: OpResult): string {
  if (result.ok) {
    throw new Error("Expected a refusal");
  }
  return result.error;
}

const rowsOf = (d: GridDoc) => displayRows(d);

describe("geometry", () => {
  it("draws header rows, then named rows, then blank sample rows when the filler adds the rows", () => {
    expect(rowsOf(sampleDoc()).map((r) => r.zone)).toEqual(["header", "header", "body", "body", "body"]);
    expect(rowsOf(oneDimension()).map((r) => r.zone)).toEqual(["header", "header", "blank", "blank", "blank", "blank", "blank"]);
  });

  it("says a header cell is always fixed and a body cell is fixed only with text or by saying so", () => {
    expect(cellKind("header", undefined)).toBe("fixed");
    expect(cellKind("body", undefined)).toBe("input");
    expect(cellKind("body", { value: "x" })).toBe("fixed");
    expect(cellKind("body", { kind: "fixed" })).toBe("fixed");
    expect(cellKind("body", { value: "5", kind: "input" })).toBe("input");
    expect(cellKind("blank", { value: "x" })).toBe("input");
  });

  it("normalizes a selection dragged backwards", () => {
    expect(normalized({ ar: 3, ac: 2, fr: 1, fc: 0 })).toEqual({ r0: 1, r1: 3, c0: 0, c1: 2 });
  });
});

describe("tidyCell", () => {
  it("gives a cell one form", () => {
    expect(tidyCell({ value: "x", kind: "fixed", style: { bold: false, fontSize: "NORMAL" } }, false)).toEqual({ value: "x" });
    expect(tidyCell({ value: "" }, false)).toBeNull();
    expect(tidyCell({ kind: "fixed" }, false)).toEqual({ kind: "fixed" });
    expect(tidyCell({ style: { bold: true } }, false)).toBeNull();
    expect(tidyCell({ style: { bold: true } }, true)).toEqual({ style: { bold: true } });
    expect(tidyCell({ value: "5", kind: "input", style: { italic: true } }, false)).toEqual({ value: "5", kind: "input" });
    expect(tidyCell({ value: "x", kind: "input" }, true)).toEqual({ value: "x" });
  });
});

describe("typing into a cell", () => {
  it("writes fixed text", () => {
    const next = doc(setCellText(sampleDoc(), "r1", "c2", "Limit 5", LIMITS));

    expect(next.cells["r1:c2"]).toEqual({ value: "Limit 5" });
    expect(cellKind("body", next.cells["r1:c2"])).toBe("fixed");
  });

  it("makes a blank input cell again when the text is cleared, and keeps a styled cell as an empty fixed one", () => {
    const cleared = doc(setCellText(sampleDoc(), "r1", "c1", "", LIMITS));
    const styled = doc(setCellText(sampleDoc(), "r2", "c1", "", LIMITS));

    expect(cleared.cells["r1:c1"]).toBeUndefined();
    expect(styled.cells["r2:c1"]).toEqual({ kind: "fixed", style: { italic: true } });
  });

  it("refuses markup, a control character and a text over the limit, in words, and changes nothing", () => {
    const start = sampleDoc();
    expect(refusal(setCellText(start, "r1", "c1", "<b>x</b>", LIMITS))).toMatch(/markup/);
    expect(refusal(setCellText(start, "r1", "c1", "a\u0000b", LIMITS))).toMatch(/control character/);
    expect(refusal(setCellText(start, "r1", "c1", "x".repeat(LIMITS.maxCellChars + 1), LIMITS))).toMatch(/1000/);
    expect(setCellText(start, "r1", "c1", "5 < 6", LIMITS).ok).toBe(true);
  });

  it("refuses a blank sample row, an unknown column and a cell covered by a merged area", () => {
    const one = oneDimension();
    expect(refusal(setCellText(one, "blank0", "c1", "x", LIMITS))).toMatch(/blank rows/);
    expect(refusal(setCellText(sampleDoc(), "r1", "nope", "x", LIMITS))).toMatch(/column does not exist/);
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 2, c0: 0, r1: 2, c1: 1 }, LIMITS));
    expect(refusal(setCellText(merged, "r1", "c2", "x", LIMITS))).toMatch(/covered/);
  });

  it("shares what it did not touch", () => {
    const start = sampleDoc();
    const next = doc(setCellText(start, "r1", "c2", "x", LIMITS));

    expect(next.columns).toBe(start.columns);
    expect(next.rows).toBe(start.rows);
    expect(start.cells["r1:c2"]).toBeUndefined();
  });

  it("clears the text of a range and leaves the styles that need a fixed cell", () => {
    const start = sampleDoc();
    const next = doc(clearCells(start, rowsOf(start), { r0: 0, c0: 0, r1: 4, c1: 2 }));

    expect(next.cells["r1:c1"]).toBeUndefined();
    expect(next.cells["r2:c1"]).toEqual({ kind: "fixed", style: { italic: true } });
    expect(next.cells["h1:c1"]).toEqual({ style: { bold: true, fontSize: "TITLE" } });
  });
});

describe("styles", () => {
  it("turns bold on for fixed cells and off again when all of them have it", () => {
    const start = sampleDoc();
    const keys = ["r1:c1", "r2:c1"];
    const on = doc(toggleStyle(start, keys, "bold"));
    const off = doc(toggleStyle(on, keys, "bold"));

    expect(on.cells["r1:c1"]?.style?.bold).toBe(true);
    expect(on.cells["r2:c1"]?.style).toEqual({ italic: true, bold: true });
    expect(off.cells["r1:c1"]).toEqual({ value: "Fire exit" });
  });

  it("sets one of the four sizes, and the normal size is nothing", () => {
    const large = doc(setFontSize(sampleDoc(), ["r1:c1"], "LARGE"));
    const normal = doc(setFontSize(large, ["r1:c1"], "NORMAL"));

    expect(large.cells["r1:c1"]?.style).toEqual({ fontSize: "LARGE" });
    expect(normal.cells["r1:c1"]).toEqual({ value: "Fire exit" });
  });

  it("styles an empty header cell, which is fixed", () => {
    const next = doc(toggleStyle(sampleDoc(), ["h2:c2"], "italic"));

    expect(next.cells["h2:c2"]).toEqual({ style: { italic: true } });
  });

  it("refuses a style on an input cell and says to type the text first", () => {
    expect(refusal(toggleStyle(sampleDoc(), ["r1:c2"], "bold"))).toBe(STYLE_NEEDS_TEXT);
    expect(refusal(setFontSize(sampleDoc(), ["r1:c1", "r1:c2"], "LARGE"))).toBe(STYLE_NEEDS_TEXT);
    expect(refusal(toggleStyle(sampleDoc(), [], "bold"))).toMatch(/Select a cell/);
  });
});

describe("fixed text and input cells", () => {
  it("makes a fixed cell an input cell, which holds no text and no style", () => {
    const next = doc(setCellKind(sampleDoc(), "r2", "c1", "input"));

    expect(next.cells["r2:c1"]).toBeUndefined();
  });

  it("makes an input cell an empty fixed cell", () => {
    const next = doc(setCellKind(sampleDoc(), "r1", "c2", "fixed"));

    expect(next.cells["r1:c2"]).toEqual({ kind: "fixed" });
  });

  it("refuses to make a header cell an input cell", () => {
    expect(refusal(setCellKind(sampleDoc(), "h1", "c1", "input"))).toMatch(/header cell is always fixed/);
  });
});

describe("merging", () => {
  const start = sampleDoc();
  const rows = rowsOf(start);

  it("merges a rectangle and keeps only the top left cell's content", () => {
    const withText = doc(setCellText(start, "r1", "c2", "dropped", LIMITS));
    const result = mergeRect(withText, rows, { r0: 2, c0: 0, r1: 3, c1: 1 }, LIMITS);
    const merged = doc(result);

    expect(merged.merges).toEqual([{ from: "r1:c1", to: "r2:c2" }]);
    expect(merged.cells["r1:c2"]).toBeUndefined();
    expect(merged.cells["r1:c1"]).toEqual({ value: "Fire exit" });
    expect(result.ok && result.note).toMatch(/top left/);
  });

  it("refuses one cell, header with body, blank rows, an overlap and too many merges", () => {
    expect(refusal(mergeRect(start, rows, { r0: 2, c0: 0, r1: 2, c1: 0 }, LIMITS))).toMatch(/more than one/);
    expect(refusal(mergeRect(start, rows, { r0: 1, c0: 0, r1: 2, c1: 1 }, LIMITS))).toMatch(/header rows and body rows/);
    const one = oneDimension();
    expect(refusal(mergeRect(one, rowsOf(one), { r0: 2, c0: 0, r1: 3, c1: 1 }, LIMITS))).toMatch(/blank rows/);
    const merged = doc(mergeRect(start, rows, { r0: 0, c0: 0, r1: 0, c1: 1 }, LIMITS));
    expect(refusal(mergeRect(merged, rows, { r0: 0, c0: 1, r1: 1, c1: 2 }, LIMITS))).toMatch(/same cell/);
    expect(refusal(mergeRect(start, rows, { r0: 2, c0: 0, r1: 2, c1: 1 }, { ...LIMITS, maxMerges: 0 }))).toMatch(/at most 0/);
  });

  it("unmerges the area that holds a cell", () => {
    const merged = doc(mergeRect(start, rows, { r0: 0, c0: 0, r1: 0, c1: 1 }, LIMITS));

    expect(doc(unmergeAt(merged, rows, 0, 1)).merges).toEqual([]);
    expect(refusal(unmergeAt(start, rows, 0, 0))).toMatch(/no merged area/);
  });

  it("lists the cells of a selection that can be changed, never a covered one", () => {
    const merged = doc(mergeRect(start, rows, { r0: 2, c0: 0, r1: 3, c1: 1 }, LIMITS));

    expect(changeableKeys(merged, rows, { r0: 2, c0: 0, r1: 3, c1: 1 })).toEqual(["r1:c1"]);
  });

  it("describes the selection for the toolbar", () => {
    const state = selectionState(start, rows, { r0: 2, c0: 0, r1: 3, c1: 0 });

    expect(state).toMatchObject({ bold: false, italic: false, size: "NORMAL", kind: "fixed", canMerge: true, canUnmerge: false });
    expect(selectionState(start, rows, { r0: 2, c0: 0, r1: 2, c1: 1 }).kind).toBe("mixed");
    expect(selectionState(start, rows, { r0: 2, c0: 0, r1: 2, c1: 0 }).canMerge).toBe(false);
  });
});

describe("paste", () => {
  it("reads a block copied from a spreadsheet application", () => {
    expect(parseClipboard("a\tb\r\nc\td\r\n")).toEqual([
      ["a", "b"],
      ["c", "d"],
    ]);
    expect(parseClipboard("x")).toEqual([["x"]]);
  });

  it("writes the block as fixed text from the cell and counts what it skipped", () => {
    const start = sampleDoc();
    const rows = rowsOf(start);
    const result = pasteBlock(start, rows, 3, 1, [["a", "<b>", "c", "d"], ["e", "f", "g", "h"]], LIMITS);

    expect(result.doc.cells["r2:c2"]).toEqual({ value: "a" });
    expect(result.doc.cells["r2:c3"]).toBeUndefined();
    expect(result.doc.cells["r3:c2"]).toEqual({ value: "e" });
    expect(result.doc.cells["r3:c3"]).toEqual({ value: "f" });
    expect(result.applied).toBe(3);
    expect(result.skipped).toBe(5);
    expect(result.problems).toEqual(["A text contains markup or a control character."]);
  });

  it("skips covered cells and blank sample rows, and a pasted empty text clears", () => {
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 2, c0: 0, r1: 2, c1: 1 }, LIMITS));
    const result = pasteBlock(merged, rowsOf(merged), 2, 0, [["new", "covered"]], LIMITS);

    expect(result.doc.cells["r1:c1"]).toEqual({ value: "new" });
    expect(result.doc.cells["r1:c2"]).toBeUndefined();
    expect(result.skipped).toBe(1);
    const one = oneDimension();
    expect(pasteBlock(one, rowsOf(one), 2, 0, [["x"]], LIMITS).applied).toBe(0);
    const cleared = pasteBlock(sampleDoc(), rowsOf(sampleDoc()), 2, 0, [[""]], LIMITS);
    expect(cleared.doc.cells["r1:c1"]).toBeUndefined();
  });
});

describe("columns", () => {
  it("adds a text column after another and at the end, with an identifier nobody typed", () => {
    const after = doc(addColumn(sampleDoc(), "c1", LIMITS));
    const end = doc(addColumn(sampleDoc(), null, LIMITS));

    expect(after.columns.map((c) => c.id).slice(0, 1)).toEqual(["c1"]);
    expect(after.columns[1]?.type).toBe("text");
    expect(after.columns[1]?.id).toMatch(/^c[a-z0-9]{10}$/);
    expect(end.columns[3]?.label).toBe("Column 4");
  });

  it("refuses more columns than allowed", () => {
    expect(refusal(addColumn(sampleDoc(), null, { ...LIMITS, maxColumns: 3 }))).toMatch(/at most 3 columns/);
  });

  it("removes a column with its cells, but not the last one and not one inside a merged area", () => {
    const withCell = doc(setCellText(sampleDoc(), "r1", "c2", "x", LIMITS));
    const removed = doc(removeColumn(withCell, "c2"));

    expect(removed.columns.map((c) => c.id)).toEqual(["c1", "c3"]);
    expect(removed.cells["r1:c2"]).toBeUndefined();
    expect(refusal(removeColumn({ ...sampleDoc(), columns: [at(sampleDoc().columns, 0)] }, "c1"))).toMatch(/at least one/);
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 0, c0: 0, r1: 0, c1: 1 }, LIMITS));
    expect(refusal(removeColumn(merged, "c2"))).toMatch(/Unmerge/);
    expect(refusal(removeColumn(sampleDoc(), "nope"))).toMatch(/does not exist/);
  });

  it("moves a column and refuses to move one past the edge or across a merged area", () => {
    expect(doc(moveColumn(sampleDoc(), "c1", 1)).columns.map((c) => c.id)).toEqual(["c2", "c1", "c3"]);
    expect(refusal(moveColumn(sampleDoc(), "c1", -1))).toMatch(/further/);
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 0, c0: 0, r1: 0, c1: 1 }, LIMITS));
    expect(refusal(moveColumn(merged, "c3", -1))).toMatch(/Unmerge/);
  });

  it("changes a label and refuses markup and a long label", () => {
    expect(doc(updateColumn(sampleDoc(), "c1", { label: "Thing" }, LIMITS)).columns[0]?.label).toBe("Thing");
    expect(refusal(updateColumn(sampleDoc(), "c1", { label: "<i>" }, LIMITS))).toMatch(/markup/);
    expect(refusal(updateColumn(sampleDoc(), "c1", { label: "x".repeat(201) }, LIMITS))).toMatch(/200/);
  });

  it("drops the default and the list that no longer fit a new type", () => {
    const withDefault = doc(updateColumn(sampleDoc(), "c2", { default: "12" }, LIMITS));
    const asText = doc(updateColumn(withDefault, "c2", { type: "boolean" }, LIMITS));
    const asList = doc(updateColumn(sampleDoc(), "c1", { type: "picklist", picklist: { values: ["a"] } }, LIMITS));
    const back = doc(updateColumn(sampleDoc(), "c3", { type: "text" }, LIMITS));

    expect(withDefault.columns[1]?.default).toBe("12");
    expect(asText.columns[1]?.default).toBeUndefined();
    expect(asList.columns[0]?.picklist).toEqual({ values: ["a"] });
    expect(back.columns[2]?.picklist).toBeUndefined();
  });

  it("does not keep a default that does not fit and clears pre-filled cells that no longer fit", () => {
    const filled = { ...sampleDoc(), cells: { ...sampleDoc().cells, "r1:c2": { value: "12.5", kind: "input" as const } } };
    const result = updateColumn(filled, "c2", { type: "date" }, LIMITS);

    expect(result.ok && result.doc.cells["r1:c2"]).toBeUndefined();
    expect(result.ok && result.note).toMatch(/1 pre-filled cell no longer fit/);
    expect(doc(updateColumn(sampleDoc(), "c2", { default: "not a number" }, LIMITS)).columns[1]?.default).toBeUndefined();
  });

  it("refuses a list with more values than allowed", () => {
    const values = Array.from({ length: 101 }, (_, i) => `v${i}`);
    expect(refusal(updateColumn(sampleDoc(), "c3", { picklist: { values } }, LIMITS))).toMatch(/at most 100/);
  });
});

describe("rows", () => {
  it("adds a named row and a header row, each with an identifier of its own kind", () => {
    const named = doc(addRow(sampleDoc(), "body", "r1", LIMITS));
    const header = doc(addRow(sampleDoc(), "header", null, LIMITS));

    expect(named.rows.map((r) => r.id).slice(0, 1)).toEqual(["r1"]);
    expect(named.rows[1]?.id).toMatch(/^r[a-z0-9]{10}$/);
    expect(named.rows[1]?.label).toBe("Row 4");
    expect(header.headerRows[2]?.id).toMatch(/^h[a-z0-9]{10}$/);
  });

  it("refuses a named row where the filler adds the rows, and more rows than allowed", () => {
    expect(refusal(addRow(oneDimension(), "body", null, LIMITS))).toMatch(/filler adds the rows/);
    expect(refusal(addRow(sampleDoc(), "body", null, { ...LIMITS, maxRows: 3 }))).toMatch(/at most 3 rows/);
    expect(refusal(addRow(sampleDoc(), "header", null, { ...LIMITS, maxHeaderRows: 2 }))).toMatch(/at most 2 header/);
  });

  it("removes a row with its cells and refuses one inside a merged area", () => {
    const removed = doc(removeRow(sampleDoc(), "r1"));

    expect(removed.rows.map((r) => r.id)).toEqual(["r2", "r3"]);
    expect(removed.cells["r1:c1"]).toBeUndefined();
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 2, c0: 0, r1: 3, c1: 0 }, LIMITS));
    expect(refusal(removeRow(merged, "r2"))).toMatch(/Unmerge/);
    expect(refusal(removeRow(sampleDoc(), "nope"))).toMatch(/does not exist/);
  });

  it("moves a row within its zone", () => {
    expect(doc(moveRow(sampleDoc(), "r1", 1)).rows.map((r) => r.id)).toEqual(["r2", "r1", "r3"]);
    expect(refusal(moveRow(sampleDoc(), "r1", -1))).toMatch(/further/);
    expect(doc(moveRow(sampleDoc(), "h1", 1)).headerRows.map((r) => r.id)).toEqual(["h2", "h1"]);
  });

  it("changes the label and the height, and an empty label is nothing", () => {
    const labelled = doc(updateRow(sampleDoc(), "r1", { label: "Primary" }, LIMITS));
    const tall = doc(updateRow(labelled, "r1", { height: 60 }, LIMITS));
    const unlabelled = doc(updateRow(tall, "r1", { label: "" }, LIMITS));

    expect(tall.rows[0]).toEqual({ id: "r1", label: "Primary", height: 60 });
    expect(unlabelled.rows[0]).toEqual({ id: "r1", height: 60 });
    expect(refusal(updateRow(sampleDoc(), "r1", { label: "<b>" }, LIMITS))).toMatch(/markup/);
  });
});

describe("the shape of the sheet", () => {
  it("goes to one dimension only when no row is named, and back to two", () => {
    expect(refusal(setShape(sampleDoc(), 1))).toMatch(/Remove the named rows/);
    const one = doc(setShape({ ...sampleDoc(), rows: [] }, 1));
    expect(one).toMatchObject({ dimension: 1, rowsMode: "REPEATABLE" });
    expect(doc(setShape(one, 2))).toMatchObject({ dimension: 2, rowsMode: "FIXED" });
    expect(doc(setShape(one, 2, "FIXED_AND_REPEATABLE")).rowsMode).toBe("FIXED_AND_REPEATABLE");
  });
});

describe("rectangles of merged areas", () => {
  it("are read in display positions", () => {
    const merged = doc(mergeRect(sampleDoc(), rowsOf(sampleDoc()), { r0: 2, c0: 0, r1: 3, c1: 1 }, LIMITS));
    const indexes = indexesOf(merged, rowsOf(merged));

    expect(mergeRects(merged, indexes)).toEqual([{ key: cellKey("r1", "c1"), r0: 2, c0: 0, r1: 3, c1: 1 }]);
  });
});
