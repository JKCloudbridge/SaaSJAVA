import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { SheetPrintView } from "./SheetPrintView";

function looked(extras: Partial<NonNullable<GridDoc["sheet"]>> = {}, change: Partial<GridDoc> = {}): GridDoc {
  return { ...sampleDoc(), formatVersion: 2, sheet: { key: "main", cells: {}, ...extras }, ...change };
}

function draw(doc: GridDoc, answers?: Parameters<typeof SheetPrintView>[0]["answers"]) {
  render(<SheetPrintView doc={doc} answers={answers} footer="" label="Fire checks" onClose={vi.fn()} />);
  return screen.getByRole("table", { name: "Fire checks" });
}

const cellOf = (table: HTMLElement, text: string) => within(table).getByText(text).closest("td, th") as HTMLElement;

// The print view of a sheet in format version 2 (SH4): the same look on a white page, only the stated borders, rules applied.
describe("the print view of a sheet in format version 2", () => {
  it("draws the look as the screen does, through the same fixed classes and properties", () => {
    const table = draw(
      looked({ cells: { "r1:c1": { look: { font: "MONO_CODE", size: 12, fill: "#FFEEDD", borders: { right: "MEDIUM" } } } } }),
    );
    const cell = cellOf(table, "Fire exit");

    expect(table).toHaveClass("sheet-print-looked");
    for (const name of ["sheet-font-MONO_CODE", "sheet-pt", "sheet-has-fill", "sheet-br-medium"]) expect(cell, name).toHaveClass(name);
    expect(cell.style.getPropertyValue("--sheet-fill")).toBe("#ffeedd");
  });

  it("applies the conditional colours to the answers it is given, with the note for a screen reader", () => {
    const doc = looked(
      { cells: { "r1:c3": { formula: "@{c2,r1}", look: { numberFormat: "PERCENT" } } }, conditions: [{ from: "r1:c3", to: "r1:c3", operator: "GTE", value: "0.5", fill: "#ffcccc" }] },
    );
    const table = draw(doc, { text: { "r1:c3": "0.75" }, kinds: { "r1:c3": "number" } });
    const cell = cellOf(table, "75%");

    expect(cell).toHaveClass("sheet-print-right");
    expect(cell.style.getPropertyValue("--sheet-fill")).toBe("#ffcccc");
    expect(cell).toHaveTextContent("highlighted: at least 0.5");
  });

  it("shows header rows once at the top when the sheet says they do not repeat, and fixed widths when it is not fitted", () => {
    const table = draw(looked({ print: { paper: "A4", orientation: "PORTRAIT", fitToWidth: false, repeatHeaderRows: false } }));

    expect(table.querySelector("thead")?.children).toHaveLength(0);
    expect(within(table.querySelector("tbody") as HTMLElement).getByText("Inspection")).toBeInTheDocument();
    // not fitted to the width of the page: the table keeps the sheet's own widths when printed too
    expect(table).not.toHaveClass("sheet-print-fit");
    expect((table.querySelector("col") as HTMLElement).style.getPropertyValue("--col-px")).toBe("160px");
  });

  it("leaves a sheet in the earlier format exactly as it was: no looked class, no custom property", () => {
    const table = draw(sampleDoc());

    expect(table).not.toHaveClass("sheet-print-looked");
    expect(table.querySelector("td, th")?.getAttribute("style")).toBeNull();
    expect(table.querySelector("thead")?.children.length).toBeGreaterThan(0);
  });
});
