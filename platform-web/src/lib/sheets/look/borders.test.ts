import { describe, expect, it } from "vitest";
import type { MergeRect } from "../geometry";
import { edgesOf, stronger, type BorderSource } from "./borders";
import { readLook, type ResolvedLook } from "./look";

/** A sheet of `rows` by `columns` where the cells listed state borders (by "r:c" in display positions). */
function sheet(rows: number, columns: number, stated: Record<string, Record<string, string>>): BorderSource {
  return {
    rows,
    columns,
    look: (r, c): ResolvedLook | undefined => (stated[`${r}:${c}`] ? readLook({ borders: stated[`${r}:${c}`] }) : undefined),
  };
}

// Which border a cell draws on each side (SH4): two neighbours share one line, a merged area draws its own edge.
describe("the borders a cell draws", () => {
  it("draws a line once when both neighbours state it: the left cell draws its right edge, the right cell none of its left", () => {
    const source = sheet(1, 2, { "0:0": { right: "THIN" }, "0:1": { left: "THIN" } });

    expect(edgesOf(source, 0, 0)).toEqual({ right: "THIN" });
    expect(edgesOf(source, 0, 1)).toEqual({});
  });

  it("draws the left edge of the first column and the top edge of the first row, and nothing of an inner top or left", () => {
    const source = sheet(2, 2, { "0:0": { left: "MEDIUM", top: "THICK" }, "1:1": { left: "THIN", top: "THIN" } });

    expect(edgesOf(source, 0, 0)).toEqual({ left: "MEDIUM", top: "THICK" });
    // the inner top and left of (1,1) are drawn by the cells above and to the left of it
    expect(edgesOf(source, 1, 1)).toEqual({});
    expect(edgesOf(source, 0, 1)).toEqual({ bottom: "THIN" });
    expect(edgesOf(source, 1, 0)).toEqual({ right: "THIN" });
  });

  it("lets the stronger of two statements win for one line", () => {
    const source = sheet(1, 2, { "0:0": { right: "THIN" }, "0:1": { left: "THICK" } });

    expect(edgesOf(source, 0, 0).right).toBe("THICK");
    expect(stronger("DOTTED", "THIN")).toBe("THIN");
    expect(stronger(undefined, "DASHED")).toBe("DASHED");
    expect(stronger("DOUBLE", "MEDIUM")).toBe("DOUBLE");
    expect(stronger(undefined, undefined)).toBeUndefined();
  });

  it("draws the edge of a merged area from the cells along that edge, covered ones included", () => {
    // the area covers rows 0..1 and columns 0..1; the covered cells carry the edge the area states
    const area: MergeRect = { key: "x", r0: 0, c0: 0, r1: 1, c1: 1 };
    const source = sheet(3, 3, {
      "0:1": { right: "MEDIUM", top: "THIN" },
      "1:1": { right: "MEDIUM", bottom: "THICK" },
      "1:0": { bottom: "THICK" },
      "0:0": { left: "THIN", top: "THIN" },
    });

    expect(edgesOf(source, 0, 0, area)).toEqual({ right: "MEDIUM", bottom: "THICK", top: "THIN", left: "THIN" });
  });

  it("takes the left statement of the cells just outside the area for its right edge", () => {
    const area: MergeRect = { key: "x", r0: 0, c0: 0, r1: 1, c1: 0 };
    const source = sheet(2, 2, { "1:1": { left: "DASHED" } });

    expect(edgesOf(source, 0, 0, area).right).toBe("DASHED");
  });

  it("never reaches outside the sheet", () => {
    const source = sheet(1, 1, { "0:0": { right: "THIN", bottom: "THIN" } });

    expect(edgesOf(source, 0, 0)).toEqual({ right: "THIN", bottom: "THIN" });
  });
});
