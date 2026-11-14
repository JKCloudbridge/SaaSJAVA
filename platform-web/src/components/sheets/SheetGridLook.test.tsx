import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";

sizeTheGrid();

/** A sheet in format version 2 built from the sample: the first sheet's extras are the look, the panes, the rules. */
function looked(extras: Partial<NonNullable<GridDoc["sheet"]>> = {}, change: Partial<GridDoc> = {}): GridDoc {
  return {
    ...sampleDoc(),
    formatVersion: 2,
    sheet: { key: "main", cells: {}, ...extras },
    ...change,
  };
}

const cellAt = (r: number, c: number) => document.querySelector(`[data-r="${r}"][data-c="${c}"]`) as HTMLElement;

// A sheet in format version 1 takes none of the look (SH4, threshold T10).
describe("a sheet in the earlier format", () => {
  it("is drawn with the classes it always had and no custom property", () => {
    render(<GridHost initial={sampleDoc()} />);
    const grid = screen.getByRole("grid");

    expect(grid.className).toBe("sheet-grid");
    expect(cellAt(1, 1).className).toBe("sheet-cell sheet-cell-fixed sheet-cell-header sheet-align-left sheet-colour-default");
    expect(cellAt(3, 1).className).toBe("sheet-cell sheet-cell-input sheet-align-left sheet-colour-default");
    expect(cellAt(0, 0).className).toContain("sheet-bold sheet-align-left sheet-colour-default sheet-size-title");
    for (const cell of screen.getAllByRole("gridcell")) {
      expect(cell.getAttribute("style") ?? "").not.toContain("--sheet");
      expect(cell.className).not.toMatch(/sheet-(has|ink|pt|font|v-|b[trbl]-|wrap|list|quiet)/);
    }
    expect(document.querySelector(".sheet-pane-left, .sheet-row-fixed")).toBeNull();
  });

  it("ignores a freeze, a look and conditions that only a version 2 document carries", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      sheet: { key: "main", cells: { "r1:c1": { look: { fill: "#ff0000" } } }, freeze: { rows: 2, columns: 1 } },
    };
    render(<GridHost initial={doc} />);

    expect(screen.getByRole("grid").className).toBe("sheet-grid");
    expect(cellAt(2, 0).getAttribute("style") ?? "").not.toContain("--sheet-fill");
    expect(document.querySelector(".sheet-row-fixed")).toBeNull();
  });
});

describe("a sheet in format version 2", () => {
  it("is drawn on paper with its stored look reaching the cell only as classes and custom properties", () => {
    const doc = looked({
      cells: {
        "r1:c1": { look: { font: "SERIF_TEXT", size: 14, fill: "#ABCDEF", colour: "#102030", wrap: true, verticalAlignment: "TOP", borders: { right: "THICK", bottom: "DOTTED" } } },
      },
    });
    render(<GridHost initial={doc} />);
    const cell = cellAt(2, 0);

    expect(screen.getByRole("grid")).toHaveClass("sheet-paper");
    for (const name of ["sheet-font-SERIF_TEXT", "sheet-pt", "sheet-has-fill", "sheet-has-text", "sheet-wrap", "sheet-v-top", "sheet-br-thick", "sheet-bb-dotted"]) {
      expect(cell, name).toHaveClass(name);
    }
    expect(cell.style.getPropertyValue("--sheet-fill")).toBe("#abcdef");
    expect(cell.style.getPropertyValue("--sheet-text")).toBe("#102030");
    expect(cell.style.getPropertyValue("--sheet-pt")).toBe("14");
    // the colour is not in the markup as a style attribute that a strict policy would see in the HTML: it is set by script
    expect(cell.className).not.toContain("abcdef");
  });

  it("drops a bad part of a look and still draws the cell", () => {
    const doc = looked({ cells: { "r1:c1": { look: { fill: "url(javascript:1)", font: "Papyrus", size: 99, borders: { left: "WAVY" } } } } });
    render(<GridHost initial={doc} />);
    const cell = cellAt(2, 0);

    expect(cell).toHaveTextContent("Fire exit");
    expect(cell.getAttribute("style") ?? "").not.toContain("--sheet-fill");
    expect(cell.className).not.toMatch(/sheet-(has-fill|font|pt)/);
  });

  it("draws a line between two neighbours once", () => {
    const doc = looked({
      cells: {
        "r1:c1": { look: { borders: { right: "THIN" } } },
        "r1:c2": { look: { borders: { left: "THIN" } } },
      },
    });
    render(<GridHost initial={doc} />);

    expect(cellAt(2, 0)).toHaveClass("sheet-br-thin");
    expect(cellAt(2, 1).className).not.toMatch(/sheet-bl-/);
  });

  it("gives a cell a person types into a thin line when it states no look, and a mark that does not depend on its fill", () => {
    render(<GridHost initial={looked()} />);
    const input = cellAt(3, 1);

    expect(input).toHaveClass("sheet-cell-input");
    expect(input).toHaveClass("sheet-br-thin");
    expect(within(input).getByText("Input cell")).toHaveClass("visually-hidden");
  });

  it("writes a number in its number format, aligns it to the right, and colours it by a rule with a hidden note", () => {
    const doc = looked(
      {
        cells: { "r1:c2": { look: { numberFormat: "TWO_DECIMALS" } }, "r2:c2": { look: { numberFormat: "PERCENT" } } },
        conditions: [{ from: "r1:c2", to: "r2:c2", operator: "GT", value: "3", fill: "#ff0000" }],
      },
      { cells: { ...sampleDoc().cells, "r1:c2": { value: "1234.5", kind: "input" }, "r2:c2": { value: "0.5", kind: "input" } } },
    );
    render(<GridHost initial={doc} />);
    const first = cellAt(2, 1);
    const second = cellAt(3, 1);

    expect(first).toHaveTextContent("1234.50");
    expect(first).toHaveClass("sheet-align-right");
    expect(first).toHaveTextContent("highlighted: above 3");
    expect(first.style.getPropertyValue("--sheet-fill")).toBe("#ff0000");
    expect(second).toHaveTextContent("50%");
    expect(second.style.getPropertyValue("--sheet-fill")).toBe("");
    expect(second).not.toHaveTextContent("highlighted");
  });

  it("shows a formula's answer in its format, judged by its kind, and an error as its code", () => {
    const doc = looked(
      { cells: { "r1:c3": { formula: "@{c2,r1}" , look: { numberFormat: "WHOLE" } }, "r2:c3": { formula: "@{c2,r2}" } }, conditions: [{ from: "r1:c3", to: "r2:c3", operator: "LT", value: "100", colour: "#00aa00" }] },
    );
    render(<GridHost initial={doc} answers={{ "r1:c3": "7.5", "r2:c3": "#DIV/0!" }} kinds={{ "r1:c3": "number", "r2:c3": "error" }} />);

    expect(cellAt(2, 2)).toHaveTextContent("8");
    expect(cellAt(2, 2).style.getPropertyValue("--sheet-text")).toBe("#00aa00");
    expect(cellAt(3, 2)).toHaveTextContent("#DIV/0!");
    expect(cellAt(3, 2).style.getPropertyValue("--sheet-text")).toBe("");
  });

  it("shows yes and no in words, a quiet placeholder for an attachment cell and a marked list", () => {
    const doc = looked(
      { inputs: { "r1:c3": { type: "attachment" } } },
      { cells: { ...sampleDoc().cells, "r2:c2": { value: true, kind: "input" } } },
    );
    render(<GridHost initial={doc} toolbar />);

    expect(cellAt(3, 1)).toHaveTextContent("Yes");
    expect(cellAt(2, 2)).toHaveTextContent("(no attachments yet)");
    expect(cellAt(2, 2)).toHaveClass("sheet-quiet");
    expect(cellAt(4, 2)).toHaveTextContent("List: Pass, Fail");
  });

  it("gives a fill with no text colour an ink, and a style colour is left to win", () => {
    const doc = looked(
      { cells: { "r1:c1": { look: { fill: "#000000" } }, "r2:c1": { look: { fill: "#000000" } } } },
      { cells: { ...sampleDoc().cells, "r2:c1": { value: "Alarm panel", style: { colour: "DANGER" } } } },
    );
    render(<GridHost initial={doc} />);

    expect(cellAt(2, 0)).toHaveClass("sheet-ink-light");
    expect(cellAt(3, 0)).not.toHaveClass("sheet-ink-light");
    expect(cellAt(3, 0)).toHaveClass("sheet-colour-danger");
  });
});

describe("the frozen rows and columns", () => {
  it("draws the fixed rows in their own sticky layer, still named and counted for a screen reader", () => {
    render(<GridHost initial={looked({ freeze: { rows: 2, columns: 0 } })} />);
    const fixed = document.querySelectorAll(".sheet-row-fixed");

    expect(fixed).toHaveLength(2);
    expect(fixed[1]).toHaveClass("sheet-row-fixed-last");
    expect(fixed[0]).toHaveAttribute("aria-rowindex", "2");
    expect(fixed[1]).toHaveAttribute("aria-rowindex", "3");
    expect(screen.getByRole("grid")).toHaveAttribute("aria-rowcount", "6");
    // the rows after them are drawn once, each at its own index
    const indexes = screen.getAllByRole("row").map((row) => row.getAttribute("aria-rowindex"));
    expect(new Set(indexes).size).toBe(indexes.length);
  });

  it("keeps the fixed columns of every row in a layer that sticks to the left edge, with the right column indices", () => {
    render(<GridHost initial={looked({ freeze: { rows: 0, columns: 1 } })} />);

    expect(document.querySelectorAll(".sheet-pane-left").length).toBeGreaterThan(3);
    const cell = cellAt(2, 0);
    expect(cell.closest(".sheet-pane-left")).not.toBeNull();
    expect(cell).toHaveAttribute("aria-colindex", "1");
    expect(cellAt(2, 1).closest(".sheet-pane-left")).toBeNull();
    expect(screen.getAllByRole("columnheader").map((header) => header.getAttribute("aria-colindex"))).toEqual(["1", "2", "3"]);
  });

  it("moves with the keyboard across the line without losing the selection", () => {
    render(<GridHost initial={looked({ freeze: { rows: 1, columns: 1 } })} />);
    const grid = screen.getByRole("grid");

    fireEvent.keyDown(grid, { key: "ArrowDown" });
    fireEvent.keyDown(grid, { key: "ArrowDown" });
    fireEvent.keyDown(grid, { key: "ArrowRight" });

    const active = document.getElementById(grid.getAttribute("aria-activedescendant") ?? "");
    expect(active).toHaveAttribute("data-r", "2");
    expect(active).toHaveAttribute("data-c", "1");
    expect(active).toHaveClass("sheet-active");
  });

  it("freezes no further than a merged area allows", () => {
    const doc = looked({ freeze: { rows: 3, columns: 0 } }, { merges: [{ from: "h2:c1", to: "r2:c1" }] });
    render(<GridHost initial={doc} />);

    // the area covers the second to the fourth row, so a line after 3 rows would cut it: the line is moved up to after row 1
    expect(document.querySelectorAll(".sheet-row-fixed")).toHaveLength(1);
  });

  it("draws the sample rows and a calculated column of a sheet where the filler adds the rows", () => {
    const doc: GridDoc = { ...oneDimension(), formatVersion: 2, sheet: { key: "main", cells: {}, freeze: { rows: 1, columns: 0 } } };
    render(<GridHost initial={doc} />);

    expect(document.querySelectorAll(".sheet-row-fixed")).toHaveLength(1);
    expect(screen.getAllByRole("gridcell").length).toBeGreaterThan(10);
  });
});
