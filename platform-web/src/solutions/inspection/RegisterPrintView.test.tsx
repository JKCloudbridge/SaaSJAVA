import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import { toApi } from "@/lib/sheets/adapter";
import { RegisterRequestPanel } from "./RegisterPanel";
import { RegisterPrintView } from "./RegisterPrintView";
import type { RegisterView } from "./register";

vi.mock("./RegisterExcelView", () => ({
  RegisterExcelView: ({ label }: { label: string }) => <div data-testid="register-excel">{label}</div>,
}));

const PORTRAIT = {
  paper: "A4",
  orientation: "portrait",
  pageWidthMm: 210,
  pageHeightMm: 297,
  marginLeft: 0.5,
  marginRight: 0.5,
  marginTop: 0.6,
  marginBottom: 0.6,
};

function view(extra: Partial<RegisterView> = {}): RegisterView {
  return {
    version: 1,
    documentName: "Walkway checklist",
    requestNo: "DCN-12",
    requestedOn: "2026-10-25",
    documentNo: "WI/PRD/01",
    revNo: "00",
    changeRequested: "Make the walkway wider",
    requestedBy: "User A",
    canComplete: false,
    grid: toApi(sampleDoc()),
    print: PORTRAIT,
    ...extra,
  } as unknown as RegisterView;
}

describe("the print view of the register (FA10)", () => {
  it("opens a popup from the View button next to Download as Excel, and closes with its button", () => {
    render(<RegisterRequestPanel templateId="t" requestId="q" view={view()} onChanged={() => undefined} />);
    expect(screen.queryByTestId("register-print-popup")).toBeNull();

    fireEvent.click(screen.getByTestId("register-view"));
    expect(screen.getByRole("dialog", { name: "Register" })).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("register-print-close"));
    expect(screen.queryByTestId("register-print-popup")).toBeNull();
  });

  it("closes on Escape", () => {
    render(<RegisterRequestPanel templateId="t" requestId="q" view={view()} onChanged={() => undefined} />);
    fireEvent.click(screen.getByTestId("register-view"));

    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.queryByTestId("register-print-popup")).toBeNull();
  });

  it("draws a page the shape of the paper the file is set for, with the server's margins, and no headings", () => {
    render(<RegisterPrintView view={view()} onClose={() => undefined} />);

    const paper = screen.getByTestId("register-paper");
    expect(paper).toHaveAttribute("data-paper", "A4");
    expect(paper).toHaveAttribute("data-orientation", "portrait");
    expect(paper.style.getPropertyValue("--register-paper-width")).toBe("210mm");
    expect(paper.style.getPropertyValue("--register-paper-ratio")).toBe("210 / 297");
    const margins = screen.getByTestId("register-paper-margins");
    // 0.5 inch of 210 mm and 0.6 inch of the same width, as shares of the page width.
    expect(margins.style.paddingLeft).toBe(`${((0.5 * 25.4) / 210) * 100}%`);
    expect(margins.style.paddingTop).toBe(`${((0.6 * 25.4) / 210) * 100}%`);
    expect(within(paper).queryAllByRole("columnheader")).toHaveLength(0);
    expect(within(paper).queryByText("Column 1")).toBeNull();
  });

  it("follows the orientation the server chose", () => {
    const landscape = { ...PORTRAIT, orientation: "landscape", pageWidthMm: 297, pageHeightMm: 210 };
    render(<RegisterPrintView view={view({ print: landscape } as Partial<RegisterView>)} onClose={() => undefined} />);

    const paper = screen.getByTestId("register-paper");
    expect(paper).toHaveAttribute("data-orientation", "landscape");
    expect(paper.style.getPropertyValue("--register-paper-width")).toBe("297mm");
  });

  it("is a reading: it has nothing to type into and nothing to save", () => {
    render(<RegisterPrintView view={view()} onClose={() => undefined} />);

    const popup = screen.getByTestId("register-print-popup");
    expect(within(popup).queryAllByRole("textbox")).toHaveLength(0);
    expect(within(popup).queryByRole("button", { name: /save/i })).toBeNull();
    expect(within(popup).queryByRole("button", { name: /print/i })).toBeNull();
  });
});
