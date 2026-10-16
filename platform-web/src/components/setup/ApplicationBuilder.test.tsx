import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { dragAndDrop } from "@/test/dragDrop";
import { fakeApi, json } from "@/test/fakeApi";
import { ApplicationBuilder } from "./ApplicationBuilder";

const state = vi.hoisted(() => ({ application: "Sales__c", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ applicationApiName: state.application }),
  useRouter: () => ({ replace: state.replace, push: vi.fn() }),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  state.application = "Sales__c";
  state.replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const PROFILE = "0198a1b2-0000-7000-8000-000000000001";
const SALES = {
  apiName: "Sales__c", label: "Sales", description: "The sales menu", version: 2,
  definition: {
    iconKey: "briefcase",
    profiles: [PROFILE],
    items: [{ type: "HEADING", label: "Work" }, { type: "OBJECT", object: "Deal__c" }],
  },
};

function answers(extra: Record<string, () => Response> = {}) {
  return {
    "GET /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Deal__c", label: "Deal" }, { apiName: "Contact__c", label: "Contact" }] }),
    "GET /api/v1/profiles": () => json({ data: [{ id: PROFILE, name: "Sales" }] }),
    "GET /api/v1/metadata/tabs": () =>
      json({ data: [{ apiName: "Map__c", label: "Map", description: "", version: 1, definition: { kind: "CUSTOM", customPage: "P__c" } }] }),
    "GET /api/v1/metadata/objects/Deal__c/record-pages": () =>
      json({ data: [{ apiName: "Overview__c", label: "Overview" }] }),
    ...extra,
  };
}

function palette() {
  return within(screen.getByTestId("palette"));
}

function entries() {
  return screen.getAllByTestId("navigation-entry");
}

async function loaded() {
  await waitFor(() => expect(screen.queryAllByTestId("navigation-entry").length).toBeGreaterThan(0));
}

describe("ApplicationBuilder", () => {
  it("shows the navigation in order and dims what is listed already, under React strict mode", async () => {
    fakeApi(answers());

    render(
      <StrictMode>
        <ApplicationBuilder />
      </StrictMode>,
    );
    await loaded();

    expect(entries()).toHaveLength(2);
    expect(within(entries()[0] as HTMLElement).getByLabelText("Text of the heading")).toHaveValue("Work");
    expect(within(entries()[1] as HTMLElement).getByText("Deal")).toBeInTheDocument();
    expect(palette().getByRole("button", { name: "Deal" })).toHaveAttribute("aria-disabled", "true");
    expect(palette().getByRole("button", { name: "Contact" })).not.toHaveAttribute("aria-disabled");
    expect(screen.getByLabelText("Sales")).toBeChecked();
    expect(screen.queryByTestId("unsaved")).toBeNull();
  });

  it("drags an object and a tab into the list at a place, and saves the items in order", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }) }));
    render(<ApplicationBuilder />);
    await loaded();

    dragAndDrop(palette().getByRole("button", { name: "Contact" }), entries()[1] as HTMLElement);
    fireEvent.click(within(palette().getByRole("group", { name: "Navigation parts: categories" })).getByRole("button", { name: "Custom tabs" }));
    dragAndDrop(palette().getByRole("button", { name: "Map" }), screen.getByTestId("navigation-canvas"));

    await waitFor(() => expect(entries()).toHaveLength(4));
    expect(await screen.findByTestId("unsaved")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));
    expect(await screen.findByTestId("application-builder-notice")).toHaveTextContent("The application was saved.");

    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")?.body ?? "{}");
    expect(sent.version).toBe(2);
    expect(sent.definition).toEqual({
      iconKey: "briefcase",
      profiles: [PROFILE],
      items: [
        { type: "HEADING", label: "Work" },
        { type: "OBJECT", object: "Contact__c" },
        { type: "OBJECT", object: "Deal__c" },
        { type: "TAB", tab: "Map__c" },
      ],
    });
  });

  it("moves an entry by dragging it, and with the buttons, and takes one out", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }) }));
    render(<ApplicationBuilder />);
    await loaded();

    // The object is dragged before the heading.
    const handle = within(entries()[1] as HTMLElement).getByTitle("Drag to move the entry");
    dragAndDrop(handle, entries()[0] as HTMLElement);
    expect(within(entries()[0] as HTMLElement).getByText("Deal")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Move Deal down" }));
    expect(within(entries()[1] as HTMLElement).getByText("Deal")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Take out Work" }));
    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));
    await screen.findByTestId("application-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")?.body ?? "{}");
    expect(sent.definition.items).toEqual([{ type: "OBJECT", object: "Deal__c" }]);
    expect(palette().getByRole("button", { name: "Deal" })).toHaveAttribute("aria-disabled", "true");
  });

  it("adds a heading, a link and a record page from the Other chips and finishes them in place", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }) }));
    render(<ApplicationBuilder />);
    await loaded();

    fireEvent.click(within(palette().getByRole("group", { name: "Navigation parts: categories" })).getByRole("button", { name: "Other" }));
    fireEvent.keyDown(palette().getByRole("button", { name: "Link" }), { key: "Enter" });
    fireEvent.keyDown(palette().getByRole("button", { name: "Record page" }), { key: "Enter" });
    await waitFor(() => expect(entries()).toHaveLength(4));
    expect(screen.getAllByText("Not finished")).toHaveLength(2);

    fireEvent.change(screen.getByLabelText("Path of the link"), { target: { value: "/members" } });
    fireEvent.change(screen.getByLabelText("Text of the link"), { target: { value: "People" } });
    fireEvent.change(screen.getByLabelText("Object of the record page"), { target: { value: "Deal__c" } });
    const page = await screen.findByLabelText("Record page");
    await waitFor(() => expect(within(page).getAllByRole("option")).toHaveLength(2));
    fireEvent.change(page, { target: { value: "Overview__c" } });
    expect(screen.queryAllByText("Not finished")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));
    await screen.findByTestId("application-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")?.body ?? "{}");
    expect(sent.definition.items.slice(2)).toEqual([
      { type: "LINK", label: "People", path: "/members" },
      { type: "RECORD_PAGE", object: "Deal__c", page: "Overview__c" },
    ]);
  });

  it("gives an entry a label of its own", async () => {
    const calls = fakeApi(answers({ "PUT /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }) }));
    render(<ApplicationBuilder />);
    await loaded();

    fireEvent.change(within(entries()[1] as HTMLElement).getByLabelText("Label shown instead of the name (optional)"), {
      target: { value: "Deals" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));
    await screen.findByTestId("application-builder-notice");

    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")?.body ?? "{}");
    expect(sent.definition.items[1]).toEqual({ type: "OBJECT", label: "Deals", object: "Deal__c" });
  });

  it("makes a new application and moves on to the one it made", async () => {
    state.application = "new";
    const calls = fakeApi(
      answers({ "POST /api/v1/metadata/applications": () => json({ data: { ...SALES, apiName: "Service__c" } }, 201) }),
    );
    render(<ApplicationBuilder />);
    await screen.findByLabelText("Name");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Service" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Service" } });
    fireEvent.click(palette().getByRole("button", { name: "Deal" }));
    await waitFor(() => expect(entries()).toHaveLength(1));
    fireEvent.click(screen.getByRole("button", { name: "Add the application" }));

    expect(await screen.findByTestId("application-builder-notice")).toHaveTextContent("The application was added.");
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/applications")?.body ?? "{}");
    expect(sent).toEqual({
      name: "Service",
      label: "Service",
      description: "",
      definition: { profiles: [], items: [{ type: "OBJECT", object: "Deal__c" }] },
    });
    expect(state.replace).toHaveBeenCalledWith("/setup/applications/Service__c");
  });

  it("puts the change into the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers({ "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<ApplicationBuilder />);
    await loaded();

    fireEvent.click(palette().getByRole("button", { name: "Contact" }));
    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));

    expect(await screen.findByTestId("application-builder-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("UPDATE_APPLICATION");
    expect(sent.itemApiName).toBe("Sales__c");
  });

  it("shows the words of the API with the place of a link that leaves the organization", async () => {
    fakeApi(
      answers({
        "PUT /api/v1/metadata/applications/Sales__c": () =>
          json(
            {
              error: {
                code: "VALIDATION_ERROR",
                message: "The request has problems.",
                fields: { "definition.items[2].path": ["A link stays inside the organization."] },
              },
            },
            400,
          ),
      }),
    );
    render(<ApplicationBuilder />);
    await loaded();

    fireEvent.click(screen.getByRole("button", { name: "Save the application" }));

    expect(await screen.findByTestId("application-builder-problem")).toHaveTextContent(
      "A link stays inside the organization.",
    );
  });
});
