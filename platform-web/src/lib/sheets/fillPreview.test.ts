import { describe, expect, it } from "vitest";
import { reviewerSheet } from "@/test/reviewerFill";
import { fromApi } from "./adapter";
import { previewAnswers } from "./fillModel";
import type { FillDoc, FillValue } from "./fillTypes";
import { specOf } from "./formula/edit";
import { wireFromCases } from "./formula/caseFile";
import type { GridDoc } from "./types";

/**
 * The preview of the answers of a fill-in's formulas while a person types (SH5.1): the fill-in's cells laid over the sheet and
 * calculated by the browser's evaluator, with the server's table of functions. It reads a value the way the server does
 * (`FillCalculations.operand`) and has no rule of its own; the server's answers replace it after a save.
 */

const spec = specOf(wireFromCases());
const FILE = "11111111-1111-4111-8111-111111111111";

function filled(rows: Record<string, Record<string, FillValue>>): FillDoc {
  return { formatVersion: 3, sheetKey: "main", rows: Object.entries(rows).map(([id, cells]) => ({ id, cells })) };
}

/** Two reviewer cells for marks (text columns, as an uploaded form has them) and the total beneath them. */
function marks(): GridDoc {
  return fromApi(reviewerSheet());
}

describe("a plain number typed into a text cell", () => {
  it("is a number to the formula, as it is on the server", () => {
    const answers = previewAnswers(marks(), filled({ r1: { c2: "5" }, r2: { c2: "2.5" } }), spec);

    expect(answers?.text["r3:c2"]).toBe("7.5");
    expect(answers?.kinds["r3:c2"]).toBe("number");
  });

  it("with a sign, a fraction and spaces around it still counts", () => {
    expect(previewAnswers(marks(), filled({ r1: { c2: " +4 " }, r2: { c2: ".5" } }), spec)?.text["r3:c2"]).toBe("4.5");
    expect(previewAnswers(marks(), filled({ r1: { c2: "3." } }), spec)?.text["r3:c2"]).toBe("3");
  });

  it("is skipped by SUM when it is a word, a date or a very long run of digits", () => {
    expect(previewAnswers(marks(), filled({ r1: { c2: "5" }, r2: { c2: "n/a" } }), spec)?.text["r3:c2"]).toBe("5");
    expect(previewAnswers(marks(), filled({ r1: { c2: "2026-10-23" }, r2: { c2: "1" } }), spec)?.text["r3:c2"]).toBe("1");
    expect(previewAnswers(marks(), filled({ r1: { c2: "1".repeat(41) } }), spec)?.text["r3:c2"]).toBe("0");
  });
});

describe("an empty cell", () => {
  it("adds nothing: the answer of a sum over nothing is 0", () => {
    expect(previewAnswers(marks(), filled({}), spec)?.text["r3:c2"]).toBe("0");
  });

  it("is what a cell that was cleared is, and the other cells still count", () => {
    expect(previewAnswers(marks(), filled({ r1: {}, r2: { c2: "7" } }), spec)?.text["r3:c2"]).toBe("7");
  });
});

describe("the kinds of cells", () => {
  it("reads a number column as a number and a bad number as nothing", () => {
    const grid = marks();
    grid.columns = grid.columns.map((column) => (column.id === "c2" ? { ...column, type: "number" as const } : column));

    expect(previewAnswers(grid, filled({ r1: { c2: "12.5" }, r2: { c2: "-2" } }), spec)?.text["r3:c2"]).toBe("10.5");
    expect(previewAnswers(grid, filled({ r1: { c2: "12.5" }, r2: { c2: "abc" } }), spec)?.text["r3:c2"]).toBe("12.5");
  });

  it("never reads the files of an attachment cell", () => {
    const grid = marks();
    grid.sheet = { ...grid.sheet!, inputs: { ...grid.sheet!.inputs, "r2:c2": { class: "reviewer", type: "attachment" } } };

    expect(previewAnswers(grid, filled({ r1: { c2: "5" }, r2: { c2: [FILE] } }), spec)?.text["r3:c2"]).toBe("5");
  });

  it("reads the answer of a Yes/No cell with a reason and never its reason", () => {
    const grid = marks();
    grid.sheet = {
      ...grid.sheet!,
      cells: { ...grid.sheet!.cells, "r3:c2": { formula: "IF(@{c2,r1},10,0)" } },
      inputs: { ...grid.sheet!.inputs, "r1:c2": { class: "reviewer", type: "boolean_reason", prompt: "Fit?" } },
    };

    expect(previewAnswers(grid, filled({ r1: { c2: { answer: true, reason: "ok" } } }), spec)?.text["r3:c2"]).toBe("10");
    expect(previewAnswers(grid, filled({ r1: { c2: { answer: false, reason: "no" } } }), spec)?.text["r3:c2"]).toBe("0");
    expect(previewAnswers(grid, filled({ r1: { c2: { reason: "only a reason" } } }), spec)?.text["r3:c2"]).toBe("0");
  });

  it("reads a value that is a yes or a no as a boolean", () => {
    const grid = marks();
    grid.columns = grid.columns.map((column) => (column.id === "c2" ? { ...column, type: "boolean" as const } : column));
    grid.sheet = { ...grid.sheet!, cells: { ...grid.sheet!.cells, "r3:c2": { formula: "IF(@{c2,r1},10,0)" } } };

    expect(previewAnswers(grid, filled({ r1: { c2: true } }), spec)?.text["r3:c2"]).toBe("10");
    expect(previewAnswers(grid, filled({ r1: { c2: false } }), spec)?.text["r3:c2"]).toBe("0");
  });

  it("draws a text answer as it is, with the kind the server names", () => {
    const grid = marks();
    grid.sheet = { ...grid.sheet!, cells: { ...grid.sheet!.cells, "r3:c2": { formula: 'IF(@{c2,r1}>3,"High","Low")' } } };

    const answers = previewAnswers(grid, filled({ r1: { c2: "5" } }), spec);

    expect(answers?.text["r3:c2"]).toBe("High");
    expect(answers?.kinds["r3:c2"]).toBe("text");
  });
});

describe("a sheet where the filler adds the rows", () => {
  function growing(): GridDoc {
    return fromApi({
      formatVersion: 2,
      sheets: [
        {
          key: "main",
          dimension: 1,
          rowsMode: "REPEATABLE",
          columns: [
            { id: "c1", label: "Quantity", type: "number" },
            { id: "c2", label: "Double", type: "number", formula: "@{c1}*2" },
          ],
          headerRows: [{ id: "h1" }],
          rows: [],
          cells: { "h1:c1": { kind: "fixed", formula: "SUM(@{c1,*})" } },
          merges: [],
        },
      ],
    });
  }

  it("gives each row its own answer in a calculated column, and a header total over all the rows", () => {
    const answers = previewAnswers(growing(), filled({ x1: { c1: "2" }, x2: { c1: "4.5" }, x3: {} }), spec);

    expect(answers?.text["x1:c2"]).toBe("4");
    expect(answers?.text["x2:c2"]).toBe("9");
    expect(answers?.text["x3:c2"]).toBe("0");
    expect(answers?.kinds["x1:c2"]).toBe("number");
    expect(answers?.text["h1:c1"]).toBe("6.5");
  });

  it("has no answer for a row that does not exist, and a total of nothing before the first row", () => {
    const answers = previewAnswers(growing(), filled({}), spec);

    expect(answers?.text["h1:c1"]).toBe("0");
    expect(Object.keys(answers?.text ?? {})).toEqual(["h1:c1"]);
  });

  it("follows the rows in the order the server reads them", () => {
    const fill: FillDoc = { formatVersion: 3, sheetKey: "main", rows: [{ id: "b", cells: { c1: "1" } }, { id: "a", cells: { c1: "10" } }] };

    const answers = previewAnswers(growing(), fill, spec);

    expect(answers?.text["b:c2"]).toBe("2");
    expect(answers?.text["a:c2"]).toBe("20");
  });
});

describe("when there is nothing to show", () => {
  it("is nothing for a sheet with no formula", () => {
    const grid = marks();
    grid.sheet = { ...grid.sheet!, cells: {} };

    expect(previewAnswers(grid, filled({ r1: { c2: "5" } }), spec)).toBeNull();
  });

  it("is nothing without the server's table of functions", () => {
    expect(previewAnswers(marks(), filled({ r1: { c2: "5" } }), null)).toBeNull();
  });

  it("is nothing for a sheet in the first format", () => {
    const grid: GridDoc = { ...marks(), formatVersion: 1, sheet: undefined };

    expect(previewAnswers(grid, filled({ r1: { c2: "5" } }), spec)).toBeNull();
  });
});

describe("an input cell the sheet does not allow", () => {
  it("is ignored, as the server would refuse it", () => {
    // `r3:c2` holds a formula and `r1:c1` is fixed text: a value somebody put there counts for nothing.
    const answers = previewAnswers(marks(), filled({ r3: { c2: "100" }, r1: { c1: "9", c2: "5" } }), spec);

    expect(answers?.text["r3:c2"]).toBe("5");
  });
});
