import { describe, expect, it } from "vitest";
import { fromApi, toApi } from "./adapter";
import { canMarkScore, isScore, scoreCell, setScore } from "./score";

/**
 * The score marker (MC2B, ADR-0261): one formula cell the template marks, kept in the sheet's own property, round-tripped
 * by the adapter and dropped as soon as its cell no longer holds a formula (the server would refuse any other).
 */

function document(score?: string): Record<string, unknown> {
  return {
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        ...(score ? { score } : {}),
        dimension: 2,
        rowsMode: "FIXED",
        columns: [{ id: "c1", label: "Item", type: "number" }],
        headerRows: [{ id: "h1" }],
        rows: [{ id: "r1" }, { id: "r2" }],
        cells: {
          "h1:c1": { value: "Item", kind: "fixed" },
          "r2:c1": { formula: "@{c1,r1}", kind: "fixed" },
        },
        merges: [],
      },
    ],
  };
}

describe("the score marker", () => {
  it("is read from the sheet and sent back as it was", () => {
    const doc = fromApi(document("r2:c1"));
    expect(scoreCell(doc)).toBe("r2:c1");
    expect(isScore(doc, "r2", "c1")).toBe(true);
    const sent = toApi(doc) as unknown as { sheets: Record<string, unknown>[] };
    expect(sent.sheets[0]?.score).toBe("r2:c1");
  });

  it("can mark only a cell that holds a formula, and a sheet has one mark", () => {
    const doc = fromApi(document());
    expect(canMarkScore(doc, "r1", "c1")).toBe(false);
    expect(setScore(doc, "r1", "c1", true)).toMatchObject({ ok: false });
    const marked = setScore(doc, "r2", "c1", true);
    expect(marked.ok).toBe(true);
    if (marked.ok) {
      expect(scoreCell(marked.doc)).toBe("r2:c1");
      const unmarked = setScore(marked.doc, "r2", "c1", false);
      expect(unmarked.ok && scoreCell(unmarked.doc)).toBeUndefined();
    }
  });

  it("is not sent when its cell lost its formula or its row", () => {
    const doc = fromApi(document("r2:c1"));
    const withoutFormula = { ...doc, sheet: { ...doc.sheet!, cells: {} } };
    const sent = toApi(withoutFormula) as unknown as { sheets: Record<string, unknown>[] };
    expect(sent.sheets[0]?.score).toBeUndefined();
    const withoutRow = { ...doc, rows: (doc.rows ?? []).filter((row) => row.id !== "r2") };
    expect((toApi(withoutRow) as unknown as { sheets: Record<string, unknown>[] }).sheets[0]?.score).toBeUndefined();
  });
});
