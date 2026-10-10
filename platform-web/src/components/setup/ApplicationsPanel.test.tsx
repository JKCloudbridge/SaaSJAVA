import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { ApplicationsPanel } from "./ApplicationsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/metadata/applications";
const CREATE = "POST /api/v1/metadata/applications";
const PROFILE = "0198a1b2-0000-7000-8000-000000000001";
const GONE = "0198a1b2-0000-7000-8000-000000000002";

const SALES = {
  apiName: "Sales__c", label: "Sales", description: "The sales menu", version: 2,
  definition: {
    iconKey: "briefcase",
    profiles: [PROFILE, GONE],
    items: [{ type: "HEADING", label: "Work" }, { type: "OBJECT", object: "Deal__c" }],
  },
};

function answers(applications: unknown[]) {
  return {
    [LIST]: () => json({ data: applications }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Deal__c", label: "Deal" }, { apiName: "Contact__c", label: "Contact" }] }),
    "GET /api/v1/profiles": () => json({ data: [{ id: PROFILE, name: "Sales" }] }),
    "GET /api/v1/metadata/objects/Deal__c/record-pages": () =>
      json({ data: [{ apiName: "Overview__c", label: "Overview" }] }),
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("ApplicationsPanel", () => {
  it("lists the applications with who sees them, under React strict mode", async () => {
    fakeApi(answers([SALES, { ...SALES, apiName: "Open__c", label: "Open", definition: { items: [] } }]));

    render(
      <StrictMode>
        <ApplicationsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("application-row");
    expect(rows[0]).toHaveTextContent("Sales");
    expect(rows[0]).toHaveTextContent("Sales, A profile that was deleted");
    expect(rows[0]).toHaveTextContent("2");
    expect(rows[1]).toHaveTextContent("Every member");
  });

  it("adds an application with a heading, an object, a record page and a link in order", async () => {
    const calls = fakeApi({ ...answers([]), [CREATE]: () => json({ data: SALES }, 201) });
    render(<ApplicationsPanel />);
    await screen.findByText("No applications yet.");
    const add = form("Add an application");

    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Sales" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "Sales" } });
    fireEvent.change(add.getByLabelText("Icon key"), { target: { value: "briefcase" } });
    fireEvent.click(add.getByLabelText("Sales"));
    for (let i = 0; i < 4; i++) {
      fireEvent.click(add.getByRole("button", { name: "Add an item" }));
    }
    fireEvent.change(add.getByLabelText("Opens", { selector: "#item-type-0" }), { target: { value: "HEADING" } });
    fireEvent.change(add.getByLabelText("Label", { selector: "#item-label-0" }), { target: { value: "Work" } });
    fireEvent.change(add.getByLabelText("Object", { selector: "#item-object-1" }), { target: { value: "Deal__c" } });
    fireEvent.change(add.getByLabelText("Opens", { selector: "#item-type-2" }), { target: { value: "RECORD_PAGE" } });
    fireEvent.change(add.getByLabelText("Object", { selector: "#item-object-2" }), { target: { value: "Deal__c" } });
    await screen.findByRole("option", { name: "Overview" });
    fireEvent.change(add.getByLabelText("Record page", { selector: "#item-page-2" }), {
      target: { value: "Overview__c" },
    });
    fireEvent.change(add.getByLabelText("Opens", { selector: "#item-type-3" }), { target: { value: "LINK" } });
    fireEvent.change(add.getByLabelText("Path", { selector: "#item-path-3" }), { target: { value: "/members" } });
    fireEvent.change(add.getByLabelText("Label", { selector: "#item-label-3" }), { target: { value: "Members" } });
    fireEvent.click(add.getByRole("button", { name: "Add the application" }));

    expect(await screen.findByTestId("applications-notice")).toHaveTextContent("The application was added.");
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent.name).toBe("Sales");
    expect(sent.definition).toEqual({
      iconKey: "briefcase",
      profiles: [PROFILE],
      items: [
        { type: "HEADING", label: "Work" },
        { type: "OBJECT", object: "Deal__c" },
        { type: "RECORD_PAGE", object: "Deal__c", page: "Overview__c" },
        { type: "LINK", label: "Members", path: "/members" },
      ],
    });
  });

  it("moves an item up and takes one out", async () => {
    const calls = fakeApi({ ...answers([SALES]), "PUT /api/v1/metadata/applications/Sales__c": () => json({ data: SALES }) });
    render(<ApplicationsPanel />);
    const row = await screen.findByTestId("application-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    const change = form("Change the application");
    fireEvent.click(change.getByRole("button", { name: "Move item 2 up" }));
    fireEvent.click(change.getByRole("button", { name: "Take out item 2" }));
    fireEvent.click(change.getByRole("button", { name: "Save the application" }));

    expect(await screen.findByTestId("applications-notice")).toHaveTextContent("The application was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/metadata/applications/Sales__c")?.body ?? "{}");
    expect(sent.version).toBe(2);
    expect(sent.definition.items).toEqual([{ type: "OBJECT", object: "Deal__c" }]);
  });

  it("shows the words of the API with the place of a link that leaves the organization", async () => {
    fakeApi({
      ...answers([]),
      [CREATE]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request has problems.",
              fields: { "definition.items[0].path": ["Does not have the allowed shape."] },
            },
          },
          400,
        ),
    });
    render(<ApplicationsPanel />);
    await screen.findByText("No applications yet.");
    const add = form("Add an application");

    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(add.getByRole("button", { name: "Add an item" }));
    fireEvent.click(add.getByRole("button", { name: "Add the application" }));

    expect(await screen.findByTestId("applications-problem")).toHaveTextContent(
      "items[0].path: Does not have the allowed shape.",
    );
  });

  it("puts an application into the chosen change set without an object name", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers([]),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<ApplicationsPanel />);
    await screen.findByText("No applications yet.");

    fireEvent.change(form("Add an application").getByLabelText("Name"), { target: { value: "Sales" } });
    fireEvent.change(form("Add an application").getByLabelText("Label"), { target: { value: "Sales" } });
    fireEvent.click(form("Add an application").getByRole("button", { name: "Add the application" }));

    expect(await screen.findByTestId("applications-notice")).toHaveTextContent("goes live when the set is published");
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_APPLICATION");
    expect(sent.objectApiName).toBeUndefined();
    expect(sent.createApplication.name).toBe("Sales");
  });
});
