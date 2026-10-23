import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillWorkspace } from "./FillWorkspace";

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const URL_OF = `/api/v1/sheet-fills/${OWNER.object}/${OWNER.id}`;

afterEach(() => {
  vi.unstubAllGlobals();
});

function summary(extra: Record<string, unknown> = {}) {
  return {
    fillId: OWNER.id,
    ownerObject: "Template",
    ownerId: "0198f5c0-0000-7000-8000-0000000000aa",
    versionNo: 3,
    versionStatus: "ACTIVE",
    status: "DRAFT",
    version: 4,
    rowCount: 0,
    cellsBytes: 28,
    cellsHash: "a".repeat(64),
    corrections: 0,
    createdAt: "2026-10-23T08:00:00Z",
    updatedAt: "2026-10-23T09:00:00Z",
    ...extra,
  };
}

function view(extra: Record<string, unknown> = {}, fill: Record<string, unknown> = {}, grid: unknown = sampleDoc()) {
  return () =>
    json({
      data: {
        fill: summary(fill),
        grid,
        cells: { formatVersion: 1, rows: [] },
        canEdit: true,
        correction: false,
        limits: { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000 },
        ...extra,
      },
    });
}

function draw(onDirty?: (dirty: boolean) => void) {
  return render(<FillWorkspace owner={OWNER} label="Fire checks" onDirty={onDirty} />);
}

const bodyOf = (calls: Call[], key: string) =>
  JSON.parse(calls.filter((call) => call.key === key).at(-1)?.body ?? "null") as unknown;

describe("a fill-in that can be filled in", () => {
  it("shows the status, the version it is pinned to and the grid of that version", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view() });

    draw();

    expect(await screen.findByRole("grid", { name: "Fire checks, version 3" })).toBeInTheDocument();
    expect(screen.getByText(/version 3 of the template/)).toBeInTheDocument();
    expect(screen.getByTestId("fill-status-note")).toHaveTextContent("This fill-in is a draft");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.queryByTestId("fill-version-note")).not.toBeInTheDocument();
  });

  it("saves the cells with the row version that was read and in the form the server writes", async () => {
    const calls = fakeApi({
      [`GET ${URL_OF}`]: view({}, { version: 7 }),
      [`PUT ${URL_OF}/cells`]: () => json({ data: summary({ version: 8, rowCount: 1 }) }),
    });
    const told: boolean[] = [];
    draw((dirty) => told.push(dirty));
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));

    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });
    fireEvent.change(box, { target: { value: "12.5" } });
    fireEvent.blur(box);
    expect(screen.getByText("There are changes that are not saved.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("All changes are saved.");
    const put = calls.find((call) => call.key === `PUT ${URL_OF}/cells`);
    expect(put).toBeDefined();
    expect(bodyOf(calls, `PUT ${URL_OF}/cells`)).toEqual({
      formatVersion: 1,
      rows: [{ id: "r1", cells: { c2: "12.5" } }],
    });
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(told).toContain(true);
    await waitFor(() => expect(told.at(-1)).toBe(false));
  });

  it("sends the row version it read in the address of the save", async () => {
    const seen: string[] = [];
    fakeApi({
      [`GET ${URL_OF}`]: view({}, { version: 7 }),
      [`PUT ${URL_OF}/cells`]: (request) => {
        seen.push(new URL(request.url).search);
        return json({ data: summary({ version: 8 }) });
      },
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });
    fireEvent.change(box, { target: { value: "1" } });
    fireEvent.blur(box);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("All changes are saved.");
    expect(seen).toEqual(["?version=7"]);
  });

  it("shows what the server refused with the place in the sheet's words and keeps the work", async () => {
    fakeApi({
      [`GET ${URL_OF}`]: view(),
      [`PUT ${URL_OF}/cells`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "cells.rows[0].cells.c2": ["A number column holds digits, with an optional sign and decimals."] },
            },
          },
          400,
        ),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });
    fireEvent.change(box, { target: { value: "5" } });
    fireEvent.blur(box);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const alert = await screen.findByTestId("save-refused");
    expect(alert).toHaveTextContent("The request is not valid.");
    expect(alert).toHaveTextContent("First, Amount:");
    expect(alert).toHaveTextContent("A number column holds digits");
    expect(screen.getByText("There are changes that are not saved.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reload this fill-in" })).not.toBeInTheDocument();
  });

  it("offers to reload when the fill-in changed since it was read, and says unsaved work is lost", async () => {
    fakeApi({
      [`GET ${URL_OF}`]: view(),
      [`PUT ${URL_OF}/cells`]: () => refusal("CONCURRENT_MODIFICATION", "This was changed by someone else.", 409),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });
    fireEvent.change(box, { target: { value: "5" } });
    fireEvent.blur(box);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("button", { name: "Reload this fill-in" })).toBeInTheDocument();
    expect(screen.getByText(/unsaved changes are lost/)).toBeInTheDocument();
  });
});

describe("the grid view", () => {
  it("does not open a cell the sheet fixes and says why", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view() });
    const { container } = draw();
    await screen.findByRole("grid");

    // Display row 2 is the first named row ("First"), column 0 holds the fixed text "Fire exit".
    const cell = container.querySelector('[data-r="2"][data-c="0"]') as HTMLElement;
    fireEvent.doubleClick(cell);

    expect(await screen.findByText("This cell is fixed text and cannot be typed into.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Item/ })).not.toBeInTheDocument();
  });

  it("opens an input cell, takes the typed value and marks the fill-in as changed", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view() });
    const { container } = draw();
    await screen.findByRole("grid");

    fireEvent.doubleClick(container.querySelector('[data-r="2"][data-c="1"]') as HTMLElement);
    const input = await screen.findByRole("textbox", { name: "Amount, First" });
    fireEvent.change(input, { target: { value: "42" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await screen.findByText("There are changes that are not saved.");
    expect(container.querySelector('[data-r="2"][data-c="1"]')).toHaveTextContent("42");
  });

  it("refuses a value that does not fit the column and keeps the box open with the reason", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view() });
    const { container } = draw();
    await screen.findByRole("grid");

    fireEvent.doubleClick(container.querySelector('[data-r="2"][data-c="1"]') as HTMLElement);
    const input = await screen.findByRole("textbox", { name: "Amount, First" });
    fireEvent.change(input, { target: { value: "abc" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(await screen.findByText(/A number column holds digits/)).toBeInTheDocument();
    expect(screen.queryByText("There are changes that are not saved.")).not.toBeInTheDocument();
  });

  it("adds a row for a sheet whose filler adds the rows and draws it below the header", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view({}, {}, oneDimension()) });
    draw();
    await screen.findByRole("grid");

    fireEvent.click(screen.getByRole("button", { name: "Add row" }));

    await screen.findByText("Row added.");
    expect(screen.getByText("There are changes that are not saved.")).toBeInTheDocument();
  });

  it("offers no add row where the sheet names its rows", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view() });
    draw();
    await screen.findByRole("grid");

    expect(screen.queryByRole("button", { name: "Add row" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });
});

describe("a fill-in that cannot be changed", () => {
  it("says it was sent for review, draws no Save and no toolbar, and shows the cells", async () => {
    fakeApi({
      [`GET ${URL_OF}`]: view(
        { canEdit: false, cells: { formatVersion: 1, rows: [{ id: "r1", cells: { c2: "9" } }] } },
        { status: "SUBMITTED", rowCount: 1 },
      ),
    });
    draw();

    expect(await screen.findByTestId("fill-status-note")).toHaveTextContent("sent for review");
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
    expect(screen.getByRole("grid")).toHaveAttribute("aria-readonly", "true");
    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));
    expect(within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByText("9")).toBeInTheDocument();
  });

  it("says that the template moved on and that the fill-in stays on its own version", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view({}, { versionStatus: "SUPERSEDED" }) });
    draw();

    expect(await screen.findByTestId("fill-version-note")).toHaveTextContent("A newer version is in use now");
    expect(screen.getByTestId("fill-version-note")).toHaveTextContent("stays on version 3");
  });

  it("says that a retired template leaves the fill-in readable but fixed", async () => {
    fakeApi({ [`GET ${URL_OF}`]: view({ canEdit: false }, { versionStatus: "RETIRED" }) });
    draw();

    expect(await screen.findByTestId("fill-version-note")).toHaveTextContent("is retired");
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  it("tells a person who may correct a sent fill-in that the correction is recorded", async () => {
    fakeApi({
      [`GET ${URL_OF}`]: view({ correction: true }, { status: "APPROVED", corrections: 2 }),
    });
    draw();

    expect(await screen.findByTestId("fill-correction-note")).toHaveTextContent("recorded in the audit trail");
    expect(screen.getByText(/corrected 2 times after it was sent/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeInTheDocument();
  });
});

describe("a fill-in that cannot be opened", () => {
  it("shows the API's words and offers to try again", async () => {
    fakeApi({ [`GET ${URL_OF}`]: () => refusal("NOT_FOUND", "This fill-in does not exist.", 404) });
    draw();

    expect(await screen.findByRole("alert")).toHaveTextContent("This fill-in does not exist.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("reads again when something outside the cells changed and nothing is unsaved", async () => {
    let reads = 0;
    fakeApi({
      [`GET ${URL_OF}`]: () => {
        reads += 1;
        return view({}, { status: reads === 1 ? "DRAFT" : "SUBMITTED" })();
      },
    });
    const { rerender } = render(<FillWorkspace owner={OWNER} label="Fire checks" refreshToken={0} />);
    await screen.findByRole("grid");

    rerender(<FillWorkspace owner={OWNER} label="Fire checks" refreshToken={1} />);

    await waitFor(() => expect(screen.getByTestId("fill-status-note")).toHaveTextContent("sent for review"));
    expect(reads).toBe(2);
  });
});
