import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { RecordFiles } from "./RecordFiles";

const FILES = "GET /api/v1/objects/Deal__c/records/r1/files";
const REMOVE = "DELETE /api/v1/objects/Deal__c/records/r1/files/f1";
const FILE = {
  id: "f1",
  name: "plan.pdf",
  contentType: "application/pdf",
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

describe("the files of a record", () => {
  it("lists them with their size and a link to download each", async () => {
    fakeApi({ [FILES]: () => json({ data: [FILE] }) });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange />);

    const link = await screen.findByRole("link", { name: "plan.pdf" });

    expect(link).toHaveAttribute("href", "/api/v1/objects/Deal__c/records/r1/files/f1/content");
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
  });

  it("says there are no files", async () => {
    fakeApi({ [FILES]: () => json({ data: [] }) });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange />);

    expect(await screen.findByText("No files.")).toBeInTheDocument();
  });

  it("offers neither adding nor removing to a member who may not change the record", async () => {
    fakeApi({ [FILES]: () => json({ data: [FILE] }) });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange={false} />);

    await screen.findByRole("link", { name: "plan.pdf" });

    expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
    expect(screen.queryByLabelText("Add a file")).toBeNull();
  });

  it("removes a file and asks again", async () => {
    let listed = 0;
    const calls = fakeApi({
      [FILES]: () => json({ data: ++listed === 1 ? [FILE] : [] }),
      [REMOVE]: () => new Response(null, { status: 204 }),
    });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange />);
    await screen.findByRole("link", { name: "plan.pdf" });

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(await screen.findByText("No files.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("shows the server's words when a file is refused, such as its size or type", async () => {
    fakeApi({ [FILES]: () => json({ data: [] }) });
    const original = globalThis.fetch;
    vi.stubGlobal("fetch", async (request: Request | string, init?: RequestInit) => {
      const method = typeof request === "string" ? init?.method : request.method;
      if (method === "POST") {
        return json({ error: { code: "VALIDATION_ERROR", message: "Files of this type are not accepted." } }, 422);
      }
      return original(request as Request, init);
    });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange />);
    await screen.findByText("No files.");

    fireEvent.change(screen.getByLabelText("Add a file"), { target: { files: [new File(["x"], "bad.exe")] } });

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Files of this type are not accepted."));
  });

  it("shows the server's words when the list cannot be read", async () => {
    fakeApi({ [FILES]: () => refusal("FORBIDDEN", "You may not see these files.", 403) });
    render(<RecordFiles objectApiName="Deal__c" recordId="r1" mayChange={false} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("You may not see these files.");
  });
});
