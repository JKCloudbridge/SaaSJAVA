import { describe, expect, it } from "vitest";
import type { MergeRect } from "../geometry";
import { effectiveFreeze, NO_FREEZE, readFreeze } from "./freeze";

const space = (rows: number, columns: number, rowHeight = 24, columnWidth = 120) => ({
  rowHeights: Array.from({ length: rows }, () => rowHeight),
  columnWidths: Array.from({ length: columns }, () => columnWidth),
  viewHeight: 400,
  viewWidth: 800,
});

// The rows and columns kept in view (SH4): what the format states, less what would cut a merged area or leave no room.
describe("the freeze that is drawn", () => {
  it("reads the stored freeze exactly: whole numbers from zero, anything else is zero", () => {
    expect(readFreeze({ rows: 2, columns: 1 })).toEqual({ rows: 2, columns: 1 });
    expect(readFreeze({ rows: -1, columns: 1.5 })).toEqual({ rows: 0, columns: 0 });
    expect(readFreeze({ rows: "2" })).toEqual({ rows: 0, columns: 0 });
    expect(readFreeze(undefined)).toBe(NO_FREEZE);
  });

  it("is what is stated when nothing is in the way", () => {
    expect(effectiveFreeze({ rows: 3, columns: 2 }, [], space(10, 6))).toEqual({ rows: 3, columns: 2 });
  });

  it("moves the line up past a merged area that it would cut, and left past one in the columns", () => {
    const tall: MergeRect = { key: "a", r0: 1, c0: 4, r1: 3, c1: 4 };
    const wide: MergeRect = { key: "b", r0: 6, c0: 0, r1: 6, c1: 2 };

    // the tall area covers rows 1 to 3: a line after 2 or 3 rows would cut it, a line after 4 rows would not
    expect(effectiveFreeze({ rows: 3, columns: 0 }, [tall], space(10, 6))).toEqual({ rows: 1, columns: 0 });
    expect(effectiveFreeze({ rows: 4, columns: 0 }, [tall], space(10, 6))).toEqual({ rows: 4, columns: 0 });
    expect(effectiveFreeze({ rows: 0, columns: 2 }, [wide], space(10, 6))).toEqual({ rows: 0, columns: 0 });
    expect(effectiveFreeze({ rows: 0, columns: 3 }, [wide], space(10, 6))).toEqual({ rows: 0, columns: 3 });
  });

  it("never takes more than three fifths of the view, so there is room to scroll", () => {
    // 10 rows of 60 pixels are 600; the view is 400 high, so at most 240 pixels: 4 rows
    expect(effectiveFreeze({ rows: 10, columns: 0 }, [], space(20, 4, 60))).toEqual({ rows: 4, columns: 0 });
    expect(effectiveFreeze({ rows: 0, columns: 5 }, [], space(20, 8, 24, 200))).toEqual({ rows: 0, columns: 2 });
  });

  it("never freezes the whole sheet", () => {
    expect(effectiveFreeze({ rows: 5, columns: 5 }, [], space(3, 2))).toEqual({ rows: 2, columns: 1 });
  });
});
