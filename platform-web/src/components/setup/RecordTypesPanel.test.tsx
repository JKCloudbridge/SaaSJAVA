import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordTypesPanel } from "./RecordTypesPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/metadata/objects/Deal__c/record-types";
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
};

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("RecordTypesPanel", () => {
  it("lists the record types with their fields, limited picklists and default, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json({ data: [RENEWAL] }) });

    render(
      <StrictMode>
        <RecordTypesPanel object={DEAL} onChanged={vi.fn()} />
      </StrictMode>,
    );

    const row = await screen.findByTestId("record-type-row");
    expect(row).toHaveTextContent("Renewal");
    expect(row).toHaveTextContent("2 fields");
    expect(row).toHaveTextContent("1 picklist limited");
    expect(row).toHaveTextContent("The default");
  });

  it("adds a record type that offers some fields and limits a picklist to the offered values", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      [CREATE]: () => json({ data: RENEWAL }, 201),
    });
    const changed = vi.fn();
    render(<RecordTypesPanel object={DEAL} onChanged={changed} />);
    await screen.findByText("No record types yet.");

    fireEvent.change(form("Add a record type").getByLabelText("Name"), { target: { value: "Renewal" } });
    fireEvent.change(form("Add a record type").getByLabelText("Label"), { target: { value: "Renewal" } });
    fireEvent.click(form("Add a record type").getByLabelText("Offer every field"));
    fireEvent.click(form("Add a record type").getByLabelText(/code__c/));
    fireEvent.click(form("Add a record type").getByLabelText(/stage__c/));
    fireEvent.click(form("Add a record type").getByLabelText("Limit the values of stage__c"));
    fireEvent.click(form("Add a record type").getByLabelText("Won"));
    fireEvent.click(form("Add a record type").getByRole("button", { name: "Add the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was added.");
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent.name).toBe("Renewal");
    expect(sent.availableFields).toEqual(["code__c", "stage__c"]);
    expect(sent.picklistSubsets).toEqual([{ field: "stage__c", values: ["Open"] }]);
    expect(changed).toHaveBeenCalled();
  });

  it("offers only the active values of a picklist", async () => {
    fakeApi({ [LIST]: () => json({ data: [] }) });
    render(<RecordTypesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record types yet.");

    fireEvent.click(form("Add a record type").getByLabelText("Limit the values of stage__c"));

    expect(form("Add a record type").getByLabelText("Open")).toBeInTheDocument();
    expect(form("Add a record type").queryByLabelText("Old")).toBeNull();
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<RecordTypesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record types yet.");

    fireEvent.change(form("Add a record type").getByLabelText("Name"), { target: { value: "Renewal" } });
    fireEvent.change(form("Add a record type").getByLabelText("Label"), { target: { value: "Renewal" } });
    fireEvent.click(form("Add a record type").getByRole("button", { name: "Add the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_RECORD_TYPE");
    expect(sent.objectApiName).toBe("Deal__c");
    expect(sent.createRecordType.name).toBe("Renewal");
  });

  it("shows the API's words, with the dependent named, when a record type cannot be saved", async () => {
    fakeApi({
      [LIST]: () => json({ data: [] }),
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
    });
    render(<RecordTypesPanel object={DEAL} onChanged={vi.fn()} />);
    await screen.findByText("No record types yet.");

    fireEvent.change(form("Add a record type").getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(form("Add a record type").getByRole("button", { name: "Add the record type" }));

    const problem = await screen.findByTestId("record-types-problem");
    expect(problem).toHaveTextContent("does not offer the required field Deal__c.code__c");
    expect(problem).toHaveTextContent("Needed by:");
  });

  it("changes a record type with the version it was read at, keeping its name fixed", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [RENEWAL] }),
      "PUT /api/v1/metadata/objects/Deal__c/record-types/Renewal__c": () => json({ data: RENEWAL }),
    });
    render(<RecordTypesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-type-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    expect(form("Change the record type").queryByLabelText("Name")).toBeNull();
    fireEvent.change(form("Change the record type").getByLabelText("Label"), { target: { value: "Renewals" } });
    fireEvent.click(form("Change the record type").getByRole("button", { name: "Save the record type" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was saved.");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}");
    expect(sent.version).toBe(4);
    expect(sent.label).toBe("Renewals");
    expect(sent.availableFields).toEqual(["code__c", "stage__c"]);
  });

  it("removes a record type", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [RENEWAL] }),
      "DELETE /api/v1/metadata/objects/Deal__c/record-types/Renewal__c": () => new Response(null, { status: 204 }),
    });
    render(<RecordTypesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-type-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("record-types-notice")).toHaveTextContent("The record type was removed.");
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(true);
  });

  it("says that an object whose records belong to the platform has no record types, and asks nothing", async () => {
    const calls = fakeApi();

    render(<RecordTypesPanel object={{ ...DEAL, managedBy: "identity" }} onChanged={vi.fn()} />);

    expect(await screen.findByText(/belong to the platform/)).toBeInTheDocument();
    expect(calls.some((call) => call.key === LIST)).toBe(false);
  });
});
