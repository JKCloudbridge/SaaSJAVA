import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { marksOf, NO_MARKS, type AnnotateContext, type SheetAnnotate } from "@/lib/sheets/annotate";
import type { VersionSummary } from "@/lib/sheets/api";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json } from "@/test/fakeApi";
import { sizeTheGrid } from "@/test/virtualDom";
import { SheetWorkspace } from "./SheetWorkspace";

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const BASE = `/api/v1/sheets/${OWNER.object}/${OWNER.id}/versions`;

afterEach(() => {
  vi.unstubAllGlobals();
});

function summary(versionNo: number, status: string): VersionSummary {
  return {
    versionNo,
    status,
    dimension: 2,
    rowsMode: "FIXED",
    gridBytes: 1234,
    gridHash: "a".repeat(64),
    version: 3,
    createdAt: "2026-10-22T08:00:00Z",
    updatedAt: "2026-10-22T09:00:00Z",
    reviewEdits: 0,
  };
}

function serve(versions: VersionSummary[], canChange: boolean) {
  fakeApi({
    [`GET ${BASE}`]: () => json({ data: { versions, canChange, limits: LIMITS } }),
    ...Object.fromEntries(
      versions.map((one) => [`GET ${BASE}/${one.versionNo}`, () => json({ data: { version: one, grid: sampleDoc() } })]),
    ),
  });
}

/** A caller's panel that shows what the workspace tells it. */
function annotate(versionNo: number, extra: Partial<SheetAnnotate> = {}): SheetAnnotate {
  return {
    versionNo,
    marks: NO_MARKS,
    panel: (context: AnnotateContext) => (
      <div data-testid="panel">
        <p data-testid="cell">{context.places.cell ? context.describe(context.places.cell) : "no cell"}</p>
        <p data-testid="column">{context.places.column ? context.describe(context.places.column) : "no column"}</p>
        <p data-testid="activations">{context.activations}</p>
        <button type="button" onClick={() => context.select({ rowId: "r3", columnId: "c2" })}>
          Go to the third row
        </button>
      </div>
    ),
    ...extra,
  };
}

describe("annotate mode in the workspace (FA5)", () => {
  it("draws the caller's panel under the grid of the version in use, read-only, for a person who may only read", async () => {
    serve([summary(1, "ACTIVE")], false);

    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);

    expect(await screen.findByTestId("panel")).toBeInTheDocument();
    expect(screen.getByRole("grid", { name: "Fire checks, version 1" })).toHaveAttribute("aria-readonly", "true");
    expect(screen.getByText(/You are reading version 1, which is in use/)).toBeInTheDocument();
    expect(screen.getByTestId("cell")).toHaveTextContent("Cell: column “Item”, row number 1");
    expect(screen.getByTestId("column")).toHaveTextContent("Column “Item”");
  });

  it("opens on the grid even on a narrow screen, where the sheet is otherwise for the form view", async () => {
    vi.stubGlobal(
      "matchMedia",
      (query: string) => ({ matches: true, media: query, addEventListener: () => undefined, removeEventListener: () => undefined }),
    );
    serve([summary(1, "ACTIVE")], false);

    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);

    expect(await screen.findByRole("grid", { name: "Fire checks, version 1" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Grid view" })).toHaveAttribute("aria-selected", "true");
  });

  it("stands on the place the active cell is on as the person moves", async () => {
    serve([summary(1, "ACTIVE")], false);
    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);
    const grid = await screen.findByRole("grid", { name: "Fire checks, version 1" });

    fireEvent.keyDown(grid, { key: "ArrowDown" });
    fireEvent.keyDown(grid, { key: "ArrowDown" });
    fireEvent.keyDown(grid, { key: "ArrowRight" });

    expect(screen.getByTestId("cell")).toHaveTextContent("Cell: column “Amount”, row “First”");
  });

  it("lets the panel stand the grid on a place, and counts the presses of Enter", async () => {
    serve([summary(1, "ACTIVE")], false);
    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);
    const grid = await screen.findByRole("grid", { name: "Fire checks, version 1" });

    fireEvent.click(screen.getByRole("button", { name: "Go to the third row" }));
    expect(screen.getByTestId("cell")).toHaveTextContent("Cell: column “Amount”, row “Third”");

    fireEvent.keyDown(grid, { key: "Enter" });
    expect(screen.getByTestId("activations")).toHaveTextContent("1");
  });

  it("marks the places the caller gives", async () => {
    serve([summary(1, "ACTIVE")], false);
    const marks = marksOf([{ rowId: "r1", columnId: "c1" }]);

    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1, { marks })} />);

    await screen.findByTestId("panel");
    await waitFor(() => expect(document.querySelector(".sheet-noted")).not.toBeNull());
  });

  it("draws no panel for any other version than the one named", async () => {
    serve([summary(2, "ACTIVE"), summary(1, "SUPERSEDED")], false);

    // The caller named version 1, but version 2 is the one in use and opens first.
    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);

    await screen.findByRole("grid", { name: "Fire checks, version 2" });
    expect(screen.queryByTestId("panel")).toBeNull();
  });

  it("never draws a panel over a draft", async () => {
    serve([summary(1, "DRAFT")], true);

    render(<SheetWorkspace owner={OWNER} label="Fire checks" annotate={annotate(1)} />);

    await screen.findByRole("grid", { name: "Fire checks, version 1" });
    expect(screen.queryByTestId("panel")).toBeNull();
  });
});
