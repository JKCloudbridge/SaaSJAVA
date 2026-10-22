import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { marksOf, NO_MARKS } from "@/lib/sheets/annotate";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";

sizeTheGrid();

const active = (grid: HTMLElement) => document.getElementById(grid.getAttribute("aria-activedescendant") ?? "");

describe("the grid in annotate mode (FA5)", () => {
  it("marks the places that have notes, in words as well as in the look", () => {
    const marks = marksOf([
      { rowId: "r1", columnId: "c1" },
      { rowId: "r1", columnId: "c1" },
      { rowId: "r2", columnId: null },
      { rowId: null, columnId: "c2" },
    ]);
    render(<GridHost readOnly annotation={{ marks, onActivate: () => undefined }} />);

    const cell = document.querySelector('[data-r="2"][data-c="0"]') as HTMLElement;
    expect(cell).toHaveClass("sheet-noted");
    expect(cell).toHaveTextContent("(2 notes)");
    const header = screen.getAllByRole("columnheader").find((one) => one.textContent?.startsWith("Amount")) as HTMLElement;
    expect(header).toHaveClass("sheet-noted");
    expect(header).toHaveTextContent("(1 note)");
    const row = screen.getByRole("row", { name: "Second, 1 note" });
    expect(row).toHaveClass("sheet-row-noted");
    expect(screen.getByRole("row", { name: "First" })).not.toHaveClass("sheet-row-noted");
  });

  it("draws nothing extra when no place has a note", () => {
    render(<GridHost readOnly annotation={{ marks: NO_MARKS, onActivate: () => undefined }} />);

    expect(document.querySelector(".sheet-noted")).toBeNull();
    expect(document.querySelector(".sheet-row-noted")).toBeNull();
  });

  it("stands on a column when its label is clicked", () => {
    render(<GridHost readOnly annotation={{ marks: NO_MARKS, onActivate: () => undefined }} />);
    const grid = screen.getByRole("grid");

    const label = screen.getAllByRole("columnheader").find((one) => one.textContent?.startsWith("Result")) as HTMLElement;
    fireEvent.mouseDown(label);

    expect(active(grid)).toHaveAttribute("data-c", "2");
    expect(grid).toHaveFocus();
  });

  it("does not make the column labels clickable outside annotate mode", () => {
    render(<GridHost readOnly />);
    const grid = screen.getByRole("grid");
    const before = grid.getAttribute("aria-activedescendant");

    const label = screen.getAllByRole("columnheader").find((one) => one.textContent?.startsWith("Result")) as HTMLElement;
    fireEvent.mouseDown(label);

    expect(label).not.toHaveClass("sheet-label-pick");
    expect(grid.getAttribute("aria-activedescendant")).toBe(before);
  });

  it("asks for the box of a note on Enter, F2 and a double click, and never opens a cell for typing", () => {
    const onActivate = vi.fn();
    render(<GridHost readOnly annotation={{ marks: NO_MARKS, onActivate }} />);
    const grid = screen.getByRole("grid");

    fireEvent.keyDown(grid, { key: "Enter" });
    fireEvent.keyDown(grid, { key: "F2" });
    fireEvent.doubleClick(document.querySelector('[data-r="2"][data-c="1"]') as HTMLElement);
    fireEvent.keyDown(grid, { key: "x" });

    expect(onActivate).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it("does nothing on Enter when there is no annotate mode", () => {
    render(<GridHost readOnly />);

    fireEvent.keyDown(screen.getByRole("grid"), { key: "Enter" });

    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it("still moves with the arrow keys", () => {
    render(<GridHost readOnly annotation={{ marks: NO_MARKS, onActivate: () => undefined }} />);
    const grid = screen.getByRole("grid");

    fireEvent.keyDown(grid, { key: "ArrowDown" });
    fireEvent.keyDown(grid, { key: "ArrowRight" });

    expect(active(grid)).toHaveAttribute("data-r", "1");
    expect(active(grid)).toHaveAttribute("data-c", "1");
  });
});
