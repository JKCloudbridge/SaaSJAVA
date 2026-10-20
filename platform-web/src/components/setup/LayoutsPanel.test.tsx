import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { tabProps } from "@/test/objectTab";
import { LayoutsPanel } from "./LayoutsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const SECTION = "/setup/objects/Deal__c?section=layouts";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  setUrl(SECTION);
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
  it("lists the layouts with who made and last changed them, and previews one without the hidden field, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json({ data: [DETAILS] }) });
    const authorship = {
      of: () => ({
        kind: "LAYOUT",
        apiName: "Details__c",
        createdBy: "Member A",
        createdAt: "2026-10-01T10:00:00Z",
        updatedBy: "Member B",
        updatedAt: "2026-10-02T10:00:00Z",
      }),
    };

    render(
      <StrictMode>
        <LayoutsPanel {...tabProps(DEAL, { authorship })} />
      </StrictMode>,
    );

    const row = await screen.findByTestId("layout-row");
    expect(screen.getByRole("heading", { name: "Deal — Page layouts" })).toBeInTheDocument();
    expect(row).toHaveTextContent("Details");
    expect(row).toHaveTextContent("Details__c");
    expect(row).toHaveTextContent("Member A");
    expect(row).toHaveTextContent("Member B");
    fireEvent.click(within(row).getByRole("button", { name: "Preview" }));

    const preview = await screen.findByTestId("layout-preview");
    expect(preview).toHaveTextContent("No records exist yet");
    expect(within(preview).getAllByTestId("preview-field")).toHaveLength(1);
    expect(within(preview).getByLabelText(/code__c/)).toHaveValue("Sample text");
    expect(preview).toHaveTextContent("1 hidden field(s) are kept in the layout.");
  });

  it("opens the builder for a new layout and for an existing one, at the addresses they always had", async () => {
    fakeApi({ [LIST]: () => json({ data: [DETAILS] }) });
    render(<LayoutsPanel {...tabProps(DEAL)} />);
    const row = await screen.findByTestId("layout-row");

    expect(screen.getByRole("link", { name: "New" })).toHaveAttribute("href", "/setup/objects/Deal__c/layouts/new");
    expect(within(row).getByRole("link", { name: "Details" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/layouts/Details__c",
    );
  });

  it("opens the layout assignments behind a button, with the way back to the list", async () => {
    fakeApi({
      [LIST]: () => json({ data: [DETAILS] }),
      "GET /api/v1/metadata/objects/Deal__c/layout-assignments": () => json({ data: [] }),
      "GET /api/v1/metadata/objects/Deal__c/record-types": () => json({ data: [] }),
      "GET /api/v1/profiles": () => json({ data: [] }),
    });
    render(<LayoutsPanel {...tabProps(DEAL)} />);
    await screen.findByTestId("layout-row");

    expect(screen.getByRole("link", { name: "Layout assignments" })).toHaveAttribute("href", `${SECTION}&view=assignments`);
    setUrl(`${SECTION}&view=assignments`);

    const page = await screen.findByTestId("layout-assignments-page");
    expect(within(page).getByRole("heading", { name: "Layout assignments" })).toBeInTheDocument();
    expect(await within(page).findByTestId("layout-assignments")).toBeInTheDocument();
    expect(within(page).getByRole("link", { name: "Page layouts" })).toHaveAttribute("href", SECTION);
  });

  it("searches the layouts", async () => {
    fakeApi({ [LIST]: () => json({ data: [DETAILS, { ...DETAILS, apiName: "Other__c", label: "Other" }] }) });
    render(<LayoutsPanel {...tabProps(DEAL)} />);
    await screen.findAllByTestId("layout-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search layouts" }), { target: { value: "other" } });

    expect(screen.getAllByTestId("layout-row")).toHaveLength(1);
  });

  it("removes a layout and tells the object page", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [DETAILS] }),
      [REMOVE]: () => new Response(null, { status: 204 }),
    });
    const props = tabProps(DEAL);
    render(<LayoutsPanel {...props} />);
    const row = await screen.findByTestId("layout-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("The page layout was removed.");
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
    expect(props.reload).toHaveBeenCalled();
  });

  it("records the removal in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [DETAILS] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<LayoutsPanel {...tabProps(DEAL)} />);
    const row = await screen.findByTestId("layout-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_LAYOUT", objectApiName: "Deal__c", itemApiName: "Details__c" });
  });

  it("says that an object whose records belong to the platform has no page layouts, and asks nothing", () => {
    const calls = fakeApi();

    render(<LayoutsPanel {...tabProps({ ...DEAL, managedBy: "identity" })} />);

    expect(screen.getByText(/belong to the platform, so it has no page layouts/)).toBeInTheDocument();
    expect(calls.some((call) => call.key === LIST)).toBe(false);
  });
});
