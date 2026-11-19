import { configure, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json } from "@/test/fakeApi";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillWorkspace } from "./FillWorkspace";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const URL_OF = `/api/v1/sheet-fills/${OWNER.object}/${OWNER.id}`;

afterEach(() => {
  vi.restoreAllMocks();
});

function serve(status = "DRAFT", canEdit = true) {
  fakeApi({
    [`GET ${URL_OF}`]: () =>
      json({
        data: {
          fill: {
            fillId: OWNER.id,
            ownerObject: "Template",
            ownerId: "0198f5c0-0000-7000-8000-0000000000aa",
            versionNo: 3,
            versionStatus: "ACTIVE",
            status,
            version: 4,
            rowCount: 1,
            cellsBytes: 28,
            cellsHash: "a".repeat(64),
            corrections: 0,
            createdAt: "2026-10-23T08:00:00Z",
            updatedAt: "2026-10-23T09:00:00Z",
          },
          grid: sampleDoc(),
          cells: { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "12.5" } }] },
          canEdit,
          correction: false,
          limits: { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000 },
        },
      }),
  });
}

// The View button on a fill-in opens the View page (SH_6, tested in FillViewPage.test.tsx); Print on that page opens the print
// drawing: the print-style page with what the filler typed in the input cells.
describe("View on a fill-in", () => {
  it("opens the print drawing, from Print on the View page, with the typed values and the footer the caller gives", async () => {
    serve();
    render(<FillWorkspace owner={OWNER} label="Fire checks" footer="F/MR/16  Rev No.'02'" />);

    fireEvent.click(await screen.findByTestId("fill-view"));
    fireEvent.click(await screen.findByRole("button", { name: "Print" }));

    const view = await screen.findByRole("dialog", { name: "View of Fire checks" });
    expect(within(view).getByText("12.5")).toBeInTheDocument();
    expect(within(view).getByText("Fire exit")).toBeInTheDocument();
    expect(within(view).getByTestId("sheet-print-footer").textContent).toBe("F/MR/16  Rev No.'02'");
  });

  it("asks a footer function for the revision the fill-in was made on", async () => {
    serve();
    const footer = vi.fn((versionNo: number) => `rev ${versionNo}`);
    render(<FillWorkspace owner={OWNER} label="Fire checks" footer={footer} />);

    fireEvent.click(await screen.findByTestId("fill-view"));
    fireEvent.click(await screen.findByRole("button", { name: "Print" }));

    expect(await screen.findByTestId("sheet-print-footer")).toHaveTextContent("rev 3");
  });

  it("is offered on a sent fill-in the person cannot edit, prints, closes the drawing and goes back", async () => {
    serve("SUBMITTED", false);
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    render(<FillWorkspace owner={OWNER} label="Fire checks" />);

    fireEvent.click(await screen.findByTestId("fill-view"));
    fireEvent.click(await within(await screen.findByTestId("fill-view-page")).findByRole("button", { name: "Print" }));
    fireEvent.click(await within(await screen.findByTestId("sheet-print-view")).findByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByTestId("sheet-print-view")).toBeNull();

    fireEvent.click(within(screen.getByTestId("fill-view-page")).getByRole("button", { name: "Back" }));
    expect(screen.queryByTestId("fill-view-page")).toBeNull();
  });
});
