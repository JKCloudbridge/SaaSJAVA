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

describe("letters, row numbers, grey and white and the paperclip (SH5.1)", () => {
  it("heads the columns with letters and numbers the rows as the formula bar counts them, keeping the names as tooltips", () => {
    render(<GridHost initial={looked()} />);

    const headers = screen.getAllByRole("columnheader");
    expect(headers.map((header) => header.textContent)).toEqual(["A", "B * (required)", "C"]);
    expect(headers[0]).toHaveAttribute("title", "Item");
    expect(headers[0]).toHaveAccessibleName("A, Item");
    expect(headers[1]).toHaveAccessibleName("B, Amount (required)");
    // two header rows, then the three named rows: 1 to 5, as toExcel writes them
    expect(screen.getAllByRole("rowheader").map((cell) => cell.textContent)).toEqual(["1", "2", "3", "4", "5"]);
    // the number rides with the row, in the layer that stays at the left edge
    expect(screen.getAllByRole("rowheader")[2]?.closest(".sheet-pane-left")).not.toBeNull();
  });

  it("starts every column after the band of row numbers", () => {
    render(<GridHost initial={looked()} />);

    expect(cellAt(2, 0).style.left).toBe("44px");
    expect(cellAt(2, 1).style.left).toBe("204px");
  });

  it("keeps named headings and no row numbers on a sheet in the earlier format", () => {
    render(<GridHost initial={sampleDoc()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Item",
      "Amount * (required)",
      "Result",
    ]);
    expect(screen.queryAllByRole("rowheader")).toHaveLength(0);
    expect(cellAt(2, 0).style.left).toBe("0px");
  });

  it("marks a fixed cell with no fill of its own for the grey, and leaves a cell with a fill or a rule's colour alone", () => {
    const doc = looked({
      cells: { "r2:c1": { look: { fill: "#ccffcc" } }, "r3:c1": { formula: "@{c2,r1}" } },
      conditions: [{ from: "r3:c1", to: "r3:c1", operator: "LT", value: "100", fill: "#ffcccc" }],
    });
    render(<GridHost initial={doc} answers={{ "r3:c1": "7" }} kinds={{ "r3:c1": "number" }} />);

    // "Fire exit": fixed text, no fill of its own: the grey applies (it is the class without a fill)
    expect(cellAt(2, 0)).toHaveClass("sheet-cell-fixed");
    expect(cellAt(2, 0)).not.toHaveClass("sheet-has-fill");
    // a fixed cell with its own fill, and one a conditional colour paints, carry the fill and so are never grey
    expect(cellAt(3, 0)).toHaveClass("sheet-has-fill");
    expect(cellAt(4, 0)).toHaveClass("sheet-has-fill");
    // a cell to fill stays white and still says so to a screen reader
    expect(cellAt(2, 1)).toHaveClass("sheet-cell-input");
    expect(within(cellAt(2, 1)).getByText("Input cell")).toHaveClass("visually-hidden");
  });

  it("keeps what was typed and selects the cell clicked while a cell is being typed into", () => {
    let latest: Parameters<NonNullable<Parameters<typeof GridHost>[0]["onEditor"]>>[0] | undefined;
    render(<GridHost initial={looked()} onEditor={(editor) => (latest = editor)} />);
    const grid = screen.getByRole("grid");

    fireEvent.mouseDown(cellAt(2, 1));
    fireEvent.keyDown(grid, { key: "7" });
    expect(screen.getByRole("textbox")).toBeInTheDocument();
    fireEvent.mouseDown(cellAt(3, 2));

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(latest?.doc.cells["r1:c2"]?.value).toBe("7");
    const active = document.getElementById(grid.getAttribute("aria-activedescendant") ?? "");
    expect(active).toHaveAttribute("data-r", "3");
    expect(active).toHaveAttribute("data-c", "2");
  });

  it("counts a plain white fill as no fill, so fixed text the file painted white is grey too", () => {
    const doc = looked({ cells: { "r1:c1": { look: { fill: "#FFFFFF" } }, "r2:c1": { look: { fill: "#ccffcc" } } } });
    render(<GridHost initial={doc} />);

    expect(cellAt(2, 0)).toHaveClass("sheet-cell-fixed", "sheet-fill-white");
    expect(cellAt(3, 0)).not.toHaveClass("sheet-fill-white");
  });

  it("draws a paperclip on an attachment cell and opens its popup with one click or Enter, also in a read-only grid", () => {
    const opened: string[] = [];
    const doc = looked({ inputs: { "r1:c3": { type: "attachment" } } });
    render(
      <GridHost
        initial={doc}
        readOnly
        openCell={(rowId, columnId) => {
          opened.push(`${rowId}:${columnId}`);
          return true;
        }}
      />,
    );
    const cell = cellAt(2, 2);

    expect(cell.querySelector("svg.sheet-clip")).not.toBeNull();
    expect(cell).toHaveTextContent("Attachment cell: (no attachments yet)");
    expect(cellAt(2, 1).querySelector("svg.sheet-clip")).toBeNull();

    fireEvent.mouseDown(cell);
    fireEvent.click(cell);
    expect(opened).toEqual(["r1:c3"]);

    fireEvent.keyDown(screen.getByRole("grid"), { key: "Enter" });
    expect(opened).toEqual(["r1:c3", "r1:c3"]);
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
