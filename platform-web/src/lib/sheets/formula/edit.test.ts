import { describe, expect, it } from "vitest";
import { fromApi, toApi } from "../adapter";
import type { GridDoc } from "../types";
import { specOf, answers, barText, clearCellFormula, clearColumnFormula, columnBarText, setCellFormula, setColumnFormula } from "./edit";
import { wireFromCases } from "./caseFile";

/**
 * What the author's editor does with formulas (SH2, ADR-0252): a formula typed as spreadsheet text is kept beside its cell
 * as identifiers, so a column or a row added in front of it rewrites nothing and only the text drawn back changes; the
 * adapter carries it to the server and back; the browser shows its own answers while a person types, an error as its code.
 */

const spec = specOf(wireFromCases());

function named(): GridDoc {
  return fromApi({
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        dimension: 2,
        rowsMode: "FIXED",
        columns: [
          { id: "c1", label: "Quantity", type: "number" },
          { id: "c2", label: "Price", type: "number" },
        ],
        headerRows: [{ id: "h1" }],
        rows: [{ id: "r1" }, { id: "r2" }, { id: "r3" }],
        cells: { "r1:c1": { value: "2", kind: "fixed" }, "r2:c1": { value: "3", kind: "fixed" } },
        merges: [],
      },
    ],
  });
}

function repeating(): GridDoc {
  return fromApi({
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        dimension: 1,
        rowsMode: "REPEATABLE",
        columns: [
          { id: "c1", label: "Quantity", type: "number" },
          { id: "c2", label: "Price", type: "number" },
        ],
        headerRows: [{ id: "h1" }],
        rows: [],
        cells: {},
        merges: [],
      },
    ],
  });
}

function applied(result: ReturnType<typeof setCellFormula>): GridDoc {
  if (!result.ok) throw new Error(result.error);
  return result.doc;
}

describe("a formula typed in a cell", () => {
  it("is kept as identifiers and drawn back as the text that was typed", () => {
    const doc = applied(setCellFormula(named(), "r3", "c1", "=SUM(A2:A3)", spec));

    expect(doc.sheet?.cells["r3:c1"]?.formula).toBe("SUM(@{c1,r1}:@{c1,r2})");
    expect(barText(doc, "r3", "c1")).toBe("=SUM(A2:A3)");
    expect(doc.cells["r3:c1"]).toEqual({ kind: "fixed" });
  });

  it("is not rewritten when a column is added in front of it; only the drawn text changes", () => {
    const doc = applied(setCellFormula(named(), "r3", "c1", "=SUM(A2:A3)", spec));
    const widened = { ...doc, columns: [{ id: "c0", label: "New", type: "text" as const }, ...doc.columns] };

    expect(widened.sheet?.cells["r3:c1"]?.formula).toBe("SUM(@{c1,r1}:@{c1,r2})");
    expect(barText(widened, "r3", "c1")).toBe("=SUM(B2:B3)");
  });

  it("is refused in the server's words and changes nothing", () => {
    const doc = named();
    const refused = setCellFormula(doc, "r3", "c1", "=INDIRECT(A1)", spec);
    expect(refused).toEqual({ ok: false, error: "A formula uses a function that is not supported." });
    const other = setCellFormula(doc, "r3", "c1", "=Sheet2!A1", spec);
    expect(other.ok).toBe(false);
  });

  it("is not available where the server did not say what formulas may be", () => {
    const result = setCellFormula(named(), "r3", "c1", "=1", null);
    expect(result.ok).toBe(false);
  });

  it("is taken off with the cell left empty", () => {
    const doc = applied(setCellFormula(named(), "r3", "c1", "=1+1", spec));
    const cleared = clearCellFormula(doc, "r3", "c1");

    expect(cleared.sheet?.cells["r3:c1"]).toBeUndefined();
    expect(cleared.cells["r3:c1"]).toBeUndefined();
  });

  it("can live in a header cell without a cell of the grid", () => {
    const doc = applied(setCellFormula(named(), "h1", "c2", "=AVERAGE(A2:A3)", spec));

    expect(doc.sheet?.cells["h1:c2"]?.formula).toBe("AVERAGE(@{c1,r1}:@{c1,r2})");
    expect(doc.cells["h1:c2"]).toBeUndefined();
  });
});

describe("a formula in a column", () => {
  it("is the formula of a column drawn at the first row of the body", () => {
    const doc = applied(setColumnFormula(repeating(), "c2", '=IF(A2="","",A2*2)', spec));

    expect(doc.sheet?.columnFormulas).toEqual({ c2: 'IF(@{c1}="","",@{c1}*2)' });
    expect(columnBarText(doc, "c2")).toBe('=IF(A2="","",A2*2)');
    expect(clearColumnFormula(doc, "c2").sheet?.columnFormulas).toBeUndefined();
  });

  it("belongs to a sheet where the filler adds all the rows", () => {
    expect(setColumnFormula(named(), "c2", "=1", spec).ok).toBe(false);
  });

  it("can name the whole body of another column, which grows with the rows", () => {
    const doc = applied(setCellFormula(repeating(), "h1", "c2", "=AVERAGE(A2:A2)", spec));

    expect(doc.sheet?.cells["h1:c2"]?.formula).toBe("AVERAGE(@{c1,*})");
  });
});

describe("the adapter", () => {
  it("sends a formula to the server on its cell and on its column, and reads it back", () => {
    let doc = applied(setCellFormula(named(), "r3", "c1", "=SUM(A2:A3)", spec));
    doc = applied(setCellFormula(doc, "h1", "c2", "=1", spec));
    const sent = toApi(doc) as unknown as { sheets: { cells: Record<string, Record<string, unknown>> }[] };

    expect(sent.sheets[0]?.cells["r3:c1"]).toEqual({ formula: "SUM(@{c1,r1}:@{c1,r2})", kind: "fixed" });
    expect(sent.sheets[0]?.cells["h1:c2"]).toEqual({ formula: "1" });
    expect(fromApi(sent).sheet?.cells["r3:c1"]?.formula).toBe("SUM(@{c1,r1}:@{c1,r2})");

    const column = applied(setColumnFormula(repeating(), "c2", "=A2*2", spec));
    const columns = (toApi(column) as unknown as { sheets: { columns: Record<string, unknown>[] }[] }).sheets[0]?.columns;
    expect(columns?.[1]?.formula).toBe("@{c1}*2");
    expect(fromApi(toApi(column)).sheet?.columnFormulas).toEqual({ c2: "@{c1}*2" });
  });

  it("does not send a formula for a cell that now holds a text or for a column of a sheet that names its rows", () => {
    const doc = applied(setCellFormula(named(), "r3", "c1", "=1", spec));
    const typed = { ...doc, cells: { ...doc.cells, "r3:c1": { value: "text" } } };
    const sent = toApi(typed) as unknown as { sheets: { cells: Record<string, Record<string, unknown>> }[] };

    expect(sent.sheets[0]?.cells["r3:c1"]).toEqual({ value: "text" });
  });
});

describe("the answers the browser shows while a person types", () => {
  it("calculates a total from the fixed cells and shows an error as its code", () => {
    let doc = applied(setCellFormula(named(), "r3", "c1", "=SUM(A2:A3)", spec));
    doc = applied(setCellFormula(doc, "r3", "c2", "=1/0", spec));

    const shown = answers(doc, spec);

    expect(shown.cells["r3:c1"]).toBe("0");
    expect(shown.cells["r3:c2"]).toBe("#DIV/0!");
  });

  it("calculates a calculated column for one empty row", () => {
    const doc = applied(setColumnFormula(repeating(), "c2", '=IF(A2="","",A2*2)', spec));

    expect(answers(doc, spec).columns).toEqual({ c2: "" });
  });

  it("shows nothing where formulas are not available", () => {
    expect(answers(named(), null)).toEqual({ cells: {}, columns: {} });
  });

  it("does not loop on a cell that depends on itself", () => {
    const doc = applied(setCellFormula(named(), "r3", "c1", "=A4", spec));

    expect(answers(doc, spec).cells["r3:c1"]).toBe("#REF!");
  });
});
