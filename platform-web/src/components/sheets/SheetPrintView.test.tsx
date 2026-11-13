import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { SheetPrintView } from "./SheetPrintView";

afterEach(() => {
  vi.restoreAllMocks();
});

function draw(doc: GridDoc = sampleDoc(), footer = "F/MR/16  Rev No.'01'") {
  const onClose = vi.fn();
  const view = render(<SheetPrintView doc={doc} footer={footer} label="Fire checks, revision 01" onClose={onClose} />);
  return { onClose, ...view };
}

// The print view (FA7, ADR-0201): the sheet the way Ctrl+P shows it, from the sheet already read, with one footer row.
describe("the print view", () => {
  it("is a dialog that draws the label band and the header rows in the head and the rows in the body", () => {
    draw();

    const view = screen.getByRole("dialog", { name: "View of Fire checks, revision 01" });
    const table = within(view).getByRole("table", { name: "Fire checks, revision 01" });
    const head = table.querySelector("thead") as HTMLElement;
    expect(within(head).getAllByRole("columnheader").map((cell) => cell.textContent)).toEqual(
      expect.arrayContaining(["Item", "Amount", "Result", "Inspection"]),
    );
    const body = table.querySelector("tbody") as HTMLElement;
    expect(within(body).getByText("Fire exit")).toBeInTheDocument();
  });

  it("shows the input cells empty", () => {
    draw({ ...sampleDoc(), cells: { ...sampleDoc().cells, "r1:c2": { value: "typed by a filler", kind: "input" } } });

    expect(screen.queryByText("typed by a filler")).toBeNull();
  });

  it("ends with a second footer row, the day, when the footer carries it (MC1)", () => {
    draw(sampleDoc(), "F/MR/16  Rev No.'00'\nDate: 13-Nov-2026");

    expect(screen.getByTestId("sheet-print-footer").textContent).toBe("F/MR/16  Rev No.'00'");
    expect(screen.getByTestId("sheet-print-footer-2").textContent).toBe("Date: 13-Nov-2026");
  });

  it("ends with one footer row across the full width with the number and the revision at its right", () => {
    draw();

    const footer = screen.getByTestId("sheet-print-footer");
    expect(footer.querySelector("td")).toHaveAttribute("colspan", "3");
    expect(footer.querySelector("td")).toHaveClass("sheet-print-right");
    expect(footer).toHaveTextContent("Rev No.'01'");
    expect(footer.textContent).toBe("F/MR/16  Rev No.'01'");
  });

  it("keeps the footer row, blank, before the template is numbered", () => {
    draw(sampleDoc(), "");

    expect(screen.getByTestId("sheet-print-footer").textContent).toBe("");
  });

  it("opens the browser's print dialog from the Print button", () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    draw();

    fireEvent.click(screen.getByRole("button", { name: "Print" }));

    expect(print).toHaveBeenCalledTimes(1);
  });

  it("asks for A4, portrait for a narrow sheet", () => {
    draw();

    expect(screen.getByTestId("sheet-print-view").querySelector("style")?.textContent).toContain("size: A4 portrait");
    expect(screen.getByTestId("sheet-print-view").querySelector(".sheet-print-page")).toHaveClass("sheet-print-portrait");
  });

  it("asks for A4 landscape for a wide sheet", () => {
    draw({ ...sampleDoc(), columns: sampleDoc().columns.map((column) => ({ ...column, width: 400 })) });

    expect(screen.getByTestId("sheet-print-view").querySelector("style")?.textContent).toContain("size: A4 landscape");
    expect(screen.getByTestId("sheet-print-view").querySelector(".sheet-print-page")).toHaveClass("sheet-print-landscape");
  });

  it("draws the page the server describes and works out no orientation of its own (SH3)", () => {
    const print = {
      paper: "A3",
      orientation: "landscape",
      pageWidthMm: 420,
      pageHeightMm: 297,
      marginLeft: 0.5,
      marginRight: 0.5,
      marginTop: 0.6,
      marginBottom: 0.6,
    };
    // The sheet is narrow, so its own width rule would say portrait: the server's description wins.
    render(<SheetPrintView doc={sampleDoc()} print={print} footer="" label="Fire checks" onClose={vi.fn()} />);

    const view = screen.getByTestId("sheet-print-view");
    expect(view.querySelector("style")?.textContent).toContain("size: A3 landscape");
    const page = view.querySelector(".sheet-print-page") as HTMLElement;
    expect(page).toHaveClass("sheet-print-landscape");
    expect(page.style.width).toBe("420mm");
    expect(view).toHaveTextContent("A3, landscape.");
  });

  it("keeps the A4 widths of the tokens for an A4 description", () => {
    const print = {
      paper: "A4",
      orientation: "portrait",
      pageWidthMm: 210,
      pageHeightMm: 297,
      marginLeft: 0.5,
      marginRight: 0.5,
      marginTop: 0.6,
      marginBottom: 0.6,
    };
    render(<SheetPrintView doc={sampleDoc()} print={print} footer="" label="Fire checks" onClose={vi.fn()} />);

    const page = screen.getByTestId("sheet-print-view").querySelector(".sheet-print-page") as HTMLElement;
    expect(page).toHaveClass("sheet-print-portrait");
    expect(page.style.width).toBe("");
  });

  it("makes printing show only itself while it is open, and stops when it is closed", () => {
    const { unmount } = draw();
    expect(document.body).toHaveClass("sheet-printing");

    unmount();

    expect(document.body).not.toHaveClass("sheet-printing");
  });

  it("closes with the Close button and with Escape, and moves the focus to Print when it opens", () => {
    const { onClose } = draw();
    expect(screen.getByRole("button", { name: "Print" })).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.keyDown(document, { key: "Escape" });

    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("keeps Tab between its two buttons", () => {
    draw();
    const close = screen.getByRole("button", { name: "Close" });
    close.focus();

    fireEvent.keyDown(document, { key: "Tab" });

    expect(screen.getByRole("button", { name: "Print" })).toHaveFocus();
  });
});
