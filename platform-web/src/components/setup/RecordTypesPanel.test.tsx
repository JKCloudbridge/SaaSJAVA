import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { tabProps } from "@/test/objectTab";
import { RecordTypesPanel } from "./RecordTypesPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const SECTION = "/setup/objects/Deal__c?section=record-types";

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

const LIST = "GET /api/v1/metadata/objects/Deal__c/record-types";
const LAYOUTS = "GET /api/v1/metadata/objects/Deal__c/layouts";
const ASSIGNMENTS = "GET /api/v1/metadata/objects/Deal__c/record-type-assignments";
const CREATE = "POST /api/v1/metadata/objects/Deal__c/record-types";

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
    field("stage__c", {
      type: "PICKLIST",
      settings: { values: [{ value: "Open", label: "Open", active: true }, { value: "Old", label: "Old", active: false },
        { value: "Won", label: "Won", active: true }] },
    }),
  ],
};
const RENEWAL = {
  apiName: "Renewal__c", label: "Renewal", description: "Second sale", active: true, defaultType: true, allFields: false,
  availableFields: ["code__c", "stage__c"], picklistSubsets: [{ field: "stage__c", values: ["Open"] }], version: 4,
  layout: "Details__c",
};
const DETAILS = { apiName: "Details__c", label: "Details page", description: "", definition: { sections: [] }, version: 1 };

function answers(types: unknown[], extra: Record<string, () => Response> = {}) {
  return {
    [LIST]: () => json({ data: types }),
    [LAYOUTS]: () => json({ data: [DETAILS] }),
    [ASSIGNMENTS]: () => json({ data: [] }),
    "GET /api/v1/profiles": () => json({ data: [] }),
    ...extra,
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

function renderPanel(props = {}) {
  return render(
    <StrictMode>
      <RecordTypesPanel {...tabProps(DEAL, props)} />
    </StrictMode>,
  );
}

describe("RecordTypesPanel", () => {
  it("lists the record types with their layout and status, under React strict mode", async () => {
    fakeApi(answers([RENEWAL]));

    renderPanel();

    const row = await screen.findByTestId("record-type-row");
    expect(screen.getByRole("heading", { name: "Deal — Record types" })).toBeInTheDocument();
    expect(row).toHaveTextContent("Renewal");
    expect(row).toHaveTextContent("Details page");
    expect(row).toHaveTextContent("Default");
    expect(row).toHaveTextContent("Active");
    const trail = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(trail).getByText("Record types")).toHaveAttribute("aria-current", "page");
  });

  it("opens a record type's page with its fields, limited picklists and the profiles that use it", async () => {
    fakeApi(
      answers([RENEWAL], {
        "GET /api/v1/profiles": () => json({ data: [{ id: "p1", name: "Sales", licenceType: "user", members: 1 }] }),
        [ASSIGNMENTS]: () =>
          json({ data: [{ key: "k1", profileId: "p1", recordType: "Renewal__c", defaultForProfile: false, version: 2 }] }),
      }),
    );
    renderPanel();
    await screen.findByTestId("record-type-row");

    fireEvent.click(screen.getByRole("button", { name: "Renewal" }));

    const page = await screen.findByTestId("record-type-detail");
    expect(currentUrl()).toBe(`${SECTION}&recordType=Renewal__c`);
    expect(page).toHaveTextContent("Second sale");
    expect(page).toHaveTextContent("Details page");
    expect(within(page).getByTestId("record-type-fields")).toHaveTextContent("code__c");
    expect(within(page).getByTestId("record-type-limits")).toHaveTextContent("stage__c is limited to Open");
    expect(await within(page).findByLabelText("Sales may use Renewal")).toBeChecked();
  });

  it("adds a record type that offers some fields and limits a picklist, from the form behind New", async () => {
    const calls = fakeApi(answers([], { [CREATE]: () => json({ data: RENEWAL }, 201) }));
    const props = tabProps(DEAL);
    render(<RecordTypesPanel {...props} />);
    await screen.findByText("No record types yet.");

    fireEvent.click(screen.getByRole("button", { name: "New" }));
    expect(currentUrl()).toBe(`${SECTION}&new=1`);
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Renewal" } });
    fireEvent.change(form("Add a record type").getByLabelText("Label"), { target: { value: "Renewal" } });
    fireEvent.click(form("Add a record type").getByLabelText("Offer every field"));
    fireEvent.click(form("Add a record type").getByLabelText(/code__c/));
    fireEvent.click(form("Add a record type").getByLabelText(/stage__c/));
    fireEvent.click(form("Add a record type").getByLabelText("Limit the values of stage__c"));
    fireEvent.click(form("Add a record type").getByLabelText("Won"));
    fireEvent.click(screen.getByRole("button", { name: "Add the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was added.");
    expect(currentUrl()).toBe(SECTION);
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent.name).toBe("Renewal");
    expect(sent.availableFields).toEqual(["code__c", "stage__c"]);
    expect(sent.picklistSubsets).toEqual([{ field: "stage__c", values: ["Open"] }]);
    expect(props.reload).toHaveBeenCalled();
  });

  it("offers only the active values of a picklist", async () => {
    fakeApi(answers([]));
    setUrl(`${SECTION}&new=1`);
    renderPanel();
    await screen.findByRole("form", { name: "Add a record type" });

    fireEvent.click(form("Add a record type").getByLabelText("Limit the values of stage__c"));

    expect(form("Add a record type").getByLabelText("Open")).toBeInTheDocument();
    expect(form("Add a record type").queryByLabelText("Old")).toBeNull();
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(
      answers([], { "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }),
    );
    setUrl(`${SECTION}&new=1`);
    renderPanel();
    await screen.findByRole("form", { name: "Add a record type" });

    fireEvent.change(form("Add a record type").getByLabelText("Name"), { target: { value: "Renewal" } });
    fireEvent.change(form("Add a record type").getByLabelText("Label"), { target: { value: "Renewal" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_RECORD_TYPE");
    expect(sent.objectApiName).toBe("Deal__c");
    expect(sent.createRecordType.name).toBe("Renewal");
  });

  it("shows the API's words, with the dependent named, when a record type cannot be saved, and stays on the form", async () => {
    fakeApi(
      answers([], {
        [CREATE]: () =>
          json(
            {
              error: {
                code: "CONFLICT",
                message: "Record type Odd__c of Deal__c does not offer the required field Deal__c.code__c: add the field.",
                fields: { dependencies: ["Record type Odd__c of Deal__c does not offer the required field."] },
              },
            },
            409,
          ),
      }),
    );
    setUrl(`${SECTION}&new=1`);
    renderPanel();
    await screen.findByRole("form", { name: "Add a record type" });

    fireEvent.change(form("Add a record type").getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the record type" }));

    const problem = await screen.findByTestId("record-types-problem");
    expect(problem).toHaveTextContent("does not offer the required field Deal__c.code__c");
    expect(problem).toHaveTextContent("Needed by:");
    expect(screen.getByRole("form", { name: "Add a record type" })).toBeInTheDocument();
  });

  it("changes a record type with the version it was read at, keeping its name fixed", async () => {
    const calls = fakeApi(
      answers([RENEWAL], { "PUT /api/v1/metadata/objects/Deal__c/record-types/Renewal__c": () => json({ data: RENEWAL }) }),
    );
    renderPanel();
    await screen.findByTestId("record-type-row");
    fireEvent.click(screen.getByRole("button", { name: "Renewal" }));
    const page = await screen.findByTestId("record-type-detail");

    fireEvent.click(within(page).getByRole("button", { name: "Edit" }));
    expect(form("Change the record type").queryByLabelText("Name")).toBeNull();
    fireEvent.change(form("Change the record type").getByLabelText("Label"), { target: { value: "Renewals" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was saved.");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}");
    expect(sent.version).toBe(4);
    expect(sent.label).toBe("Renewals");
    expect(sent.availableFields).toEqual(["code__c", "stage__c"]);
    expect(sent.layout).toBe("Details__c");
  });

  it("removes a record type after a second click and goes back to the list", async () => {
    const calls = fakeApi(
      answers([RENEWAL], {
        "DELETE /api/v1/metadata/objects/Deal__c/record-types/Renewal__c": () => new Response(null, { status: 204 }),
      }),
    );
    renderPanel();
    await screen.findByTestId("record-type-row");
    fireEvent.click(screen.getByRole("button", { name: "Renewal" }));
    const page = await screen.findByTestId("record-type-detail");

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was removed.");
    expect(currentUrl()).toBe(SECTION);
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(true);
  });

  it("says plainly that a record type that is not there any more does not exist", async () => {
    fakeApi(answers([RENEWAL]));
    setUrl(`${SECTION}&recordType=Gone__c`);
    renderPanel();

    expect(await screen.findByText("This record type does not exist any more.")).toBeInTheDocument();
  });

  it("says that an object whose records belong to the platform has no record types, and asks nothing", async () => {
    const calls = fakeApi();

    render(<RecordTypesPanel {...tabProps({ ...DEAL, managedBy: "identity" })} />);

    expect(await screen.findByText(/belong to the platform/)).toBeInTheDocument();
    expect(calls.some((call) => call.key === LIST)).toBe(false);
  });
});
