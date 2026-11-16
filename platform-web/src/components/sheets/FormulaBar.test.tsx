import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { fromApi } from "@/lib/sheets/adapter";
import { wireFromCases } from "@/lib/sheets/formula/caseFile";
import { LIMITS } from "@/lib/sheets/fixtures";
import type { GridDoc, SheetLimits } from "@/lib/sheets/types";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";
import type { SheetEditor } from "./useSheetEditor";

sizeTheGrid();

const limits = { ...LIMITS, formulas: wireFromCases() };

function sheet(): GridDoc {
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
        rows: [{ id: "r1" }, { id: "r2" }],
        cells: {},
        merges: [],
      },
    ],
  });
}

function draw(initial: GridDoc = sheet(), withLimits: SheetLimits = limits) {
  let latest: SheetEditor | undefined;
  render(<GridHost toolbar initial={initial} limits={withLimits} onEditor={(editor) => (latest = editor)} />);
  return { doc: (): GridDoc => (latest as SheetEditor).doc, editor: (): SheetEditor => latest as SheetEditor };
}

describe("the formula bar of the author's grid", () => {
  it("shows the address of the selected cell before the formula, as the formulas write it (SH5.1)", () => {
    draw();
    expect(screen.getByTestId("name-box")).toHaveTextContent("A1");
    fireEvent.keyDown(screen.getByRole("grid"), { key: "ArrowDown" });
    fireEvent.keyDown(screen.getByRole("grid"), { key: "ArrowRight" });

    // the bar is drawn again for the new cell, so it is looked up again
    expect(screen.getByTestId("name-box")).toHaveTextContent("B2");
  });

  it("gives the selected cell a formula typed with an equals sign and shows its answer as plain text", () => {
    const { doc } = draw();
    const bar = screen.getByRole("textbox", { name: "Formula bar" });

    fireEvent.change(bar, { target: { value: "=1+1" } });
    fireEvent.keyDown(bar, { key: "Enter" });

    expect(doc().sheet?.cells["h1:c1"]?.formula).toBe("1+1");
    expect(screen.getByTestId("formula-answer")).toHaveTextContent("Answer: 2");
    expect(screen.getByRole("textbox", { name: "Formula bar" })).toHaveValue("=1+1");
  });

  it("checks a formula at once with the server's words and keeps what was typed when it is refused", () => {
    const { doc } = draw();
    const bar = screen.getByRole("textbox", { name: "Formula bar" });

    fireEvent.change(bar, { target: { value: "=INDIRECT(A1)" } });
    fireEvent.keyDown(bar, { key: "Enter" });

    expect(screen.getAllByText("A formula uses a function that is not supported.").length).toBeGreaterThan(0);
    expect(doc().sheet?.cells["h1:c1"]).toBeUndefined();
    expect(screen.getByRole("textbox", { name: "Formula bar" })).toHaveValue("=INDIRECT(A1)");
  });

  it("shows an error as its code", () => {
    draw();
    const bar = screen.getByRole("textbox", { name: "Formula bar" });

    fireEvent.change(bar, { target: { value: "=1/0" } });
    fireEvent.keyDown(bar, { key: "Enter" });

    expect(screen.getByTestId("formula-answer")).toHaveTextContent("Answer: #DIV/0!");
  });

  it("types plain text as before, and an apostrophe makes a text that starts with an equals sign", () => {
    const { doc } = draw();
    const bar = screen.getByRole("textbox", { name: "Formula bar" });

    fireEvent.change(bar, { target: { value: "'=not a formula" } });
    fireEvent.keyDown(bar, { key: "Enter" });

    expect(doc().cells["h1:c1"]?.value).toBe("=not a formula");
    expect(doc().sheet?.cells["h1:c1"]?.formula).toBeUndefined();
  });

  it("is not drawn where the server did not say what formulas may be", () => {
    draw(sheet(), { ...LIMITS });

    expect(screen.queryByRole("textbox", { name: "Formula bar" })).not.toBeInTheDocument();
  });

  it("is not drawn for a sheet in the earlier format", () => {
    const earlier: GridDoc = { ...sheet(), formatVersion: 1, sheet: undefined };
    draw(earlier);

    expect(screen.queryByRole("textbox", { name: "Formula bar" })).not.toBeInTheDocument();
  });
});
