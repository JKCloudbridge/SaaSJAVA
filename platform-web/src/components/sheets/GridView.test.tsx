import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LIMITS, oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";
import type { SheetEditor } from "./useSheetEditor";

sizeTheGrid();

function draw(props: Parameters<typeof GridHost>[0] = {}) {
  let latest: SheetEditor | undefined;
  render(<GridHost toolbar {...props} onEditor={(editor) => (latest = editor)} />);
  return { grid: screen.getByRole("grid"), doc: (): GridDoc => (latest as SheetEditor).doc };
}

const key = (grid: HTMLElement, name: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(grid, { key: name, ...init });
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole("button", { name }));

describe("the toolbar of the grid view", () => {
  it("offers formatting, merging, the kind of cell, history and adding to the sheet", () => {
    draw();

    expect(screen.getByRole("toolbar", { name: "Formatting" })).toBeInTheDocument();
    for (const name of ["Bold", "Italic", "Merge", "Unmerge", "Fixed text", "Input cell", "Undo", "Redo", "Add row", "Add column"]) {
      expect(screen.getByRole("button", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("combobox", { name: "Text size" })).toBeInTheDocument();
  });

  it("makes the selected fixed text bold and italic and shows the buttons as pressed", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    click("Bold");
    click("Italic");

    expect(doc().cells["r1:c1"]?.style).toEqual({ bold: true, italic: true });
    expect(screen.getByRole("button", { name: "Bold" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Italic" })).toHaveAttribute("aria-pressed", "true");
    click("Bold");
    expect(screen.getByRole("button", { name: "Bold" })).toHaveAttribute("aria-pressed", "false");
  });

  it("sets one of four sizes by name", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    fireEvent.change(screen.getByRole("combobox", { name: "Text size" }), { target: { value: "LARGE" } });

    expect(doc().cells["r1:c1"]?.style).toEqual({ fontSize: "LARGE" });
    expect(Array.from(screen.getByRole("combobox", { name: "Text size" }).querySelectorAll("option"), (o) => o.textContent)).toEqual([
      "Small",
      "Normal",
      "Large",
      "Title",
    ]);
  });

  it("merges the selection and unmerges it, one step each, and the merged cell spans", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");
    key(grid, "ArrowRight", { shiftKey: true });
    key(grid, "ArrowDown", { shiftKey: true });

    click("Merge");

    expect(doc().merges).toEqual([{ from: "r1:c1", to: "r2:c2" }]);
    expect(screen.getByRole("status")).toHaveTextContent("Only the top left cell keeps its content.");
    expect(document.querySelector('[data-r="2"][data-c="0"]')).toHaveAttribute("aria-colspan", "2");
    click("Unmerge");
    expect(doc().merges).toEqual([]);
    click("Undo");
    expect(doc().merges).toHaveLength(1);
  });

  it("does not offer to merge one cell and tries nothing it knows will be refused", () => {
    draw();

    expect(screen.getByRole("button", { name: "Merge" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Unmerge" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it("refuses a merge across the header and the body in the words of the model", () => {
    const { grid } = draw();
    key(grid, "ArrowDown", { shiftKey: true });
    key(grid, "ArrowDown", { shiftKey: true });

    click("Merge");

    expect(screen.getByRole("alert")).toHaveTextContent("cannot join header rows and body rows");
  });

  it("turns a fixed cell into an input cell and back", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    click("Input cell");
    expect(doc().cells["r1:c1"]).toBeUndefined();
    expect(screen.getByRole("button", { name: "Input cell" })).toHaveAttribute("aria-pressed", "true");
    click("Fixed text");
    expect(doc().cells["r1:c1"]).toEqual({ kind: "fixed" });
  });

  it("adds a row after the selected one and a column after the selected column", () => {
    const { grid, doc } = draw();
    key(grid, "ArrowDown");
    key(grid, "ArrowDown");

    click("Add row");
    click("Add column");

    expect(doc().rows).toHaveLength(4);
    expect(doc().rows[1]?.id).not.toBe("r2");
    expect(doc().columns).toHaveLength(4);
    expect(doc().columns[1]?.id).toMatch(/^c/);
  });

  it("offers no row to add where the filler adds the rows", () => {
    draw({ initial: oneDimension() });

    expect(screen.queryByRole("button", { name: "Add row" })).toBeNull();
    expect(screen.getByRole("button", { name: "Add column" })).toBeInTheDocument();
  });

  it("has no toolbar when the grid is read-only, and says why", () => {
    render(<GridHost toolbar readOnly />);

    expect(screen.queryByRole("toolbar")).toBeNull();
    expect(screen.getByText(/Arrow keys move/)).toBeInTheDocument();
  });

  it("shows the limits of the installation it was given", () => {
    draw({ limits: { ...LIMITS, maxColumns: 3 } });

    click("Add column");

    expect(screen.getByRole("alert")).toHaveTextContent("at most 3 columns");
    expect(sampleDoc().columns).toHaveLength(3);
  });
});

describe("changing the width of a column by dragging its edge", () => {
  const edge = (name: string) => screen.getByRole("separator", { name: `Width of column ${name}` });

  it("widens the column while the mouse moves and writes the width once, when it is let go", () => {
    const { doc } = draw();
    const original = doc().columns[0]?.width;
    const before = original ?? 120;

    fireEvent.mouseDown(edge("Item"), { clientX: 100 });
    fireEvent.mouseMove(document, { clientX: 160 });
    expect(edge("Item")).toHaveAttribute("aria-valuenow", String(before + 60));
    expect(doc().columns[0]?.width).toBe(original);
    fireEvent.mouseUp(document);

    expect(doc().columns[0]?.width).toBe(before + 60);
    fireEvent.keyDown(screen.getByRole("grid"), { key: "z", ctrlKey: true });
    expect(doc().columns[0]?.width).toBe(before);
  });

  it("keeps a column between 40 and 1000 pixels", () => {
    const { doc } = draw();

    fireEvent.mouseDown(edge("Item"), { clientX: 500 });
    fireEvent.mouseMove(document, { clientX: -900 });
    fireEvent.mouseUp(document);
    expect(doc().columns[0]?.width).toBe(40);

    fireEvent.mouseDown(edge("Item"), { clientX: 0 });
    fireEvent.mouseMove(document, { clientX: 5000 });
    fireEvent.mouseUp(document);
    expect(doc().columns[0]?.width).toBe(1000);
  });

  it("goes back to the default width on a double click, and changes nothing when the mouse did not move", () => {
    const { doc } = draw();
    fireEvent.mouseDown(edge("Item"), { clientX: 10 });
    fireEvent.mouseMove(document, { clientX: 90 });
    fireEvent.mouseUp(document);
    expect(doc().columns[0]?.width).toBeDefined();

    fireEvent.doubleClick(edge("Item"));
    expect(doc().columns[0]?.width).toBeUndefined();

    const same = doc();
    fireEvent.mouseDown(edge("Item"), { clientX: 10 });
    fireEvent.mouseUp(document);
    expect(doc()).toBe(same);
  });

  it("draws no handle in a grid that cannot be changed", () => {
    draw({ readOnly: true });

    expect(screen.queryByRole("separator")).toBeNull();
  });
});

describe("changing the height of a row by dragging its edge", () => {
  const edges = () => screen.getAllByRole("separator", { name: /^Height of / });

  it("makes the row taller while the mouse moves and writes the height once, when it is let go", () => {
    const { doc } = draw();
    const original = doc().headerRows[0]?.height;
    const before = original ?? 24;

    fireEvent.mouseDown(edges()[0] as HTMLElement, { clientY: 100 });
    fireEvent.mouseMove(document, { clientY: 130 });
    expect(edges()[0]).toHaveAttribute("aria-valuenow", String(before + 30));
    expect(doc().headerRows[0]?.height).toBe(original);
    fireEvent.mouseUp(document);

    expect(doc().headerRows[0]?.height).toBe(before + 30);
    fireEvent.keyDown(screen.getByRole("grid"), { key: "z", ctrlKey: true });
    expect(doc().headerRows[0]?.height).toBe(original);
  });

  it("keeps a row between 16 and 400 pixels", () => {
    const { doc } = draw();

    fireEvent.mouseDown(edges()[0] as HTMLElement, { clientY: 500 });
    fireEvent.mouseMove(document, { clientY: -900 });
    fireEvent.mouseUp(document);
    expect(doc().headerRows[0]?.height).toBe(16);

    fireEvent.mouseDown(edges()[0] as HTMLElement, { clientY: 0 });
    fireEvent.mouseMove(document, { clientY: 5000 });
    fireEvent.mouseUp(document);
    expect(doc().headerRows[0]?.height).toBe(400);
  });

  it("goes back to the default height on a double click, and changes nothing when the mouse did not move", () => {
    const { doc } = draw();
    fireEvent.mouseDown(edges()[0] as HTMLElement, { clientY: 10 });
    fireEvent.mouseMove(document, { clientY: 50 });
    fireEvent.mouseUp(document);
    expect(doc().headerRows[0]?.height).toBeDefined();

    fireEvent.doubleClick(edges()[0] as HTMLElement);
    expect(doc().headerRows[0]?.height).toBeUndefined();

    const same = doc();
    fireEvent.mouseDown(edges()[0] as HTMLElement, { clientY: 10 });
    fireEvent.mouseUp(document);
    expect(doc()).toBe(same);
  });

  it("draws no row handle in a grid that cannot be changed", () => {
    draw({ readOnly: true });

    expect(screen.queryByRole("separator", { name: /^Height of / })).toBeNull();
  });
});
