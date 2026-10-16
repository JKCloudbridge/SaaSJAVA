import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { LayoutsPanel } from "./LayoutsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/metadata/objects/Deal__c/layouts";
const REMOVE = "DELETE /api/v1/metadata/objects/Deal__c/layouts/Details__c";

function field(apiName: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type: "TEXT", required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [
    field("id", { kind: "SYSTEM", editable: false }),
    field("code__c", { required: true }),
    field("note__c"),
  ],
};
const DETAILS = {
  apiName: "Details__c", objectApiName: "Deal__c", label: "Details", description: "The main form", version: 3,
  definition: {
    sections: [
      { label: "Main", columns: 2, fields: [{ field: "code__c", behaviour: "REQUIRED" }, { field: "note__c", behaviour: "HIDDEN" }] },
    ],
  },
};

describe("LayoutsPanel", () => {
  it("lists the layouts and previews one with sample values and without the hidden field, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json({ data: [DETAILS] }) });

    render(
      <StrictMode>
        <LayoutsPanel object={DEAL} onChanged={vi.fn()} />
      </StrictMode>,
    );

    const row = await screen.findByTestId("layout-row");
    expect(row).toHaveTextContent("Details");
    expect(row).toHaveTextContent("Details__c");
    fireEvent.click(within(row).getByRole("button", { name: "Preview" }));

    const preview = await screen.findByTestId("layout-preview");
    expect(preview).toHaveTextContent("No records exist yet");
    expect(within(preview).getAllByTestId("preview-field")).toHaveLength(1);
    expect(within(preview).getByLabelText(/code__c/)).toHaveValue("Sample text");
    expect(preview).toHaveTextContent("1 hidden field(s) are kept in the layout.");
  });

  it("opens the builder for a new layout and for an existing one", async () => {
    fakeApi({ [LIST]: () => json({ data: [DETAILS] }) });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("layout-row");

    expect(screen.getByRole("link", { name: "New page layout" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/layouts/new",
    );
    expect(within(row).getByRole("link", { name: "Build" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/layouts/Details__c",
    );
    expect(within(row).getByRole("link", { name: "Details" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/layouts/Details__c",
    );
  });

  it("removes a layout and tells the object page", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [DETAILS] }),
      [REMOVE]: () => new Response(null, { status: 204 }),
    });
    const changed = vi.fn();
    render(<LayoutsPanel object={DEAL} onChanged={changed} />);
    const row = await screen.findByTestId("layout-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("The page layout was removed.");
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
    expect(changed).toHaveBeenCalled();
  });

  it("records the removal in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [DETAILS] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("layout-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_LAYOUT", objectApiName: "Deal__c", itemApiName: "Details__c" });
  });
});
