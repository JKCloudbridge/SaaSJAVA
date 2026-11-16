import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { AttachmentDialog } from "./AttachmentDialog";

const FILES = "GET /api/v1/objects/Fill__c/records/h1/files";
const REMOVE = "DELETE /api/v1/objects/Fill__c/records/h1/files/f1";
const FILE = {
  id: "f1",
  name: "photo-a.png",
  contentType: "image/png",
  size: 2048,
  createdAt: "2030-01-02T03:04:05Z",
  createdBy: "u1",
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function dialog(overrides: Partial<Parameters<typeof AttachmentDialog>[0]> = {}) {
  const props = {
    object: "Fill__c",
    recordId: "h1",
    place: "B12",
    value: ["f1"],
    maxFiles: 5,
    required: false,
    readOnly: false,
    onChange: vi.fn(() => null),
    beforeRemove: vi.fn(async () => true),
    afterRemove: vi.fn(async () => undefined),
    onClose: vi.fn(),
    ...overrides,
  };
  render(<AttachmentDialog {...props} />);
  return props;
}

describe("the popup of an attachment cell in the fill-in grid (SH5.1)", () => {
  it("names the cell, lists the files of the cell with a download link and lets one be taken off", async () => {
    let listed = 0;
    const calls = fakeApi({
      [FILES]: () => json({ data: ++listed === 1 ? [FILE] : [] }),
      [REMOVE]: () => new Response(null, { status: 204 }),
    });
    const props = dialog();

    expect(screen.getByRole("dialog", { name: "Attachments, cell B12" })).toBeInTheDocument();
    const link = await screen.findByRole("link", { name: "photo-a.png" });
    expect(link).toHaveAttribute("href", "/api/v1/objects/Fill__c/records/h1/files/f1/content");
    expect(screen.getByText("(1 attachment)")).toBeInTheDocument();
    expect(screen.getByLabelText("Attach a file")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    await waitFor(() => expect(props.afterRemove).toHaveBeenCalled());
    expect(props.beforeRemove).toHaveBeenCalled();
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("only lists and downloads for a person who may not change the cell", async () => {
    fakeApi({ [FILES]: () => json({ data: [FILE] }) });
    dialog({ readOnly: true });

    await screen.findByRole("link", { name: "photo-a.png" });

    expect(screen.getByText(/cannot be changed here/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
    expect(screen.queryByLabelText("Attach a file")).toBeNull();
  });

  it("says when at least one file is needed and closes", async () => {
    fakeApi({ [FILES]: () => json({ data: [] }) });
    const props = dialog({ value: [], required: true, maxFiles: 1 });

    expect(screen.getByText("Attach up to 1 file. At least one is needed before the fill-in is sent.")).toBeInTheDocument();
    // the popup has its own Close button beside the one in its corner: either closes it
    fireEvent.click(screen.getAllByRole("button", { name: "Close" }).at(-1) as HTMLElement);

    expect(props.onClose).toHaveBeenCalled();
  });
});
