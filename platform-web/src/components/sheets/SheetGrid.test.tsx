import { act, createEvent, fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";
import type { SheetEditor } from "./useSheetEditor";

sizeTheGrid();

/** Renders a host and gives back the grid and a way to read the document the editor holds now. */
function draw(props: Parameters<typeof GridHost>[0] = {}) {
  let latest: SheetEditor | undefined;
  render(<GridHost {...props} onEditor={(editor) => (latest = editor)} />);
  const grid = screen.getByRole("grid");
  return { grid, doc: (): GridDoc => (latest as SheetEditor).doc, editor: () => latest as SheetEditor };
}

const active = (grid: HTMLElement) => document.getElementById(grid.getAttribute("aria-activedescendant") ?? "");
const key = (grid: HTMLElement, name: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(grid, { key: name, ...init });

describe("the grid and a screen reader", () => {
  it("is a grid with counted rows and columns, named column headers and cells", () => {
    const { grid } = draw();

    expect(grid).toHaveAccessibleName("Test sheet");
    expect(grid).toHaveAttribute("aria-rowcount", "6");
    expect(grid).toHaveAttribute("aria-colcount", "3");
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Item", "Amount * (required)", "Result"]);
    expect(within(grid).getAllByRole("row").length).toBeGreaterThan(3);
    expect(grid).toHaveAttribute("tabindex", "0");
    expect(active(grid)).not.toBeNull();
  });

  it("says which cells are input cells in words, not only in colour", () => {
    draw();

    const cell = document.querySelector('[data-r="2"][data-c="1"]') as HTMLElement;
    expect(cell).toHaveTextContent("Input cell");
    expect(cell).toHaveClass("sheet-cell-input");
    const fixed = document.querySelector('[data-r="2"][data-c="0"]') as HTMLElement;
    expect(fixed).toHaveTextContent("Fire exit");
    expect(fixed).toHaveClass("sheet-cell-fixed");
  });

  it("names a row by its label and shows a yes or no in words", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      columns: [{ id: "c1", label: "Done", type: "boolean" }],
      cells: { "r1:c1": { value: true, kind: "input" } },
    };
    draw({ initial: doc });

    expect(screen.getByRole("row", { name: "First" })).toBeInTheDocument();
    expect(screen.getByText("Yes")).toBeInTheDocument();
  });

  it("draws only the rows and columns on the screen, whatever the size of the sheet", () => {
    const rows = Array.from({ length: 500 }, (_, i) => ({ id: `r${i}`, label: `Row ${i}` }));
    const columns = Array.from({ length: 100 }, (_, i) => ({ id: `c${i}`, label: `Column ${i}`, type: "text" as const }));
    draw({ initial: { ...sampleDoc(), columns, rows, headerRows: [], cells: {} } });

    const drawn = screen.getAllByRole("gridcell").length;
    expect(drawn).toBeGreaterThan(0);
    expect(drawn).toBeLessThan(600);
    expect(screen.getByRole("grid")).toHaveAttribute("aria-rowcount", "501");
    expect(screen.getByRole("grid")).toHaveAttribute("aria-colcount", "100");
  });

  it("is read-only for a reader and says so", () => {
    const { grid } = draw({ readOnly: true });

    expect(grid).toHaveAttribute("aria-readonly", "true");
    expect(screen.getAllByRole("gridcell")[0]).toHaveAttribute("aria-readonly", "true");
  });
});

describe("moving with the keyboard", () => {
  it("moves one cell with the arrow keys and stays inside the grid", () => {
    const { grid } = draw();
    const start = grid.getAttribute("aria-activedescendant");

    key(grid, "ArrowRight");
    expect(active(grid)).toHaveAttribute("data-c", "1");
    key(grid, "ArrowDown");
    expect(active(grid)).toHaveAttribute("data-r", "1");
    key(grid, "ArrowLeft");
    key(grid, "ArrowLeft");
    key(grid, "ArrowUp");
    key(grid, "ArrowUp");
    expect(grid.getAttribute("aria-activedescendant")).toBe(start);
  });

  it("goes to the ends with Home, End and the page keys", () => {
    const { grid } = draw();

    key(grid, "End");
    expect(active(grid)).toHaveAttribute("data-c", "2");
    key(grid, "Home");
    expect(active(grid)).toHaveAttribute("data-c", "0");
    key(grid, "End", { ctrlKey: true });
    expect(active(grid)).toHaveAttribute("data-r", "4");
    expect(active(grid)).toHaveAttribute("data-c", "2");
    key(grid, "Home", { ctrlKey: true });
    key(grid, "PageDown");
    expect(active(grid)).toHaveAttribute("data-r", "4");
    key(grid, "PageUp");
    expect(active(grid)).toHaveAttribute("data-r", "0");
  });

  it("moves with Tab inside the grid and lets Tab leave at the last cell", () => {
    const { grid } = draw();

    expect(key(grid, "Tab")).toBe(false);
    expect(active(grid)).toHaveAttribute("data-c", "1");
    key(grid, "End", { ctrlKey: true });
    expect(key(grid, "Tab")).toBe(true);
    key(grid, "Home", { ctrlKey: true });
    expect(key(grid, "Tab", { shiftKey: true })).toBe(true);
  });

  it("extends the selection with Shift and the arrow keys", () => {
    const { grid } = draw();

    key(grid, "ArrowDown", { shiftKey: true });
    key(grid, "ArrowRight", { shiftKey: true });

    expect(document.querySelectorAll(".sheet-selected")).toHaveLength(4);
    expect(screen.getAllByRole("gridcell").filter((cell) => cell.getAttribute("aria-selected") === "true")).toHaveLength(4);
  });

  it("selects everything with Ctrl+A", () => {
    const { grid } = draw();

    key(grid, "a", { ctrlKey: true });

    expect(document.querySelectorAll(".sheet-selected")).toHaveLength(15);
  });

  it("moves over a merged area as one cell", () => {
    const merged: GridDoc = { ...sampleDoc(), merges: [{ from: "r1:c1", to: "r2:c2" }], cells: { "r1:c1": { value: "Both" } } };
    const { grid } = draw({ initial: merged });

    for (let i = 0; i < 2; i++) {
      key(grid, "ArrowDown");
    }
    expect(active(grid)).toHaveAttribute("data-r", "2");
    expect(active(grid)).toHaveAttribute("aria-colspan", "2");
    expect(active(grid)).toHaveAttribute("aria-rowspan", "2");
    key(grid, "ArrowRight");
    expect(active(grid)).toHaveAttribute("data-c", "2");
    key(grid, "ArrowLeft");
    expect(active(grid)).toHaveAttribute("data-c", "0");
    expect(document.querySelector('[data-r="2"][data-c="1"]')).toBeNull();
  });
});

describe("writing in a cell", () => {
  it("starts with a character, shows a box, and saves the text with Enter as fixed text", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowRight");

    key(grid, "x");
    const box = screen.getByRole("textbox");
    expect(box).toHaveValue("x");
    expect(box).toHaveAccessibleName("Amount, First");
    fireEvent.change(box, { target: { value: "Limit 5" } });
    fireEvent.keyDown(box, { key: "Enter" });

    expect(doc().cells["r1:c2"]).toEqual({ value: "Limit 5" });
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(grid).toHaveFocus();
    expect(active(grid)).toHaveAttribute("data-r", "3");
  });

  it("opens the text that is there with Enter or F2, and Escape throws the change away", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "Enter");
    const box = screen.getByRole("textbox");
    expect(box).toHaveValue("Fire exit");
    fireEvent.change(box, { target: { value: "changed" } });
    fireEvent.keyDown(box, { key: "Escape" });

    expect(doc().cells["r1:c1"]).toEqual({ value: "Fire exit" });
    key(grid, "F2");
    expect(screen.getByRole("textbox")).toHaveValue("Fire exit");
  });

  it("opens with a double click", () => {
    draw();

    fireEvent.doubleClick(document.querySelector('[data-r="2"][data-c="0"]') as HTMLElement);

    expect(screen.getByRole("textbox")).toHaveValue("Fire exit");
  });

  it("stays in the cell and says why when the text is refused", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "Enter");
    const box = screen.getByRole("textbox");
    fireEvent.change(box, { target: { value: "<b>bold</b>" } });
    fireEvent.keyDown(box, { key: "Enter" });

    expect(screen.getByRole("alert")).toHaveTextContent("markup or a control character");
    expect(screen.getByRole("textbox")).toHaveValue("<b>bold</b>");
    expect(doc().cells["r1:c1"]).toEqual({ value: "Fire exit" });
  });

  it("moves on with Tab and back with Shift+Enter", () => {
    const { grid } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "q");
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Tab" });
    expect(active(grid)).toHaveAttribute("data-c", "1");
    key(grid, "w");
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter", shiftKey: true });
    expect(active(grid)).toHaveAttribute("data-r", "1");
  });

  it("clears the selected cells with Delete and undoes and redoes with Ctrl+Z and Ctrl+Shift+Z", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "Delete");
    expect(doc().cells["r1:c1"]).toBeUndefined();
    key(grid, "z", { ctrlKey: true });
    expect(doc().cells["r1:c1"]).toEqual({ value: "Fire exit" });
    key(grid, "z", { ctrlKey: true, shiftKey: true });
    expect(doc().cells["r1:c1"]).toBeUndefined();
    key(grid, "z", { ctrlKey: true });
    key(grid, "y", { ctrlKey: true });
    expect(doc().cells["r1:c1"]).toBeUndefined();
  });

  it("refuses to write in the blank sample rows of a sheet whose filler adds the rows", () => {
    const { grid, doc } = draw({ initial: oneDimension() });
    for (let i = 0; i < 2; i++) {
      key(grid, "ArrowDown");
    }

    key(grid, "x");

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("blank rows");
    expect(Object.keys(doc().cells)).toEqual(["h1:c1"]);
  });

  it("changes nothing when the grid is read-only", () => {
    const { grid, doc } = draw({ readOnly: true });
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "x");
    key(grid, "Enter");
    key(grid, "Delete");
    key(grid, "b", { ctrlKey: true });
    fireEvent.doubleClick(document.querySelector('[data-r="2"][data-c="0"]') as HTMLElement);

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(doc().cells["r1:c1"]).toEqual({ value: "Fire exit" });
  });
});

describe("styles by key", () => {
  it("makes fixed text bold and italic with Ctrl+B and Ctrl+I, and off again", () => {
    const { grid } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "b", { ctrlKey: true });
    key(grid, "i", { ctrlKey: true });
    const cell = active(grid) as HTMLElement;
    expect(cell).toHaveClass("sheet-bold", "sheet-italic");
    key(grid, "b", { ctrlKey: true });
    expect((active(grid) as HTMLElement).classList.contains("sheet-bold")).toBe(false);
  });

  it("says to type the text first when the cell is an input cell", () => {
    const { grid } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowRight");

    key(grid, "b", { ctrlKey: true });

    expect(screen.getByRole("alert")).toHaveTextContent("Type the text first");
  });

  it("draws the four sizes by name", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      cells: {
        "r1:c1": { value: "a", style: { fontSize: "SMALL" } },
        "r2:c1": { value: "b", style: { fontSize: "LARGE" } },
        "r3:c1": { value: "c", style: { fontSize: "TITLE" } },
      },
    };
    draw({ initial: doc });

    expect(screen.getByText("a")).toHaveClass("sheet-size-small");
    expect(screen.getByText("b")).toHaveClass("sheet-size-large");
    expect(screen.getByText("c")).toHaveClass("sheet-size-title");
  });
});

describe("paste and copy", () => {
  function paste(grid: HTMLElement, text: string) {
    const event = createEvent.paste(grid, { clipboardData: { getData: () => text } });
    fireEvent(grid, event);
  }

  it("writes a block copied from a spreadsheet application from the selected cell, as one step", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowRight");

    paste(grid, "a\tb\n1\t2");

    expect(doc().cells["r1:c2"]).toEqual({ value: "a" });
    expect(doc().cells["r1:c3"]).toEqual({ value: "b" });
    expect(doc().cells["r2:c2"]).toEqual({ value: "1" });
    expect(doc().cells["r2:c3"]).toEqual({ value: "2" });
    expect(screen.getByRole("status")).toHaveTextContent("Pasted 4 cells.");
    key(grid, "z", { ctrlKey: true });
    expect(doc().cells["r1:c2"]).toBeUndefined();
    expect(doc().cells["r2:c3"]).toBeUndefined();
  });

  it("says what it skipped and why", () => {
    const { grid } = draw();
    key(grid, "End", { ctrlKey: true });

    paste(grid, "x\t<b>\ny\tz");

    expect(screen.getByRole("status")).toHaveTextContent("Pasted 1 cell, 3 skipped.");
  });

  it("pastes nothing into a read-only grid", () => {
    const { grid, doc } = draw({ readOnly: true });

    paste(grid, "x");

    expect(doc().cells["r1:c1"]).toEqual({ value: "Fire exit" });
  });

  it("copies the selected cells as tab and line separated text", () => {
    const { grid } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowDown", { shiftKey: true });
    key(grid, "ArrowRight", { shiftKey: true });
    let copied = "";
    const event = createEvent.copy(grid, { clipboardData: { setData: (_type: string, value: string) => (copied = value) } });

    fireEvent(grid, event);

    expect(copied).toBe("Fire exit\t\nAlarm panel\t");
  });
});

describe("an input method", () => {
  it("opens the cell when composition starts and does not save on the Enter that ends a composition", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowRight");

    fireEvent.compositionStart(grid);
    const box = screen.getByRole("textbox");
    fireEvent.change(box, { target: { value: "日本" } });
    fireEvent.keyDown(box, { key: "Enter", isComposing: true });

    expect(screen.getByRole("textbox")).toBeInTheDocument();
    expect(doc().cells["r1:c2"]).toBeUndefined();
    fireEvent.keyDown(box, { key: "Enter" });
    expect(doc().cells["r1:c2"]).toEqual({ value: "日本" });
  });

  it("opens the cell on the key an input method sends first", () => {
    const { grid } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    key(grid, "Process");

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("does not move the grid while a composition is going on", () => {
    const { grid } = draw();
    const before = grid.getAttribute("aria-activedescendant");

    key(grid, "ArrowRight", { isComposing: true });

    expect(grid.getAttribute("aria-activedescendant")).toBe(before);
  });
});

describe("the mouse", () => {
  it("selects a cell on a click and extends with Shift and a drag", () => {
    const { grid } = draw();
    const from = document.querySelector('[data-r="2"][data-c="0"]') as HTMLElement;
    const to = document.querySelector('[data-r="3"][data-c="1"]') as HTMLElement;

    fireEvent.mouseDown(from);
    fireEvent.mouseOver(to);
    fireEvent.mouseUp(window);

    expect(active(grid)).toHaveAttribute("data-r", "3");
    expect(document.querySelectorAll(".sheet-selected")).toHaveLength(4);
    act(() => {
      fireEvent.mouseDown(document.querySelector('[data-r="4"][data-c="2"]') as HTMLElement, { shiftKey: true });
      fireEvent.mouseUp(window);
    });
    expect(document.querySelectorAll(".sheet-selected").length).toBeGreaterThan(4);
  });
});
