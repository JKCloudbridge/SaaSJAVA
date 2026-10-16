import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { dragAndDrop } from "@/test/dragDrop";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordPageBuilder } from "./RecordPageBuilder";

const state = vi.hoisted(() => ({ page: "Overview__c", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ objectApiName: "Deal__c", pageApiName: state.page }),
  useRouter: () => ({ replace: state.replace, push: vi.fn() }),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  state.page = "Overview__c";
  state.replace.mockReset();
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
    field("deal__c", { type: "LOOKUP" }),
    field("phone__c"),
    field("email__c"),
    field("city__c"),
  ],
};
const LAYOUT = {
  apiName: "Details__c", objectApiName: "Deal__c", label: "Details", description: "", version: 0,
  definition: { sections: [{ label: "Main", fields: [{ field: "code__c" }] }] },
};
const MAP_TAB = {
  apiName: "Map__c", label: "Map", description: "", version: 1, definition: { kind: "CUSTOM", customPage: "FieldMap__c" },
};
const PAGES = [
  { apiName: "FieldMap__c", label: "Field map", description: "", version: 1, definition: { frontend: { assetId: "a", sha256: "h", size: 3 } } },
  { apiName: "Gauge__c", label: "Gauge", description: "", version: 1, definition: { frontend: { assetId: "b", sha256: "g", size: 4 } } },
];
const RELATIONSHIP = {
  type: "ONE_TO_MANY", fieldType: "LOOKUP", childObject: "Contact__c", field: "deal__c", fieldLabel: "Deal",
  parentObject: "Deal__c", otherObject: "Contact__c", onDelete: "REFUSE", reparentable: false, required: false,
  listLabel: "Contacts",
};
const RULE = { all: [{ ability: "members.view" }, { any: [{ ability: "members.invite" }] }] };
const PAGE = {
  apiName: "Overview__c", objectApiName: "Deal__c", label: "Overview", description: "", version: 5,
  definition: {
    layout: "Details__c",
    components: [
      { key: "head", type: "HEADER", titleField: "code__c" },
      {
        key: "tabs", type: "TABS", tabs: [
          { label: "Content", components: [{ key: "details-1", type: "DETAILS" }] },
          {
            label: "Related",
            components: [
              {
                key: "contacts", type: "RELATED_LIST", object: "Contact__c", field: "deal__c", columns: ["surname__c"], limit: 5,
                visibleWhen: RULE,
              },
            ],
          },
        ],
      },
    ],
    actions: [{ action: "EDIT" }],
  },
};

function answers(extra: Record<string, () => Response> = {}) {
  return {
    [`GET ${BASE}`]: () => json({ data: DEAL }),
    [`GET ${BASE}/layouts`]: () => json({ data: [LAYOUT] }),
    [`GET ${BASE}/relationships`]: () => json({ data: { parents: [], children: [RELATIONSHIP], manyToMany: [] } }),
    [`GET ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }),
    "GET /api/v1/metadata/objects": () => json({ data: [{ apiName: "Deal__c", label: "Deal" }, { apiName: "Contact__c", label: "Contact" }] }),
    "GET /api/v1/metadata/objects/Contact__c": () => json({ data: CONTACT }),
    "GET /api/v1/abilities": () => json({ data: [{ key: "members.view", name: "View members", description: "" }] }),
    "GET /api/v1/metadata/tabs": () => json({ data: [MAP_TAB] }),
    "GET /api/v1/metadata/custom-pages": () => json({ data: PAGES }),
    ...extra,
  };
}

function palette() {
  return within(screen.getByTestId("palette"));
}

function goTo(category: string) {
  fireEvent.click(palette().getByRole("button", { name: category, pressed: false }));
}

function cards() {
  return screen.getAllByTestId("page-tab-card");
}

async function loaded() {
  await waitFor(() => expect(screen.queryAllByTestId("page-tab-card").length).toBeGreaterThan(0));
}

describe("RecordPageBuilder", () => {
  it("shows the tabs of the page with their parts, under React strict mode", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <RecordPageBuilder />
      </StrictMode>,
    );
    await loaded();

    expect(cards()).toHaveLength(2);
    expect(within(cards()[0] as HTMLElement).getByLabelText("Label of tab 1")).toHaveValue("Content");
    expect(within(cards()[0] as HTMLElement).getByText("Details")).toBeInTheDocument();
    expect(within(cards()[1] as HTMLElement).getByText("Related: Contact__c")).toBeInTheDocument();
    expect(screen.getByLabelText("Layout shown by the details")).toHaveValue("Details__c");
    expect(screen.queryByTestId("unsaved")).toBeNull();
  });

  it("saves the page unchanged with the version it read and keeps a combined rule as it is", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }) }));
    render(<RecordPageBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));

    expect(await screen.findByTestId("record-page-builder-notice")).toHaveTextContent("The record page was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    expect(sent.version).toBe(5);
    expect(sent.definition.components[1].tabs[1].components[0].visibleWhen).toEqual(RULE);
    expect(sent.definition.actions).toEqual([{ action: "EDIT" }]);
    expect(sent.definition.components[0]).toEqual({ key: "head", type: "HEADER", titleField: "code__c" });
  });

  it("drags a tab of the organization into a tab, keeping the other parts", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }) }));
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Tabs");
    dragAndDrop(palette().getByRole("button", { name: "Map" }), within(cards()[0] as HTMLElement).getByTestId("page-tab-items"));
    expect(await screen.findByTestId("unsaved")).toBeInTheDocument();
    expect(within(cards()[0] as HTMLElement).getAllByTestId("page-tab-part")).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));
    await screen.findByTestId("record-page-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    expect(sent.definition.components[1].tabs[0].components).toEqual([
      { key: "details-1", type: "DETAILS" },
      { key: "tab-1", type: "TAB", tab: "Map__c" },
    ]);
  });

  it("makes a tab for a custom page that has none, in the same save, and uses the tab that already shows a page", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/tabs": () => json({ data: { ...MAP_TAB, apiName: "Gauge__c", label: "Gauge" } }, 201),
        [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }),
      }),
    );
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Custom pages");
    expect(palette().getByRole("button", { name: "Gauge" })).toHaveAttribute(
      "title",
      "Gauge: a tab is made for it when you save",
    );
    expect(palette().getByRole("button", { name: "Field map" })).toHaveAttribute(
      "title",
      "Field map: shown through the tab Map",
    );
    dragAndDrop(palette().getByRole("button", { name: "Gauge" }), screen.getByRole("button", { name: /Add a tab/ }));
    fireEvent.keyDown(palette().getByRole("button", { name: "Field map" }), { key: "Enter" });

    await waitFor(() => expect(cards()).toHaveLength(3));
    expect(within(cards()[2] as HTMLElement).getByText("Gauge")).toBeInTheDocument();
    expect(within(cards()[0] as HTMLElement).getByText("Map")).toBeInTheDocument();
    expect(await screen.findByText("Made when saved")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));
    await screen.findByTestId("record-page-builder-notice");

    const order = calls.map((call) => call.key).filter((key) => key.startsWith("POST") || key.startsWith("PUT"));
    expect(order).toEqual(["POST /api/v1/metadata/tabs", `PUT ${BASE}/record-pages/Overview__c`]);
    const made = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/tabs")?.body ?? "{}");
    expect(made).toEqual({ name: "Gauge", label: "Gauge", description: "", definition: { kind: "CUSTOM", customPage: "Gauge__c" } });
    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    const tabs = sent.definition.components[1].tabs as { components: unknown[] }[];
    expect(tabs[0]?.components).toEqual([
      { key: "details-1", type: "DETAILS" },
      { key: "tab-2", type: "TAB", tab: "Map__c" },
    ]);
    expect(tabs[2]?.components).toEqual([{ key: "tab-1", type: "TAB", tab: "Gauge__c" }]);
  });

  it("does not make a tab for a custom page whose chip was taken out again", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }) }));
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Custom pages");
    dragAndDrop(palette().getByRole("button", { name: "Gauge" }), screen.getByRole("button", { name: /Add a tab/ }));
    await waitFor(() => expect(cards()).toHaveLength(3));
    fireEvent.click(screen.getByRole("button", { name: "Take out Gauge" }));
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));
    await screen.findByTestId("record-page-builder-notice");

    expect(calls.some((call) => call.key === "POST /api/v1/metadata/tabs")).toBe(false);
  });

  it("adds a related list for a relationship with the first columns of the other object", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }) }));
    render(<RecordPageBuilder />);
    await loaded();

    fireEvent.click(palette().getByRole("button", { name: "Contacts" }));
    await waitFor(() => expect(within(cards()[0] as HTMLElement).getAllByTestId("page-tab-part")).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));
    await screen.findByTestId("record-page-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    expect(sent.definition.components[1].tabs[0].components[1]).toEqual({
      key: "related-1", type: "RELATED_LIST", object: "Contact__c", field: "deal__c", label: "Contacts",
      columns: ["surname__c", "phone__c", "email__c"],
    });
  });

  it("orders tabs and parts with the buttons, and takes a tab out", async () => {
    const calls = fakeApi(answers({ [`PUT ${BASE}/record-pages/Overview__c`]: () => json({ data: PAGE }) }));
    render(<RecordPageBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Move tab Content down" }));
    expect(within(cards()[1] as HTMLElement).getByLabelText("Label of tab 2")).toHaveValue("Content");
    fireEvent.click(screen.getByRole("button", { name: "Take out tab Related" }));
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));
    await screen.findByTestId("record-page-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)?.body ?? "{}");
    expect(sent.definition.components[1].tabs).toEqual([{ label: "Content", components: [{ key: "details-1", type: "DETAILS" }] }]);
  });

  it("lets a part of a tab be set: a TAB part picks from the tabs of the organization", async () => {
    fakeApi(answers());
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Tabs");
    fireEvent.keyDown(palette().getByRole("button", { name: "Map" }), { key: "Enter" });
    await waitFor(() => expect(screen.getAllByTestId("page-tab-part")).toHaveLength(3));
    const part = screen.getAllByTestId("page-tab-part")[0] as HTMLElement;
    fireEvent.click(within(screen.getAllByTestId("page-tab-part")[1] as HTMLElement).getByRole("button", { name: "Settings" }));

    const select = within(screen.getAllByTestId("page-tab-part")[1] as HTMLElement).getByLabelText("Tab");
    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual(["Choose a tab", "Map"]);
    expect(part).toBeInTheDocument();
  });

  it("makes a new page: it needs a layout to be saved and the details start it", async () => {
    state.page = "new";
    const calls = fakeApi(
      answers({
        [`POST ${BASE}/record-pages`]: () => json({ data: { ...PAGE, apiName: "Fresh__c" } }, 201),
      }),
    );
    render(<RecordPageBuilder />);
    await screen.findByLabelText("Name");

    expect(screen.getByRole("button", { name: "Add the record page" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Fresh" } });
    fireEvent.change(screen.getByLabelText("Layout shown by the details"), { target: { value: "Details__c" } });
    fireEvent.click(screen.getByRole("button", { name: /Add a tab/ }));
    await waitFor(() => expect(cards()).toHaveLength(1));
    fireEvent.click(palette().getByRole("button", { name: "Details" }));
    await waitFor(() => expect(within(cards()[0] as HTMLElement).getAllByTestId("page-tab-part")).toHaveLength(1));
    fireEvent.click(screen.getByRole("button", { name: "Add the record page" }));

    expect(await screen.findByTestId("record-page-builder-notice")).toHaveTextContent("The record page was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-pages`)?.body ?? "{}");
    expect(sent.name).toBe("Fresh");
    expect(sent.definition.layout).toBe("Details__c");
    expect(sent.definition.components.map((component: { type: string }) => component.type)).toEqual(["DETAILS", "TABS"]);
    expect(sent.definition.components[1].tabs).toEqual([
      { label: "Tab 1", components: [{ key: "details-2", type: "DETAILS" }] },
    ]);
    expect(state.replace).toHaveBeenCalledWith("/setup/objects/Deal__c/record-pages/Fresh__c");
  });

  it("puts the tab it makes and the page into the chosen change set", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Custom pages");
    dragAndDrop(palette().getByRole("button", { name: "Gauge" }), screen.getByRole("button", { name: /Add a tab/ }));
    await waitFor(() => expect(cards()).toHaveLength(3));
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));

    expect(await screen.findByTestId("record-page-builder-notice")).toHaveTextContent("goes live when the set is published");
    const sent = calls.filter((call) => call.key.endsWith("/changes")).map((call) => JSON.parse(call.body));
    expect(sent.map((one: { kind: string }) => one.kind)).toEqual(["CREATE_TAB", "UPDATE_RECORD_PAGE"]);
    expect(sent[0].createTab.definition).toEqual({ kind: "CUSTOM", customPage: "Gauge__c" });
    expect(calls.some((call) => call.key === "POST /api/v1/metadata/tabs")).toBe(false);
  });

  it("shows the words of the API and does not save the page when the tab it needs is refused", async () => {
    const calls = fakeApi(
      answers({
        "POST /api/v1/metadata/tabs": () =>
          json({ error: { code: "CONFLICT", message: "The organization has the most tabs it may have." } }, 409),
      }),
    );
    render(<RecordPageBuilder />);
    await loaded();

    goTo("Custom pages");
    dragAndDrop(palette().getByRole("button", { name: "Gauge" }), screen.getByRole("button", { name: /Add a tab/ }));
    await waitFor(() => expect(cards()).toHaveLength(3));
    fireEvent.click(screen.getByRole("button", { name: "Save the record page" }));

    expect(await screen.findByTestId("record-page-builder-problem")).toHaveTextContent("the most tabs");
    expect(calls.some((call) => call.key === `PUT ${BASE}/record-pages/Overview__c`)).toBe(false);
    expect(screen.getByTestId("unsaved")).toBeInTheDocument();
  });

  it("previews the draft", async () => {
    fakeApi(answers());
    render(<RecordPageBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    expect(await screen.findByTestId("record-page-preview")).toBeInTheDocument();
  });
});
