import { configure, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import type { VersionSummary } from "@/lib/sheets/api";
import { fakeApi, json } from "@/test/fakeApi";
import { sizeTheGrid } from "@/test/virtualDom";
import { SheetWorkspace } from "./SheetWorkspace";

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const BASE = `/api/v1/sheets/${OWNER.object}/${OWNER.id}/versions`;

afterEach(() => {
  vi.restoreAllMocks();
});

function summary(versionNo: number, status: string): VersionSummary {
  return {
    versionNo,
    status,
    dimension: 2,
    rowsMode: "FIXED",
    gridBytes: 1234,
    gridHash: "a".repeat(64),
    version: 0,
    createdAt: "2026-10-22T08:00:00Z",
    updatedAt: "2026-10-22T09:00:00Z",
    reviewEdits: 0,
    held: false,
  };
}

function serve(canChange = false) {
  fakeApi({
    [`GET ${BASE}`]: () => json({ data: { versions: [summary(2, "ACTIVE")], canChange, limits: LIMITS } }),
    [`GET ${BASE}/2`]: () => json({ data: { version: summary(2, "ACTIVE"), grid: sampleDoc() } }),
  });
}

// View and the footer row on the template sheet (FA7, ADR-0201).
describe("View on the sheet", () => {
  it("is offered to a person who can only read, and opens the print view of the revision that is open", async () => {
    serve();
    render(<SheetWorkspace owner={OWNER} label="Fire checks" footer="F/MR/16  Rev No.'01'" />);

    fireEvent.click(await screen.findByTestId("sheet-view"));

    const view = await screen.findByRole("dialog", { name: "View of Fire checks, revision 01" });
    expect(within(view).getByTestId("sheet-print-footer").textContent).toBe("F/MR/16  Rev No.'01'");
    expect(within(view).getByText("Fire exit")).toBeInTheDocument();
  });

  it("uses the print dialog of the browser from its Print button and closes again", async () => {
    serve();
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    render(<SheetWorkspace owner={OWNER} label="Fire checks" />);

    fireEvent.click(await screen.findByTestId("sheet-view"));
    fireEvent.click(await screen.findByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByTestId("sheet-print-view")).toBeNull();
  });

  it("draws the footer row in the grid, asking for the revision that is open when the caller gives a function", async () => {
    serve();
    const footer = vi.fn((shown: number) => `F/MR/16  Rev No.'0${shown - 1}'`);
    render(<SheetWorkspace owner={OWNER} label="Fire checks" footer={footer} />);

    expect(await screen.findByTestId("sheet-footer")).toHaveTextContent("Rev No.'01'");
    expect(footer).toHaveBeenCalledWith(2);
  });

  it("draws no footer row when the caller gives none, and still offers View", async () => {
    serve();
    render(<SheetWorkspace owner={OWNER} label="Fire checks" />);

    await screen.findByRole("grid");
    expect(screen.queryByTestId("sheet-footer")).toBeNull();
    expect(screen.getByTestId("sheet-view")).toBeInTheDocument();
  });

  it("puts the buttons the caller offers beside View, for the revision that is open", async () => {
    serve();
    const actions = vi.fn(({ versionNo }: { versionNo: number }) => <button type="button">Download {versionNo}</button>);
    render(<SheetWorkspace owner={OWNER} label="Fire checks" actions={actions} />);

    expect(await screen.findByRole("button", { name: "Download 2" })).toBeInTheDocument();
    expect(screen.getByTestId("sheet-view")).toBeInTheDocument();
  });

  it("shows the footer as a closing line of the form view too, and nothing while it is blank", async () => {
    serve();
    const { unmount } = render(<SheetWorkspace owner={OWNER} label="Fire checks" footer="F/MR/16  Rev No.'01'" />);
    await screen.findByRole("grid");
    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));
    expect(await screen.findByTestId("form-footer")).toHaveTextContent("Rev No.'01'");
    unmount();

    serve();
    render(<SheetWorkspace owner={OWNER} label="Fire checks" footer="" />);
    await screen.findByRole("grid");
    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));
    await screen.findByRole("tabpanel");
    expect(screen.queryByTestId("form-footer")).toBeNull();
  });
});
