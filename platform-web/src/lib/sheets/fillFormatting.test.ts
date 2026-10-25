import { expect, it } from "vitest";
import { displayDoc, formatFillCells, fromApiFill, reapplyFill, toApiFill } from "./fillModel";
import { sampleDoc } from "./fixtures";
import type { FillDoc } from "./fillTypes";

const limits = { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000 };

it("keeps v1 on a value-only round trip and upgrades only when formatting an input", () => {
  const grid = sampleDoc();
  const original: FillDoc = { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "1" } }] };
  expect(toApiFill(grid, fromApiFill(original))).toEqual(original);
  const result = formatFillCells(grid, original, ["r1"], ["c1", "c2"], { bold: true, italic: false }, limits);
  expect(result.ok).toBe(true);
  if (!result.ok) return;
  expect(result.fill.formatVersion).toBe(2);
  expect(result.fill.rows[0]?.styles).toEqual({ c2: { bold: true, italic: false } });
  expect(displayDoc(grid, result.fill).cells["r1:c2"]?.style).toEqual({ bold: true, italic: false });
  expect(displayDoc(grid, result.fill).cells["r1:c1"]).toEqual(grid.cells["r1:c1"]);
});

it("reapplies local values and styles while preserving an unrelated remote value", () => {
  const base: FillDoc = { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "1" } }] };
  const local: FillDoc = { formatVersion: 2, rows: [{ id: "r1", cells: { c2: "2" }, styles: { c2: { bold: true } } }] };
  const remote: FillDoc = { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "1", c3: "Pass" } }] };
  expect(reapplyFill(base, local, remote)).toEqual({ formatVersion: 2, rows: [
    { id: "r1", cells: { c2: "2", c3: "Pass" }, styles: { c2: { bold: true } } },
  ] });
});

it("reapplies extended styles and resets them while preserving a remote style", () => {
  const grid = sampleDoc();
  const base: FillDoc = { formatVersion: 2, rows: [{ id: "r1", cells: { c2: "1" },
    styles: { c2: { underline: true, alignment: "LEFT", colour: "ACCENT" } } }] };
  const local: FillDoc = { formatVersion: 2, rows: [{ id: "r1", cells: { c2: "1" },
    styles: { c2: { underline: false, alignment: "RIGHT", colour: "DEFAULT" } } }] };
  const remote: FillDoc = { formatVersion: 2, rows: [{ id: "r1", cells: { c2: "1" },
    styles: { c2: { ...base.rows[0]!.styles!.c2, italic: true } } }] };
  const merged = reapplyFill(base, local, remote, grid);
  expect(fromApiFill(toApiFill(grid, merged)).rows[0]?.styles?.c2)
    .toEqual({ underline: false, alignment: "RIGHT", colour: "DEFAULT", italic: true });
  const reset = formatFillCells(grid, merged, ["r1"], ["c2"], null, limits);
  expect(reset.ok).toBe(true);
  if (reset.ok) expect(displayDoc(grid, reset.fill).cells["r1:c2"]?.style).toBeUndefined();
});
