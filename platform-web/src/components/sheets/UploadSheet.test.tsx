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
    expect(urls[0]).toContain("firstRowLabels=true");
    expect(urls[1]).toContain("firstRowLabels=false");
    expect(urls[0]).not.toContain("Fire");
    expect(urls[0]).toContain("preview=true");
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
          : refusal("CONFLICT", "A draft already exists: it is version 2. Continue with it.", 409),
    });
    const onSaved = draw();
    pick("a.xlsx");
    await screen.findByText(/The file gives/);

    fireEvent.click(screen.getByRole("button", { name: "Save as a new draft" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("A draft already exists: it is version 2.");
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

  it("tells a person who starts the first version that the sheet will name its rows", async () => {
    fakeApi({ [IMPORT]: () => json({ data: { saved: false, report: REPORT } }) });
    draw({ firstVersion: true, label: "Start from a spreadsheet" });

    pick("a.xlsx");

    expect(await screen.findByText(/This sheet will name its rows/)).toBeInTheDocument();
  });

  it("is switched off when the screen says so", () => {
    draw({ disabled: true });

    expect(screen.getByRole("button", { name: "Upload a spreadsheet" })).toBeDisabled();
  });
});
