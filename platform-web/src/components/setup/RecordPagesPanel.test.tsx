import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordPagesPanel } from "./RecordPagesPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const BASE = "/api/v1/metadata/objects/Deal__c";

function field(apiName: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type: "TEXT", required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [field("id", { kind: "SYSTEM", editable: false }), field("code__c", { required: true }), field("stage__c")],
};
const CONTACT = {
  ...DEAL,
  apiName: "Contact__c",
  label: "Contact",
  pluralLabel: "Contacts",
  fields: [
    field("id", { kind: "SYSTEM", editable: false }),
    field("surname__c"),
    field("deal__c", { type: "LOOKUP", settings: { targetObject: "Deal__c" } }),
  ],
};
const LAYOUT = {
  apiName: "Details__c", objectApiName: "Deal__c", label: "Details", description: "", version: 0,
  definition: { sections: [{ label: "Main", fields: [{ field: "code__c" }] }] },
};
const PAGE = {
  apiName: "Overview__c", objectApiName: "Deal__c", label: "Overview", description: "", version: 5,
  definition: {
    layout: "Details__c",
    components: [
      { key: "head", type: "HEADER", titleField: "code__c", highlightFields: ["stage__c"] },
      { key: "details", type: "DETAILS", visibleWhen: { field: "stage__c", operator: "EQUALS", value: "Open" } },
      {
        key: "contacts", type: "RELATED_LIST", object: "Contact__c", field: "deal__c", columns: ["surname__c"], limit: 5,
        visibleWhen: { all: [{ ability: "members.view" }, { any: [{ ability: "members.invite" }] }] },
      },
    ],
    actions: [{ action: "EDIT" }],
  },
};

function answers(pages: unknown[]) {
  return {
    [`GET ${BASE}/record-pages`]: () => json({ data: pages }),
    [`GET ${BASE}/layouts`]: () => json({ data: [LAYOUT] }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Deal__c", label: "Deal" }, { apiName: "Contact__c", label: "Contact" }] }),
    "GET /api/v1/metadata/objects/Contact__c": () => json({ data: CONTACT }),
    "GET /api/v1/abilities": () =>
      json({ data: [{ key: "members.view", name: "View members", description: "" }] }),
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("RecordPagesPanel", () => {
  it("lists the pages and previews the structure with the rules in words, under React strict mode", async () => {
    fakeApi(answers([PAGE]));

    render(
      <StrictMode>
        <RecordPagesPanel object={DEAL} onChanged={vi.fn()} />
      </StrictMode>,
    );

    const row = await screen.findByTestId("record-page-row");
    expect(row).toHaveTextContent("Overview");
    fireEvent.click(within(row).getByRole("button", { name: "Preview" }));

    const preview = await screen.findByTestId("record-page-preview");
    const parts = within(preview).getAllByTestId("preview-component");
    expect(parts.map((part) => part.getAttribute("data-type"))).toEqual(["HEADER", "DETAILS", "RELATED_LIST"]);
    expect(parts[1]).toHaveTextContent("Shown when stage__c is Open.");
    expect(parts[2]).toHaveTextContent("(the person holds the ability members.view and (the person holds the ability members.invite))");
    expect(within(preview).getByTestId("layout-preview")).toBeInTheDocument();
    expect(within(preview).getByTestId("preview-actions")).toHaveTextContent("EDIT");
  });

  it("adds a page with the details of a layout and one condition, and sends closed data", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`POST ${BASE}/record-pages`]: () => json({ data: PAGE }, 201),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record pages yet.");

    const add = form("Add a record page");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Layout shown by the details"), { target: { value: "Details__c" } });
    fireEvent.change(add.getByLabelText("Shown when"), { target: { value: "field" } });
    fireEvent.change(add.getByLabelText("Field"), { target: { value: "stage__c" } });
    fireEvent.change(add.getByLabelText("Value"), { target: { value: "Open" } });
    fireEvent.click(add.getByLabelText("Edit"));
    fireEvent.click(add.getByRole("button", { name: "Add the record page" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("The record page was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-pages`)?.body ?? "{}");
    expect(sent.definition.layout).toBe("Details__c");
    expect(sent.definition.components).toEqual([
      { key: "details-1", type: "DETAILS", visibleWhen: { field: "stage__c", operator: "EQUALS", value: "Open" } },
    ]);
    expect(sent.definition.actions).toEqual([{ action: "EDIT" }]);
  });

  it("keeps a combined rule as it is when the page is saved", async () => {
    const calls = fakeApi({
      ...answers([PAGE]),
      [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-page-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    expect(await screen.findAllByText(/Shown when \(the person holds the ability members.view/)).not.toHaveLength(0);
    fireEvent.click(form("Change the record page").getByRole("button", { name: "Save the record page" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("The record page was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    expect(sent.version).toBe(5);
    expect(sent.definition.components[2].visibleWhen).toEqual(PAGE.definition.components[2]?.visibleWhen);
    expect(sent.definition.components[2].columns).toEqual(["surname__c"]);
  });

  it("offers only the lookup fields of the related object for a related list", async () => {
    fakeApi(answers([]));
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record pages yet.");
    const add = form("Add a record page");

    fireEvent.change(add.getByLabelText("Add a component"), { target: { value: "RELATED_LIST" } });
    fireEvent.change(add.getAllByLabelText("Records of")[0] as HTMLElement, { target: { value: "Contact__c" } });

    const through = await screen.findByLabelText("Linked through the field");
    await waitFor(() => {
      const options = within(through).getAllByRole("option");
      expect(options.map((option) => option.textContent)).toEqual([
        "Choose a lookup or master-detail field",
        "deal__c (deal__c)",
      ]);
    });
  });

  it("adds a tab of the organization inside a tab, and sends its name with the label that replaces its own", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`POST ${BASE}/record-pages`]: () => json({ data: PAGE }, 201),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record pages yet.");
    const add = form("Add a record page");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Layout shown by the details"), { target: { value: "Details__c" } });

    fireEvent.change(add.getByLabelText("Add a component"), { target: { value: "TABS" } });
    fireEvent.change(add.getByLabelText("Add to this tab"), { target: { value: "TAB" } });
    fireEvent.change(add.getByLabelText("API name of the tab"), { target: { value: "Inspections__c" } });
    fireEvent.change(add.getByLabelText(/Label \(overrides the tab.s own\)/), { target: { value: "Checks" } });
    fireEvent.click(add.getByRole("button", { name: "Add the record page" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("The record page was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-pages`)?.body ?? "{}");
    expect(sent.definition.components[1].tabs[0].components).toEqual([
      { key: "tab-1", type: "TAB", tab: "Inspections__c", label: "Checks" },
    ]);
  });

  it("offers Share among the actions and sends it as closed data", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`POST ${BASE}/record-pages`]: () => json({ data: PAGE }, 201),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record pages yet.");
    const add = form("Add a record page");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Overview" } });
    fireEvent.change(add.getByLabelText("Layout shown by the details"), { target: { value: "Details__c" } });
    fireEvent.change(add.getByLabelText("Add a component"), { target: { value: "DETAILS" } });

    fireEvent.click(add.getByLabelText("Share"));
    fireEvent.click(add.getByRole("button", { name: "Add the record page" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("The record page was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-pages`)?.body ?? "{}");
    expect(sent.definition.actions).toEqual([{ action: "SHARE" }]);
  });

  it("shows the words of the API with the place and removes a page into a change set", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers([PAGE]),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-page-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("goes live when the set is published");
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_RECORD_PAGE", objectApiName: "Deal__c", itemApiName: "Overview__c" });
  });
});
