import { describe, expect, it } from "vitest";
import { sampleDoc } from "./fixtures";
import { explainPlace } from "./hints";

describe("explainPlace", () => {
  it("names a place by position, the way a person counts", () => {
    const doc = sampleDoc();

    expect(explainPlace(doc, "grid")).toBe("The grid");
    expect(explainPlace(doc, "grid.columns[2].label")).toBe("Column 3, label");
    expect(explainPlace(doc, "grid.columns[1].picklist.values[0]")).toBe("Column 2, picklist, values");
    expect(explainPlace(doc, "grid.rows[0]")).toBe("Row 1");
    expect(explainPlace(doc, "grid.headerRows[1]")).toBe("Header row 2");
    expect(explainPlace(doc, "grid.merges[0]")).toBe("Merged area 1");
    expect(explainPlace(doc, "grid.cells.r2:c3")).toBe("Row 2, column 3");
    expect(explainPlace(doc, "grid.cells.h1:c1.value")).toBe("Header row 1, column 1");
    expect(explainPlace(doc, "grid.cells.nope:c1")).toBe("A cell");
    expect(explainPlace(doc, "grid.…")).toBe("…");
  });
});
