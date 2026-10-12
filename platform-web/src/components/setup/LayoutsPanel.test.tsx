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
const CREATE = "POST /api/v1/metadata/objects/Deal__c/layouts";
const CHANGE = "PUT /api/v1/metadata/objects/Deal__c/layouts/Details__c";

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

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

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

  it("adds a layout with a field and sends the definition the API checks", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [] }), [CREATE]: () => json({ data: DETAILS }, 201) });
    const changed = vi.fn();
    render(<LayoutsPanel object={DEAL} onChanged={changed} />);
    await screen.findByText("No page layouts yet.");

    const add = form("Add a page layout");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Details" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Details" } });
    fireEvent.change(add.getByLabelText("Add a field to this section"), { target: { value: "code__c" } });
    fireEvent.change(add.getByLabelText(/code__c/), { target: { value: "REQUIRED" } });
    fireEvent.change(add.getByLabelText("Columns"), { target: { value: "2" } });
    fireEvent.click(add.getByRole("button", { name: "Add the page layout" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("The page layout was added.");
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent.name).toBe("Details");
    expect(sent.definition).toEqual({
      sections: [{ label: "Details", columns: 2, fields: [{ field: "code__c", behaviour: "REQUIRED" }] }],
    });
    expect(changed).toHaveBeenCalled();
  });

  it("offers a field only once in the form", async () => {
    fakeApi({ [LIST]: () => json({ data: [] }) });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No page layouts yet.");
    const add = form("Add a page layout");

    fireEvent.change(add.getByLabelText("Add a field to this section"), { target: { value: "code__c" } });

    const offered = within(add.getByLabelText("Add a field to this section")).getAllByRole("option");
    expect(offered.map((option) => option.textContent)).toEqual(["Choose a field", "note__c (note__c)"]);
  });

  it("changes a layout with the version it read", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [DETAILS] }), [CHANGE]: () => json({ data: DETAILS }) });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("layout-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    fireEvent.change(form("Change the page layout").getByLabelText("Label"), { target: { value: "Main form" } });
    fireEvent.click(form("Change the page layout").getByRole("button", { name: "Save the page layout" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("The page layout was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === CHANGE)?.body ?? "{}");
    expect(sent.version).toBe(3);
    expect(sent.label).toBe("Main form");
    expect(sent.definition.sections[0].fields).toHaveLength(2);
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No page layouts yet.");

    fireEvent.change(form("Add a page layout").getByLabelText("Name"), { target: { value: "Details" } });
    fireEvent.change(form("Add a page layout").getByLabelText("Label"), { target: { value: "Details" } });
    fireEvent.click(form("Add a page layout").getByRole("button", { name: "Add the page layout" }));

    expect(await screen.findByTestId("layouts-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_LAYOUT");
    expect(sent.objectApiName).toBe("Deal__c");
    expect(sent.createLayout.name).toBe("Details");
  });

  it("shows the words of the API with the place that is wrong", async () => {
    fakeApi({
      [LIST]: () => json({ data: [] }),
      [CREATE]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request has problems.",
              fields: { "definition.sections[0].fields[0].behaviour": ["Must be one of: EDITABLE, READ_ONLY."] },
            },
          },
          400,
        ),
    });
    render(<LayoutsPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No page layouts yet.");

    fireEvent.change(form("Add a page layout").getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(form("Add a page layout").getByRole("button", { name: "Add the page layout" }));

    const problem = await screen.findByTestId("layouts-problem");
    expect(problem).toHaveTextContent("sections[0].fields[0].behaviour: Must be one of: EDITABLE, READ_ONLY.");
    expect(problem).not.toHaveTextContent("definition.sections");
  });

  it("says that an object of the platform has no layouts", () => {
    fakeApi();
    render(<LayoutsPanel object={{ ...DEAL, managedBy: "identity" }} onChanged={vi.fn()} />);

    expect(screen.getByText(/belong to the platform, so it has no page layouts/)).toBeInTheDocument();
  });
});
