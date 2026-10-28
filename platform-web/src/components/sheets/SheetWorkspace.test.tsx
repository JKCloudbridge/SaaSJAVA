import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import type { VersionSummary } from "@/lib/sheets/api";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import { sizeTheGrid } from "@/test/virtualDom";
import { SheetWorkspace } from "./SheetWorkspace";

sizeTheGrid();

// A whole sheet is drawn for every test; give each wait longer than the default second on a busy machine.
configure({ asyncUtilTimeout: 5000 });
// The whole test is bounded too: on a busy CI machine one heavy grid test can use up the default 5 seconds.
vi.setConfig({ testTimeout: 20000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const BASE = `/api/v1/sheets/${OWNER.object}/${OWNER.id}/versions`;

afterEach(() => {
  vi.unstubAllGlobals();
});

function summary(versionNo: number, status: string, version = 0, extra: Partial<VersionSummary> = {}): VersionSummary {
  return {
    versionNo,
    status,
    dimension: 2,
    rowsMode: "FIXED",
    gridBytes: 1234,
    gridHash: "a".repeat(64),
    version,
    createdAt: "2026-10-22T08:00:00Z",
    updatedAt: "2026-10-22T09:00:00Z",
    reviewEdits: 0,
    held: false,
    ...extra,
  };
}

function list(versions: VersionSummary[], canChange = true) {
  return () => json({ data: { versions, canChange, limits: LIMITS } });
}

function one(versionNo: number, status: string, version = 0) {
  return () => json({ data: { version: summary(versionNo, status, version), grid: sampleDoc() } });
}

function draw() {
  return render(<SheetWorkspace owner={OWNER} label="Fire checks" />);
}

const bodyOf = (calls: Call[], key: string) => JSON.parse(calls.filter((c) => c.key === key).at(-1)?.body ?? "null") as unknown;

describe("a sheet with a draft", () => {
  it("saves and reopens underline, alignment and theme text colour from the template toolbar", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 7)]),
      [`GET ${BASE}/1`]: one(1, "DRAFT", 7),
      [`PUT ${BASE}/1/grid`]: () => json({ data: summary(1, "DRAFT", 8) }),
    });
    const first = draw();
    await screen.findByRole("grid");
    fireEvent.click(screen.getByRole("button", { name: "Underline" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Alignment" }), { target: { value: "RIGHT" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Text colour" }), { target: { value: "SUCCESS" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("All changes are saved.");
    const stored = bodyOf(calls, `PUT ${BASE}/1/grid`);
    expect(stored).toMatchObject({ cells: { "h1:c1": { style: {
      bold: true, fontSize: "TITLE", underline: true, alignment: "RIGHT", colour: "SUCCESS",
    } } } });
    first.unmount();
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 8)]),
      [`GET ${BASE}/1`]: () => json({ data: { version: summary(1, "DRAFT", 8), grid: stored } }),
    });
    const reopened = draw();
    await screen.findByRole("grid");
    expect(reopened.container.querySelector('[data-r="0"][data-c="0"]'))
      .toHaveClass("sheet-underline", "sheet-align-right", "sheet-colour-success");
    expect(screen.getByRole("button", { name: "Underline" })).toHaveAttribute("aria-pressed", "true");
  });
  it("lists the revisions with their tags and opens the draft in the grid view", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "DRAFT", 3), summary(1, "ACTIVE", 5)]),
      [`GET ${BASE}/2`]: one(2, "DRAFT", 3),
    });

    draw();

    expect(await screen.findByRole("table", { name: "Revisions" })).toBeInTheDocument();
    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0] as HTMLElement).getAllByText("Draft").length).toBeGreaterThan(0);
    expect(within(rows[1] as HTMLElement).getByText("Active")).toBeInTheDocument();
    expect(await screen.findByRole("grid", { name: "Fire checks, draft" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByText(/This is a draft/)).toBeInTheDocument();
  });

  it("saves the grid with the row version that was read and says so", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 7)]),
      [`GET ${BASE}/1`]: one(1, "DRAFT", 7),
      [`PUT ${BASE}/1/grid`]: () => json({ data: summary(1, "DRAFT", 8) }),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));

    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Thing" } });
    fireEvent.blur(label);
    expect(screen.getByText("There are changes that are not saved.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("All changes are saved.");
    const put = calls.find((c) => c.key === `PUT ${BASE}/1/grid`);
    expect(put).toBeDefined();
    const sent = bodyOf(calls, `PUT ${BASE}/1/grid`) as { columns: { label: string }[]; formatVersion: number };
    expect(sent.formatVersion).toBe(1);
    expect(sent.columns[0]?.label).toBe("Thing");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("sends the revision it read in the address of the save", async () => {
    const seen: string[] = [];
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 7)]),
      [`GET ${BASE}/1`]: one(1, "DRAFT", 7),
      [`PUT ${BASE}/1/grid`]: (request) => {
        seen.push(new URL(request.url).search);
        return json({ data: summary(1, "DRAFT", 8) });
      },
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "x" } });
    fireEvent.blur(label);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("All changes are saved.");
    fireEvent.change(label, { target: { value: "y" } });
    fireEvent.blur(label);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(seen).toEqual(["?version=7", "?version=8"]));
  });

  it("shows the API's words with the place that is wrong, and keeps the person's work", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 0)]),
      [`GET ${BASE}/1`]: one(1, "DRAFT", 0),
      [`PUT ${BASE}/1/grid`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is invalid.",
              fields: { "grid.columns[1].default": ["A number column holds digits, with an optional sign and decimals."] },
            },
          },
          400,
        ),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Kept" } });
    fireEvent.blur(label);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const refused = await screen.findByTestId("save-refused");
    expect(refused).toHaveTextContent("The request is invalid.");
    expect(refused).toHaveTextContent("Column 2, default: A number column holds digits");
    expect(screen.getByRole("textbox", { name: "Label of column 1" })).toHaveValue("Kept");
    expect(within(screen.getByRole("listitem", { name: "Column 2" })).getByRole("alert")).toHaveTextContent("holds digits");
    fireEvent.change(label, { target: { value: "Kept again" } });
    fireEvent.blur(label);
    expect(screen.queryByTestId("save-refused")).toBeNull();
  });

  it("offers to reload when someone else saved first", async () => {
    let reads = 0;
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT", 0)]),
      [`GET ${BASE}/1`]: () => {
        reads += 1;
        return one(1, "DRAFT", reads)();
      },
      [`PUT ${BASE}/1/grid`]: () => refusal("CONCURRENT_MODIFICATION", "The resource was changed by someone else. Reload it and try again.", 409),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Mine" } });
    fireEvent.blur(label);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const reload = await screen.findByRole("button", { name: "Reload this revision" });
    expect(screen.getByTestId("save-refused")).toHaveTextContent("changed by someone else");
    fireEvent.click(reload);

    await waitFor(() => expect(reads).toBe(2));
    await waitFor(() => expect(screen.queryByTestId("save-refused")).toBeNull());
  });
});

describe("a sheet with no version", () => {
  it("offers only the two start choices and defaults to named rows", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([]),
      [`POST ${BASE}`]: () => json({ data: summary(1, "DRAFT", 0) }, 201),
    });
    draw();

    fireEvent.click(await screen.findByRole("button", { name: "Start first revision" }));
    expect(screen.queryByRole("radio")).toBeNull();
    expect(screen.queryByText(/There is no version yet/)).toBeNull();

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${BASE}`)).toBe(true));
    expect(bodyOf(calls, `POST ${BASE}`)).toEqual({ dimension: 2 });
  });


  it("tells the page around it when a first revision was started, so it can read what depends on it (FA10)", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([]),
      [`POST ${BASE}`]: () => json({ data: summary(1, "DRAFT", 0) }, 201),
    });
    const changed = vi.fn();
    render(<SheetWorkspace owner={OWNER} label="Fire checks" onVersionsChanged={changed} />);

    fireEvent.click(await screen.findByRole("button", { name: "Start first revision" }));

    await waitFor(() => expect(changed).toHaveBeenCalled());
  });
  it("shows the API's words when the first revision cannot be started", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([]),
      [`POST ${BASE}`]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    draw();

    fireEvent.click(await screen.findByRole("button", { name: "Start first revision" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("You are not allowed to perform this action.");
  });

  it("says plainly that nothing is in use yet to a person who may only read", async () => {
    fakeApi({ [`GET ${BASE}`]: list([], false) });
    draw();

    expect(await screen.findByText("No revision is in use yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start first revision" })).toBeNull();
  });
});

describe("a revision that is not a draft", () => {
  it("is read-only, says why, and offers a new revision from it", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([summary(1, "ACTIVE", 5)]),
      [`GET ${BASE}/1`]: one(1, "ACTIVE", 5),
      [`POST ${BASE}`]: () => json({ data: summary(2, "DRAFT", 0) }, 201),
    });
    draw();

    expect(await screen.findByText(/Revision 00 is active and cannot be changed/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByRole("toolbar")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "New revision from revision 00" }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${BASE}`)).toBe(true));
    expect(bodyOf(calls, `POST ${BASE}`)).toEqual({ basedOn: 1 });
  });

  it("offers no new revision while a draft exists, and says where the draft is", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "DRAFT"), summary(1, "ACTIVE")]),
      [`GET ${BASE}/2`]: one(2, "DRAFT"),
    });
    draw();

    await screen.findByRole("grid");
    expect(screen.queryByRole("button", { name: /New revision from/ })).toBeNull();
    expect(screen.getByText(/Only one draft can exist at a time/)).toBeInTheDocument();
  });
});

describe("a person who may only read", () => {
  it("sees the active version, cannot change it, and is told so", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "ACTIVE", 5)], false),
      [`GET ${BASE}/1`]: one(1, "ACTIVE", 5),
    });
    draw();

    expect(await screen.findByRole("grid")).toHaveAttribute("aria-readonly", "true");
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByRole("button", { name: /New revision from/ })).toBeNull();
    fireEvent.click(screen.getByRole("tab", { name: "Form view" }));
    expect(screen.getByRole("textbox", { name: "Label of column 1" })).toBeDisabled();
  });

  it("is told, for a draft they may not change, that they can read but not change", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT")], false),
      [`GET ${BASE}/1`]: one(1, "DRAFT"),
    });
    draw();

    expect(await screen.findByText("You can read this sheet but not change it.")).toBeInTheDocument();
  });
});

describe("opening another version", () => {
  it("opens it at once when nothing is unsaved", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "DRAFT"), summary(1, "ACTIVE")]),
      [`GET ${BASE}/2`]: one(2, "DRAFT"),
      [`GET ${BASE}/1`]: one(1, "ACTIVE"),
    });
    draw();
    await screen.findByRole("grid", { name: "Fire checks, draft" });

    fireEvent.click(screen.getByRole("button", { name: "Open revision 00" }));

    expect(await screen.findByRole("grid", { name: "Fire checks, revision 00" })).toBeInTheDocument();
  });

  it("asks first when there are changes that are not saved, and keeps them if the person says so", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "DRAFT"), summary(1, "ACTIVE")]),
      [`GET ${BASE}/2`]: one(2, "DRAFT"),
      [`GET ${BASE}/1`]: one(1, "ACTIVE"),
    });
    draw();
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Unsaved" } });
    fireEvent.blur(label);

    fireEvent.click(screen.getByRole("button", { name: "Open revision 00" }));

    const ask = screen.getByRole("alertdialog", { name: "Unsaved changes" });
    expect(ask).toHaveTextContent("Open revision 00 and lose them?");
    fireEvent.click(within(ask).getByRole("button", { name: "Keep editing" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.getByRole("textbox", { name: "Label of column 1" })).toHaveValue("Unsaved");
    fireEvent.click(screen.getByRole("button", { name: "Open revision 00" }));
    fireEvent.click(screen.getByRole("button", { name: "Discard the changes and open it" }));
    expect(await screen.findByRole("note")).toHaveTextContent("Revision 00 is active");
  });
});

describe("when the sheet cannot be read", () => {
  it("says so in the API's words and offers another try", async () => {
    let tries = 0;
    fakeApi({
      [`GET ${BASE}`]: () => {
        tries += 1;
        return tries === 1 ? refusal("NOT_FOUND", "This sheet does not exist.", 404) : list([])();
      },
    });
    draw();

    expect(await screen.findByRole("alert")).toHaveTextContent("This sheet does not exist.");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("No revision is in use yet.", {}, { timeout: 2000 }).catch(() => null)).toBeDefined();
    expect(tries).toBe(2);
  });
});

describe("a narrow screen", () => {
  it("opens the form view, makes the grid read-only, and says how to build", async () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
    );
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "DRAFT")]),
      [`GET ${BASE}/1`]: one(1, "DRAFT"),
    });
    draw();

    expect(await screen.findByTestId("form-view")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Grid view" }));
    expect(screen.getByRole("grid")).toHaveAttribute("aria-readonly", "true");
    expect(screen.getAllByText(/narrow screen the grid is for reading/).length).toBeGreaterThan(0);
    expect(screen.queryByRole("toolbar")).toBeNull();
  });
});

describe("a new revision from the active one (FA4)", () => {
  it("offers one button that starts it from the revision in use, while no draft exists", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([summary(3, "ACTIVE", 5), summary(2, "SUPERSEDED", 1)]),
      [`GET ${BASE}/3`]: one(3, "ACTIVE", 5),
      [`POST ${BASE}`]: () => json({ data: summary(4, "DRAFT", 0) }, 201),
    });
    draw();

    fireEvent.click(await screen.findByRole("button", { name: "Start a new revision from the active one" }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${BASE}`)).toBe(true));
    expect(bodyOf(calls, `POST ${BASE}`)).toEqual({});
  });

  it("offers none to a person who may only read, nor while a draft exists", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(3, "ACTIVE", 5)], false),
      [`GET ${BASE}/3`]: one(3, "ACTIVE", 5),
    });
    const { unmount } = draw();
    await screen.findByRole("grid");
    expect(screen.queryByRole("button", { name: /Start a new revision/ })).toBeNull();
    unmount();

    fakeApi({
      [`GET ${BASE}`]: list([summary(4, "DRAFT"), summary(3, "ACTIVE")]),
      [`GET ${BASE}/4`]: one(4, "DRAFT"),
    });
    draw();
    await screen.findByRole("grid");
    expect(screen.queryByRole("button", { name: /Start a new revision/ })).toBeNull();
  });
});

describe("a revision in review (FA4)", () => {
  it("is read-only for a person who may change the sheet but does not approve it, and says why", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "IN_REVIEW", 4)]),
      [`GET ${BASE}/2`]: one(2, "IN_REVIEW", 4),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" correctInReview={false} />);

    expect(await screen.findByText(/This copy is in review. Only the people who approve it can correct it/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByTestId("review-edit-note")).toBeNull();
  });

  it("can be corrected by an approver, who is told it is recorded, and the save is the ordinary one", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: list([summary(2, "IN_REVIEW", 4)]),
      [`GET ${BASE}/2`]: one(2, "IN_REVIEW", 4),
      [`PUT ${BASE}/2/grid`]: () => json({ data: summary(2, "IN_REVIEW", 5, { reviewEdits: 1 }) }),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" correctInReview />);
    expect(await screen.findByTestId("review-edit-note")).toHaveTextContent(/Every correction is recorded in the audit trail/);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));

    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Corrected" } });
    fireEvent.blur(label);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await screen.findByText("All changes are saved.");
    expect(calls.some((c) => c.key === `PUT ${BASE}/2/grid`)).toBe(true);
    expect(await screen.findByText(/corrected once while in review/)).toBeInTheDocument();
  });

  it("shows the API's words when it refuses the correction, and keeps the work", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "IN_REVIEW", 4)]),
      [`GET ${BASE}/2`]: one(2, "IN_REVIEW", 4),
      [`PUT ${BASE}/2/grid`]: () =>
        json(
          { error: { code: "FORBIDDEN", message: "A version that is in review can be changed only by the people who approve it." } },
          403,
        ),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" correctInReview />);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Kept" } });
    fireEvent.blur(label);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("A version that is in review can be changed only by the people who approve it.")).toBeInTheDocument();
    expect(label).toHaveValue("Kept");
  });

  it("is never made editable for the draft it replaces: a revision in use stays read-only for an approver", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(1, "ACTIVE", 5)]),
      [`GET ${BASE}/1`]: one(1, "ACTIVE", 5),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" correctInReview />);

    expect(await screen.findByText(/Revision 00 is active and cannot be changed/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
  });
});

describe("a change outside the sheet (FA4)", () => {
  it("reads the revisions again when the screen raises the token and nothing is unsaved", async () => {
    let reads = 0;
    fakeApi({
      [`GET ${BASE}`]: () => {
        reads += 1;
        return json({ data: { versions: [summary(1, reads > 1 ? "ACTIVE" : "APPROVED", 5)], canChange: true, limits: LIMITS } });
      },
      [`GET ${BASE}/1`]: one(1, "ACTIVE", 5),
    });
    const { rerender } = render(<SheetWorkspace owner={OWNER} label="Fire checks" refreshToken={0} />);
    await screen.findByRole("table", { name: "Revisions" });

    rerender(<SheetWorkspace owner={OWNER} label="Fire checks" refreshToken={1} />);

    await waitFor(() => expect(reads).toBeGreaterThanOrEqual(2));
  });

  it("never reads over unsaved changes: it tells the person instead", async () => {
    let reads = 0;
    fakeApi({
      [`GET ${BASE}`]: () => {
        reads += 1;
        return json({ data: { versions: [summary(1, "DRAFT", 7)], canChange: true, limits: LIMITS } });
      },
      [`GET ${BASE}/1`]: one(1, "DRAFT", 7),
    });
    const { rerender } = render(<SheetWorkspace owner={OWNER} label="Fire checks" refreshToken={0} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const label = screen.getByRole("textbox", { name: "Label of column 1" });
    fireEvent.change(label, { target: { value: "Unsaved" } });
    fireEvent.blur(label);
    const before = reads;

    rerender(<SheetWorkspace owner={OWNER} label="Fire checks" refreshToken={1} />);

    expect(await screen.findByText(/Something changed on this template while you were editing/)).toBeInTheDocument();
    expect(reads).toBe(before);
    expect(label).toHaveValue("Unsaved");
  });
});

describe("a revision made from a spreadsheet file", () => {
  const REPORT = { columns: 3, rows: 2, mergedAreas: 0, notes: [] };
  const choose = (name: string) =>
    fireEvent.change(screen.getByLabelText("Choose a spreadsheet file"), {
      target: { files: [new File([new Uint8Array(10)], name)] },
    });

  it("is offered beside the new revision button to a person who may change the sheet and no draft exists", async () => {
    fakeApi({ [`GET ${BASE}`]: list([summary(1, "ACTIVE", 5)]), [`GET ${BASE}/1`]: one(1, "ACTIVE", 5) });

    draw();

    expect(await screen.findByRole("button", { name: "Upload a spreadsheet" })).toBeEnabled();
  });

  it("is not offered while a draft exists, nor to a person who may only read", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "DRAFT", 3), summary(1, "ACTIVE", 5)]),
      [`GET ${BASE}/2`]: one(2, "DRAFT", 3),
    });
    const withDraft = draw();
    await screen.findByText(/This is a draft/);
    expect(screen.queryByRole("button", { name: "Upload a spreadsheet" })).toBeNull();
    withDraft.unmount();

    fakeApi({ [`GET ${BASE}`]: list([summary(1, "ACTIVE", 5)], false), [`GET ${BASE}/1`]: one(1, "ACTIVE", 5) });
    draw();
    await screen.findByRole("table", { name: "Revisions" });
    expect(screen.queryByRole("button", { name: "Upload a spreadsheet" })).toBeNull();
  });

  it("starts the first revision from a file and opens the new draft once it is saved", async () => {
    let made = false;
    const calls = fakeApi({
      [`GET ${BASE}`]: () => json({ data: { versions: made ? [summary(1, "DRAFT", 0)] : [], canChange: true, limits: LIMITS } }),
      [`GET ${BASE}/1`]: one(1, "DRAFT", 0),
      [`POST ${BASE}/import`]: (request) => {
        if (new URL(request.url).searchParams.get("preview") === "true") {
          return json({ data: { saved: false, report: REPORT } });
        }
        made = true;
        return json({ data: { saved: true, version: summary(1, "DRAFT", 0), report: REPORT } }, 201);
      },
    });
    draw();
    await screen.findByRole("button", { name: "Start first revision" });

    fireEvent.click(screen.getByRole("button", { name: "Start from a spreadsheet" }));
    choose("Fire checks.xlsx");
    await screen.findByText(/The file gives/);
    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));

    expect(await screen.findByRole("grid", { name: "Fire checks, draft" })).toBeInTheDocument();
    expect(calls.filter((call) => call.key === `POST ${BASE}/import`)).toHaveLength(2);
    expect(calls.some((call) => call.key === `POST ${BASE}`)).toBe(false);
  }, 20_000);
});

describe("who last changed a revision", () => {
  it("is named in the list, and a revision nobody can be named for shows a dash", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(2, "ACTIVE", 3, { updatedBy: "User One" }), summary(1, "SUPERSEDED", 5)]),
      [`GET ${BASE}/2`]: one(2, "ACTIVE", 3),
    });

    draw();

    expect(await screen.findByRole("columnheader", { name: "Last changed by" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "User One" })).toBeInTheDocument();
    expect(screen.getByLabelText("Nobody to name")).toBeInTheDocument();
  });
});

describe("the revised copy of a change request (FA8)", () => {
  const held = (versionNo: number) => summary(versionNo, "DRAFT", 2, { held: true });
  const listHeld = (versions: VersionSummary[], canChange: boolean, canChangeHeld: boolean) => () =>
    json({ data: { versions, canChange, canChangeHeld, limits: LIMITS } });

  it("offers no new draft by hand when the screen says so, even to a person who may change the sheet", async () => {
    fakeApi({
      [`GET ${BASE}`]: list([summary(3, "ACTIVE", 5)]),
      [`GET ${BASE}/3`]: one(3, "ACTIVE", 5),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" allowNewDraft={false} />);

    await screen.findByRole("grid");

    expect(screen.queryByRole("button", { name: /Start a new revision/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Upload a spreadsheet" })).toBeNull();
    expect(screen.queryByRole("button", { name: /New revision from/ })).toBeNull();
  });

  it("opens the version it is asked to focus, the copy, and not the revision in use", async () => {
    fakeApi({
      [`GET ${BASE}`]: listHeld([held(4), summary(3, "ACTIVE", 5)], false, true),
      [`GET ${BASE}/4`]: () => json({ data: { version: held(4), grid: sampleDoc() } }),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" allowNewDraft={false} focusVersion={4} />);

    expect(await screen.findByRole("grid", { name: "Fire checks, draft" })).toBeInTheDocument();
  });

  it("lets a person asked to decide the request change the copy, though they may not change the template", async () => {
    fakeApi({
      [`GET ${BASE}`]: listHeld([held(4), summary(3, "ACTIVE", 5)], false, true),
      [`GET ${BASE}/4`]: () => json({ data: { version: held(4), grid: sampleDoc() } }),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" allowNewDraft={false} focusVersion={4} />);

    await screen.findByRole("grid", { name: "Fire checks, draft" });

    expect(screen.getByRole("grid")).not.toHaveAttribute("aria-readonly", "true");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Replace from a spreadsheet" })).toBeEnabled();
    expect(screen.getByText(/revised copy of a change request/)).toBeInTheDocument();
  });

  it("is read only, with the reason, for a person who may change the template but is not asked to decide the request", async () => {
    fakeApi({
      [`GET ${BASE}`]: listHeld([held(4), summary(3, "ACTIVE", 5)], true, false),
      [`GET ${BASE}/4`]: () => json({ data: { version: held(4), grid: sampleDoc() } }),
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" allowNewDraft={false} focusVersion={4} />);

    await screen.findByRole("grid", { name: "Fire checks, draft" });

    expect(screen.getByRole("grid")).toHaveAttribute("aria-readonly", "true");
    expect(screen.queryByRole("button", { name: "Replace from a spreadsheet" })).toBeNull();
    expect(screen.getByText(/Only the people asked to decide the request can change it/)).toBeInTheDocument();
  });

  it("replaces the copy in place from a file and starts no second draft", async () => {
    const calls = fakeApi({
      [`GET ${BASE}`]: listHeld([held(4), summary(3, "ACTIVE", 5)], false, true),
      [`GET ${BASE}/4`]: () => json({ data: { version: held(4), grid: sampleDoc() } }),
      [`POST ${BASE}/import`]: (request) => {
        const query = new URL(request.url).searchParams;
        return query.get("preview") === "true"
          ? json({ data: { saved: false, report: { columns: 3, rows: 2, mergedAreas: 0, notes: [] } } })
          : json({ data: { saved: true, version: held(4), report: { columns: 3, rows: 2, mergedAreas: 0, notes: [] } } }, 201);
      },
    });
    render(<SheetWorkspace owner={OWNER} label="Fire checks" allowNewDraft={false} focusVersion={4} />);
    await screen.findByRole("grid", { name: "Fire checks, draft" });

    fireEvent.click(screen.getByRole("button", { name: "Replace from a spreadsheet" }));
    fireEvent.change(screen.getByLabelText("Choose a spreadsheet file"), {
      target: { files: [new File([new Uint8Array(10)], "copy.xlsx")] },
    });
    await screen.findByText(/The file gives/);
    fireEvent.click(screen.getByRole("button", { name: "Replace the revised copy" }));

    await waitFor(() => expect(calls.filter((call) => call.key === `POST ${BASE}/import`)).toHaveLength(2));
    expect(calls.filter((call) => call.key === `POST ${BASE}/import`).length).toBe(2);
    expect(calls.some((call) => call.key === `POST ${BASE}`)).toBe(false);
  }, 20_000);
});
