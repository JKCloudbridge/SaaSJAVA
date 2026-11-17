import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { VersionSummary } from "@/lib/sheets/api";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { UploadSheet } from "./UploadSheet";

configure({ asyncUtilTimeout: 5000 });

const OWNER = { object: "Subject", id: "0198f5c0-0000-7000-8000-000000000001" };
const IMPORT = `POST /api/v1/sheets/${OWNER.object}/${OWNER.id}/versions/import`;

afterEach(() => {
  vi.unstubAllGlobals();
});

const DRAFT: VersionSummary = {
  versionNo: 3,
  status: "DRAFT",
  dimension: 2,
  rowsMode: "FIXED",
  gridBytes: 500,
  gridHash: "a".repeat(64),
  version: 0,
  createdAt: "2026-10-22T08:00:00Z",
  updatedAt: "2026-10-22T09:00:00Z",
  reviewEdits: 0,
  held: false,
};

const REPORT = {
  columns: 12,
  rows: 80,
  mergedAreas: 3,
  notes: [
    { code: "images", count: 2, leftOut: true, words: "2 images were left out." },
    { code: "dates", count: 1, leftOut: false, words: "1 date was written year-month-day." },
  ],
};

function pick(name: string, size = 20) {
  const file = new File([new Uint8Array(size)], name);
  fireEvent.change(screen.getByLabelText("Choose a spreadsheet file"), { target: { files: [file] } });
  return file;
}

function draw(props: Partial<Parameters<typeof UploadSheet>[0]> = {}) {
  const onSaved = vi.fn();
  render(<UploadSheet owner={OWNER} label="Upload a spreadsheet" onSaved={onSaved} {...props} />);
  return onSaved;
}

describe("the report behind More information (SH_UI)", () => {
  const preview = () => fakeApi({ [IMPORT]: () => json({ data: { saved: false, report: REPORT } }) });

  it("shows only the checkbox and the buttons at first, with a short line that says how many things to check", async () => {
    preview();
    draw();

    pick("Fire checks.xlsx");

    const more = await screen.findByRole("button", { name: "More information" });
    expect(more).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByTestId("upload-to-check")).toHaveTextContent("2 things to check");
    expect(screen.getByTestId("upload-report")).not.toBeVisible();
    expect(screen.getByRole("checkbox", { name: "The first row holds the names of the columns" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save as a new draft" })).toBeEnabled();
  });

  it("opens the report on the button and says which part it controls, and closes it again", async () => {
    preview();
    draw();
    pick("Fire checks.xlsx");

    const more = await screen.findByRole("button", { name: "More information" });
    fireEvent.click(more);

    expect(more).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByTestId("upload-report")).toBeVisible();
    expect(more.getAttribute("aria-controls")).toBe(screen.getByTestId("upload-report").id);
    expect(screen.getByText("2 images were left out.")).toBeVisible();
    fireEvent.click(more);
    expect(screen.getByTestId("upload-report")).not.toBeVisible();
  });

  it("starts closed again when another file is chosen", async () => {
    preview();
    draw();
    pick("One.xlsx");
    fireEvent.click(await screen.findByRole("button", { name: "More information" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    pick("Two.xlsx");

    expect(await screen.findByRole("button", { name: "More information" })).toHaveAttribute("aria-expanded", "false");
  });

  it("shows a refusal at once, in the API's words, with no report to open", async () => {
    fakeApi({ [IMPORT]: () => refusal("VALIDATION_ERROR", "The file has too many columns.", 400) });
    draw();

    pick("Wide.xlsx");

    expect(await screen.findByRole("alert")).toHaveTextContent("The file has too many columns.");
    expect(screen.queryByRole("button", { name: "More information" })).toBeNull();
  });

  it("says nothing to check when the report has nothing to look at", async () => {
    fakeApi({ [IMPORT]: () => json({ data: { saved: false, report: { ...REPORT, notes: [] } } }) });
    draw();

    pick("Clean.xlsx");

    await screen.findByRole("button", { name: "More information" });
    expect(screen.queryByTestId("upload-to-check")).toBeNull();
  });
});

describe("the upload of a spreadsheet", () => {
  it("previews what the file gives, says what was left out and saves only when asked", async () => {
    const calls = fakeApi({
      [IMPORT]: (request) =>
        new URL(request.url).searchParams.get("preview") === "true"
          ? json({ data: { saved: false, report: REPORT } })
          : json({ data: { saved: true, version: DRAFT, report: REPORT } }, 201),
    });
    const onSaved = draw();

    pick("Fire checks.xlsx");

    expect(await screen.findByText(/The file gives 12 columns, 80 rows, 3 merged areas\./)).toBeInTheDocument();
    expect(screen.getByText("2 images were left out.")).toBeInTheDocument();
    expect(screen.getByText("1 date was written year-month-day.")).toBeInTheDocument();
    expect(calls.filter((call) => call.key === IMPORT)).toHaveLength(1);
    expect(onSaved).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(DRAFT));
    expect(calls.filter((call) => call.key === IMPORT)).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Save as a new draft" })).toBeNull();
  });

  it("sends the file as it is, with its kind and the choice about the first row, never its name", async () => {
    const urls: string[] = [];
    fakeApi({
      [IMPORT]: (request) => {
        urls.push(request.url);
        expect(request.headers.get("content-type")).toBe("application/octet-stream");
        return json({ data: { saved: false, report: REPORT } });
      },
    });
    draw();

    pick("Fire checks.csv");
    await screen.findByText(/The file gives/);
    fireEvent.click(screen.getByRole("checkbox", { name: "The first row holds the names of the columns" }));
    await waitFor(() => expect(urls).toHaveLength(2));

    expect(urls[0]).toContain("format=csv");
    expect(urls[0]).not.toContain("firstRowLabels");
    expect(urls[1]).toContain("firstRowLabels=false");
    expect(urls[0]).not.toContain("Fire");
    expect(urls[0]).toContain("preview=true");
  });

  it("starts the box unticked when the server says the first row is a title, and says so in words", async () => {
    const urls: string[] = [];
    fakeApi({
      [IMPORT]: (request) => {
        urls.push(request.url);
        const labels = new URL(request.url).searchParams.get("firstRowLabels");
        const ticked = labels === null ? false : labels === "true";
        return new URL(request.url).searchParams.get("preview") === "true"
          ? json({
              data: {
                saved: false,
                report: {
                  ...REPORT,
                  firstRowLabels: ticked,
                  firstRowIsTitle: true,
                  notes: [
                    ...REPORT.notes,
                    ...(ticked
                      ? []
                      : [
                          {
                            code: "title_row",
                            count: 1,
                            leftOut: false,
                            words: "1 title row was found (it is kept as the first row of the sheet and does not name a column).",
                          },
                        ]),
                  ],
                },
              },
            })
          : json({ data: { saved: true, version: DRAFT, report: REPORT } }, 201);
      },
    });
    const onSaved = draw();

    pick("Form.xlsx");

    const box = await screen.findByRole("checkbox", { name: "The first row holds the names of the columns" });
    expect(await screen.findByText(/The first row looks like a title\./)).toBeInTheDocument();
    expect(box).not.toBeChecked();
    expect(screen.getByText(/1 title row was found/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(DRAFT));
    expect(urls[urls.length - 1]).toContain("preview=false");
    expect(urls[urls.length - 1]).toContain("firstRowLabels=false");
  });

  it("lets the person tick the box for a file with a title, and asks the server again", async () => {
    const urls: string[] = [];
    fakeApi({
      [IMPORT]: (request) => {
        urls.push(request.url);
        const labels = new URL(request.url).searchParams.get("firstRowLabels");
        return json({
          data: { saved: false, report: { ...REPORT, firstRowLabels: labels === "true", firstRowIsTitle: true } },
        });
      },
    });
    draw();

    pick("Form.xlsx");
    const box = await screen.findByRole("checkbox", { name: "The first row holds the names of the columns" });
    await screen.findByText(/The first row looks like a title\./);
    fireEvent.click(box);

    await waitFor(() => expect(urls).toHaveLength(2));
    expect(urls[1]).toContain("firstRowLabels=true");
    expect(await screen.findByText(/Because the box is ticked it will name the first column\./)).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "The first row holds the names of the columns" })).toBeChecked();
  });

  it("keeps the box ticked and says nothing about a title for a file without one", async () => {
    fakeApi({
      [IMPORT]: () => json({ data: { saved: false, report: { ...REPORT, firstRowLabels: true, firstRowIsTitle: false } } }),
    });
    draw();

    pick("Plain.xlsx");

    const box = await screen.findByRole("checkbox", { name: "The first row holds the names of the columns" });
    await screen.findByText(/The file gives/);
    expect(box).toBeChecked();
    expect(screen.queryByText(/looks like a title/)).toBeNull();
  });

  it("keeps the box ticked when an older server answers without the new fields", async () => {
    fakeApi({ [IMPORT]: () => json({ data: { saved: false, report: REPORT } }) });
    draw();

    pick("Old.xlsx");

    await screen.findByText(/The file gives/);
    expect(screen.getByRole("checkbox", { name: "The first row holds the names of the columns" })).toBeChecked();
  });

  it("shows the server's own words for a refusal, and offers no save", async () => {
    fakeApi({
      [IMPORT]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is invalid.",
              fields: { file: ["Row 12, column C has a text longer than the allowed 1000 characters."] },
            },
          },
          400,
        ),
    });
    draw();

    pick("big.xlsx");

    expect(await screen.findByRole("alert")).toHaveTextContent("Row 12, column C has a text longer");
    expect(screen.getByRole("button", { name: "Save as a new draft" })).toBeDisabled();
  });

  it("shows a refusal at the save and keeps the dialog open so the person can go on or cancel", async () => {
    fakeApi({
      [IMPORT]: (request) =>
        new URL(request.url).searchParams.get("preview") === "true"
          ? json({ data: { saved: false, report: REPORT } })
          : refusal("CONFLICT", "A draft already exists: it is Revision 01. Continue with it.", 409),
    });
    const onSaved = draw();
    pick("a.xlsx");
    await screen.findByText(/The file gives/);

    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("A draft already exists: it is Revision 01.");
    expect(onSaved).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("does not send a file of a kind it does not read, or one over the size the API states", async () => {
    const calls = fakeApi();
    draw({ maxBytes: 100 });

    pick("old.xls");
    expect(await screen.findByRole("alert")).toHaveTextContent(/\.xlsx/);
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    pick("big.csv", 101);
    expect(await screen.findByRole("alert")).toHaveTextContent("larger than the allowed 100 bytes");

    expect(calls.filter((call) => call.key === IMPORT)).toHaveLength(0);
  });

  it("tells a person who starts the first revision that the sheet will name its rows", async () => {
    fakeApi({ [IMPORT]: () => json({ data: { saved: false, report: REPORT } }) });
    draw({ firstVersion: true, label: "Start from a spreadsheet" });

    pick("a.xlsx");

    expect(await screen.findByText(/This sheet will name its rows/)).toBeInTheDocument();
  });

  it("offers the visible worksheets, asks again for the one chosen and saves that one", async () => {
    const urls: string[] = [];
    const worksheets = [
      { index: 0, title: "Notes", rows: 10, columns: 3 },
      { index: 1, title: "Data", rows: 60, columns: 22 },
    ];
    fakeApi({
      [IMPORT]: (request) => {
        urls.push(request.url);
        const sheet = new URL(request.url).searchParams.get("worksheet");
        const report = {
          ...REPORT,
          worksheets,
          worksheet: sheet === null ? 0 : Number(sheet),
          embeddedFilePlaces: [{ row: 12, column: "B" }],
        };
        return new URL(request.url).searchParams.get("preview") === "true"
          ? json({ data: { saved: false, report } })
          : json({ data: { saved: true, version: DRAFT, report } }, 201);
      },
    });
    const onSaved = draw();

    pick("Workbook.xlsx");
    const choice = await screen.findByRole("combobox");
    expect(screen.getByRole("option", { name: "Data (about 60 rows, 22 columns)" })).toBeInTheDocument();
    expect(screen.getByTestId("upload-embedded-places")).toHaveTextContent("B12");
    expect(urls[0]).not.toContain("worksheet=");

    fireEvent.change(choice, { target: { value: "1" } });
    await waitFor(() => expect(urls.some((url) => url.includes("worksheet=1"))).toBe(true));
    await screen.findByText(/The file gives/);
    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(DRAFT));
    expect(urls[urls.length - 1]).toContain("worksheet=1");
    expect(urls[urls.length - 1]).toContain("preview=false");
  });

  it("offers no choice for a workbook with one visible worksheet", async () => {
    fakeApi({
      [IMPORT]: () =>
        json({ data: { saved: false, report: { ...REPORT, worksheets: [{ index: 0, title: "Only", rows: 5, columns: 2 }] } } }),
    });
    draw();

    pick("Single.xlsx");
    await screen.findByText(/The file gives/);

    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("is switched off when the screen says so", () => {
    draw({ disabled: true });

    expect(screen.getByRole("button", { name: "Upload a spreadsheet" })).toBeDisabled();
  });
});
