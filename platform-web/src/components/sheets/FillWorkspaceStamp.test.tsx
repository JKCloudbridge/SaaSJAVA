import { configure, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi } from "@/test/fakeApi";
import { FILL_OWNER, newServer, reviewerFill } from "@/test/reviewerFill";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillWorkspace } from "./FillWorkspace";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

/**
 * The stamp that closes a fill-in's sheet (SH5.2): the number and Revision, the publish day when the page knows it, and who
 * sent it and when once it was sent. Everyone who sees the sheet sees it, in the grid view, the form view and View; it is
 * read only and is not a cell. The words are made by `lib/sheets/revision.ts`; the workspace only draws them.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

const STAMP = (versionNo: number) =>
  versionNo === 3 ? "F/MC/10  Rev No.'02'\nPublish date: 13-Nov-2026" : "F/MC/10  Rev No.'00'";

async function open(editAs: string | null, fill: Record<string, unknown> = {}) {
  fakeApi(reviewerFill(newServer(), { editAs, canEdit: editAs !== null, fill }));
  render(<FillWorkspace owner={FILL_OWNER} label="Fire checks" footer={STAMP} />);
  await screen.findByRole("grid");
}

describe("the stamp of a fill-in that was not sent", () => {
  it("shows the number, the Revision and the publish day in the grid, and nothing about who sent it", async () => {
    await open("FILLER", { status: "DRAFT" });

    expect(screen.getByTestId("sheet-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.getByTestId("sheet-footer-2")).toHaveTextContent("Publish date: 13-Nov-2026");
    expect(screen.queryByTestId("sheet-footer-3")).not.toBeInTheDocument();
  });

  it("shows the same in the form view", async () => {
    await open("FILLER", { status: "DRAFT" });

    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));

    const note = screen.getByTestId("form-footer");
    expect(note).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(note.textContent).toContain("Publish date: 13-Nov-2026");
    expect(note.textContent).not.toContain("Submitted");
  });
});

describe("the stamp of a fill-in that was sent", () => {
  const sent = { submittedAt: "2026-11-17T10:00:00Z", submittedBy: "Plant head 1" };

  it("is the same for the reviewer, with who sent it and when below the number", async () => {
    await open("REVIEWER", sent);

    expect(screen.getByTestId("sheet-footer-3")).toHaveTextContent("Submitted by Plant head 1 on 17-Nov-2026");
    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));
    expect(screen.getByTestId("form-footer").textContent).toContain("Submitted by Plant head 1 on 17-Nov-2026");
  });

  it("is the same for a person who may only read it, and shows the View page and its print drawing the same lines", async () => {
    await open(null, sent);

    expect(screen.getByTestId("sheet-footer-3")).toHaveTextContent("Submitted by Plant head 1 on 17-Nov-2026");
    fireEvent.click(screen.getByTestId("fill-view"));
    const page = await screen.findByTestId("fill-view-page");
    expect(within(page).getByTestId("sheet-footer-3")).toHaveTextContent("Submitted by Plant head 1 on 17-Nov-2026");
    fireEvent.click(within(page).getByRole("button", { name: "Print" }));
    expect(screen.getByTestId("sheet-print-footer")).toHaveTextContent("F/MC/10 Rev No.'02'");
    expect(screen.getByTestId("sheet-print-footer-2")).toHaveTextContent("Publish date: 13-Nov-2026");
    expect(screen.getByTestId("sheet-print-footer-3")).toHaveTextContent("Submitted by Plant head 1 on 17-Nov-2026");
  });

  it("keeps the day alone when the person who sent it has left", async () => {
    await open("REVIEWER", { submittedAt: "2026-11-17T10:00:00Z" });

    expect(screen.getByTestId("sheet-footer-3")).toHaveTextContent("Submitted on 17-Nov-2026");
  });

  it("is not a cell: the footer cannot be typed into", async () => {
    await open("REVIEWER", sent);

    expect(screen.getByTestId("sheet-footer").closest("[data-r]")).toBeNull();
    expect(screen.getByTestId("sheet-footer").querySelector("input, textarea")).toBeNull();
  });
});

describe("a sheet with no number yet", () => {
  it("has no stamp at all before it is sent", async () => {
    fakeApi(reviewerFill(newServer(), { editAs: "FILLER", fill: { status: "DRAFT" } }));
    render(<FillWorkspace owner={FILL_OWNER} label="Fire checks" footer="" />);
    await screen.findByRole("grid");

    expect(screen.queryByTestId("sheet-footer")).not.toBeInTheDocument();
  });
});
