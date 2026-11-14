import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { sizeTheGrid } from "@/test/virtualDom";
import { GridHost } from "./harness";

sizeTheGrid();

// The footer row of a sheet (FA7, ADR-0201): one row at the bottom, made when the sheet is shown, never part of the document.
describe("the footer row of the grid", () => {
  it("is one more row at the bottom with the number and the revision at its right", () => {
    render(<GridHost footer="F/MR/16  Rev No.'01'" />);

    const grid = screen.getByRole("grid");
    const footer = screen.getByTestId("sheet-footer");
    expect(footer.textContent).toBe("F/MR/16  Rev No.'01'");
    expect(footer).toHaveClass("sheet-footer");
    expect(grid).toHaveAttribute("aria-rowcount", "7");
    expect(footer).toHaveAttribute("aria-rowindex", "7");
    expect(within(footer).getByRole("gridcell")).toHaveAttribute("aria-readonly", "true");
  });

  it("is two rows when the application's footer carries the day below the number (MC1)", () => {
    render(<GridHost footer={"F/MR/16  Rev No.'00'\nDate: 13-Nov-2026"} />);

    expect(screen.getByTestId("sheet-footer").textContent).toBe("F/MR/16  Rev No.'00'");
    expect(screen.getByTestId("sheet-footer-2").textContent).toBe("Date: 13-Nov-2026");
    expect(screen.getByRole("grid")).toHaveAttribute("aria-rowcount", "8");
    expect(screen.getByTestId("sheet-footer-2")).toHaveAttribute("aria-rowindex", "8");
  });

  it("is there and blank before the template is numbered", () => {
    render(<GridHost footer="" />);

    expect(screen.getByTestId("sheet-footer")).toHaveTextContent("");
    expect(screen.getByRole("grid")).toHaveAttribute("aria-rowcount", "7");
  });

  it("is not drawn when the grid is given none (a fill-in, a sheet being built)", () => {
    render(<GridHost />);

    expect(screen.queryByTestId("sheet-footer")).toBeNull();
    expect(screen.getByRole("grid")).toHaveAttribute("aria-rowcount", "6");
  });

  it("cannot be moved onto or typed into, and the document is not changed by it", () => {
    let seen: unknown;
    render(<GridHost footer="F/MR/16  Rev No.'01'" onEditor={(editor) => (seen = editor.doc)} />);

    expect(screen.getByTestId("sheet-footer").querySelector("[data-r]")).toBeNull();
    expect(JSON.stringify(seen)).not.toContain("Rev No.");
  });

  it("is drawn through the toolbar view of the grid too", () => {
    render(<GridHost toolbar readOnly footer="F/MR/16  Rev No.'00'" />);

    expect(screen.getByTestId("sheet-footer")).toHaveTextContent("Rev No.'00'");
  });
});
