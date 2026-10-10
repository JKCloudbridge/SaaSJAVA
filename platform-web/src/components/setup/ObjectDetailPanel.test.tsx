import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ObjectDetailPanel } from "./ObjectDetailPanel";

const state = vi.hoisted(() => ({ object: "Employee__c", push: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ objectApiName: state.object }),
  useRouter: () => ({ push: state.push }),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  state.object = "Employee__c";
  state.push.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const NONE = {};
const TYPES = {
  data: [
    { type: "TEXT", label: "Text", description: "A short line of text.", settings: ["maxLength"], allowsRequired: true, allowsUnique: true, allowsDefault: true, calculated: false, formulaResult: true },
    { type: "BOOLEAN", label: "Checkbox", description: "Yes or no.", settings: [], allowsRequired: false, allowsUnique: false, allowsDefault: true, calculated: false, formulaResult: true },
    { type: "PICKLIST", label: "Picklist", description: "One choice.", settings: ["values"], allowsRequired: true, allowsUnique: false, allowsDefault: true, calculated: false, formulaResult: false },
    { type: "LOOKUP", label: "Lookup", description: "A link.", settings: ["targetObject"], allowsRequired: true, allowsUnique: false, allowsDefault: false, calculated: false, formulaResult: false },
    { type: "FORMULA", label: "Formula", description: "Worked out.", settings: ["expression", "resultType"], allowsRequired: false, allowsUnique: false, allowsDefault: false, calculated: true, formulaResult: false },
  ],
};
const OBJECTS = {
  data: [
    { apiName: "Account", label: "Account", pluralLabel: "Accounts", kind: "STANDARD", fieldCount: 3, customFieldCount: 0 },
    { apiName: "Employee__c", label: "Employee", pluralLabel: "Employees", kind: "CUSTOM", fieldCount: 3, customFieldCount: 1 },
  ],
};
const SYSTEM_FIELD = {
  apiName: "id", label: "Record ID", description: "The permanent identifier.", kind: "SYSTEM", type: "TEXT",
  required: false, unique: true, settings: { maxLength: 36 }, retired: false, editable: false, version: 0,
};
const CODE_FIELD = {
  apiName: "code__c", label: "Code", description: "", kind: "CUSTOM", type: "TEXT",
  required: true, unique: false, settings: { maxLength: 20 }, retired: false, editable: true, version: 3,
};
const EMPLOYEE = {
  apiName: "Employee__c", label: "Employee", pluralLabel: "Employees", description: "People who work here.",
  kind: "CUSTOM", editable: true, extensible: true, version: 2, fields: [SYSTEM_FIELD, CODE_FIELD],
};
const ACCOUNT = {
  apiName: "Account", label: "Account", pluralLabel: "Accounts", description: "A company.", kind: "STANDARD",
  editable: false, extensible: true, version: 0,
  fields: [SYSTEM_FIELD, { ...CODE_FIELD, apiName: "name", label: "Account name", kind: "STANDARD", editable: false }],
};
const USER = { ...ACCOUNT, apiName: "User", label: "User", managedBy: "identity", extensible: false };

function addForm() {
  return within(screen.getByRole("form", { name: "Add a field" }));
}

function changeForm() {
  return within(screen.getByRole("form", { name: "Change the field" }));
}

function answers(object: unknown, extra: Record<string, () => Response> = NONE) {
  return {
    "GET /api/v1/metadata/objects/Employee__c": () => json({ data: object }),
    "GET /api/v1/metadata/objects/Account": () => json({ data: object }),
    "GET /api/v1/metadata/objects/User": () => json({ data: object }),
    "GET /api/v1/metadata/field-types": () => json(TYPES),
    "GET /api/v1/metadata/objects": () => json(OBJECTS),
    ...extra,
  };
}

describe("ObjectDetailPanel", () => {
  it("shows the fields in the order the API sent, with who defined each, under React strict mode", async () => {
    fakeApi(answers(EMPLOYEE));
    render(
      <StrictMode>
        <ObjectDetailPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("field-row");

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Record ID");
    expect(rows[0]).toHaveTextContent("System");
    expect(within(rows[0] as HTMLElement).queryByRole("button", { name: "Change" })).toBeNull();
    expect(rows[1]).toHaveTextContent("code__c");
    expect(rows[1]).toHaveTextContent("required");
    expect(rows[1]).toHaveTextContent("up to 20 characters");
    expect(within(rows[1] as HTMLElement).getByRole("button", { name: "Change" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Employee/ })).toBeInTheDocument();
  });

  it("offers neither changing nor removing for an object the platform defines", async () => {
    state.object = "Account";
    fakeApi(answers(ACCOUNT));
    render(<ObjectDetailPanel />);

    const rows = await screen.findAllByTestId("field-row");

    expect(screen.getByText(/defined by the platform/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove the object" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Save the labels" })).toBeNull();
    expect(within(rows[1] as HTMLElement).queryByRole("button", { name: "Change" })).toBeNull();
    expect(screen.getByRole("button", { name: "Add the field" })).toBeInTheDocument();
  });

  it("offers no field form for an object the platform does not allow extra fields on", async () => {
    state.object = "User";
    fakeApi(answers(USER));
    render(<ObjectDetailPanel />);

    await screen.findAllByTestId("field-row");

    expect(screen.getByText("The platform does not allow extra fields on this object.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add the field" })).toBeNull();
    expect(screen.getByText(/Its records are managed by identity/)).toBeInTheDocument();
  });

  it("adds a field with the settings of its type, and only those", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/objects/Employee__c/fields": () => json({ data: CODE_FIELD }, 201),
      }),
    );
    render(<ObjectDetailPanel />);
    await screen.findAllByTestId("field-row");

    fireEvent.change(addForm().getByLabelText("Name"), { target: { value: "badge" } });
    fireEvent.change(addForm().getByLabelText("Label"), { target: { value: "Badge" } });
    fireEvent.change(addForm().getByLabelText("Maximum length"), { target: { value: "12" } });
    fireEvent.click(addForm().getByLabelText("Required"));
    fireEvent.click(addForm().getByRole("button", { name: "Add the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was added.");
    expect(calls.find((call) => call.key === "POST /api/v1/metadata/objects/Employee__c/fields")?.body).toBe(
      JSON.stringify({
        label: "Badge",
        description: "",
        required: true,
        unique: false,
        settings: { maxLength: 12 },
        name: "badge",
        type: "TEXT",
      }),
    );
  });

  it("builds the form from the field type: a picklist asks for values, a checkbox cannot be required", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/objects/Employee__c/fields": () => json({ data: CODE_FIELD }, 201),
      }),
    );
    render(<ObjectDetailPanel />);
    await screen.findAllByTestId("field-row");

    fireEvent.change(addForm().getByLabelText("Type"), { target: { value: "BOOLEAN" } });
    expect(addForm().queryByLabelText("Required")).toBeNull();
    expect(addForm().queryByLabelText("Maximum length")).toBeNull();

    fireEvent.change(addForm().getByLabelText("Type"), { target: { value: "PICKLIST" } });
    fireEvent.click(addForm().getByRole("button", { name: "Add a value" }));
    fireEvent.change(addForm().getByLabelText("Value 1"), { target: { value: "Red" } });
    fireEvent.change(addForm().getByLabelText("Label of value 1"), { target: { value: "Red colour" } });
    fireEvent.change(addForm().getByLabelText("Name"), { target: { value: "colour" } });
    fireEvent.change(addForm().getByLabelText("Label"), { target: { value: "Colour" } });
    fireEvent.click(addForm().getByRole("button", { name: "Add the field" }));

    await screen.findByTestId("object-notice");
    const body = JSON.parse(calls.find((call) => call.key.startsWith("POST"))?.body ?? "{}");
    expect(body.type).toBe("PICKLIST");
    expect(body.settings).toEqual({ values: [{ value: "Red", label: "Red colour", active: true }] });
  });

  it("shows the API's own words with the invalid settings named when a field is refused", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/objects/Employee__c/fields": () =>
          json(
            {
              error: {
                code: "VALIDATION_ERROR",
                message: "The request is invalid.",
                fields: { "settings.maxLength": ["Must be between 1 and 255."], defaultValue: ["Must be true or false."] },
              },
            },
            400,
          ),
      }),
    );
    render(<ObjectDetailPanel />);
    await screen.findAllByTestId("field-row");

    fireEvent.change(addForm().getByLabelText("Name"), { target: { value: "badge" } });
    fireEvent.click(addForm().getByRole("button", { name: "Add the field" }));

    const problem = await screen.findByTestId("object-problem");
    expect(problem).toHaveTextContent("Maximum length: Must be between 1 and 255.");
    expect(problem).toHaveTextContent("Default value: Must be true or false.");
  });

  it("changes a field with the version it was read at, and keeps its type and name fixed", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "PUT /api/v1/metadata/objects/Employee__c/fields/code__c": () => json({ data: CODE_FIELD }),
      }),
    );
    render(<ObjectDetailPanel />);
    const rows = await screen.findAllByTestId("field-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Change" }));
    expect(changeForm().queryByLabelText("Type")).toBeNull();
    expect(screen.getByText(/never change/)).toBeInTheDocument();
    fireEvent.change(changeForm().getByLabelText("Label"), { target: { value: "Product code" } });
    fireEvent.click(changeForm().getByRole("button", { name: "Save the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was saved.");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}");
    expect(sent.version).toBe(3);
    expect(sent.label).toBe("Product code");
    expect(sent.settings).toEqual({ maxLength: 20 });
    expect(sent.type).toBeUndefined();
  });

  it("shows the API's words when the object was changed by someone else", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "PUT /api/v1/metadata/objects/Employee__c": () =>
          refusal("CONCURRENT_MODIFICATION", "The resource was changed by someone else. Reload it and try again.", 409),
      }),
    );
    render(<ObjectDetailPanel />);
    await screen.findAllByTestId("field-row");

    fireEvent.click(screen.getByRole("button", { name: "Save the labels" }));

    expect(await screen.findByTestId("object-problem")).toHaveTextContent("changed by someone else");
  });

  it("removes a field, and removes the object only after a second click, then returns to the list", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c": () => new Response(null, { status: 204 }),
        "DELETE /api/v1/metadata/objects/Employee__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<ObjectDetailPanel />);
    const rows = await screen.findAllByTestId("field-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Remove" }));
    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was removed.");

    fireEvent.click(screen.getByRole("button", { name: "Remove the object" }));
    expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/objects/Employee__c")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the object" }));
    await vi.waitFor(() => expect(state.push).toHaveBeenCalledWith("/setup/objects"));
    expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/objects/Employee__c")).toBe(true);
  });

  it("asks for what happens to a record when the other record is removed, and for the list label, from the type", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "GET /api/v1/metadata/field-types": () =>
          json({
            data: [
              ...TYPES.data,
              { type: "REF", label: "Link", description: "A link.", settings: ["targetObject", "onDelete", "listLabel"], allowsRequired: true, allowsUnique: true, allowsDefault: false, calculated: false, formulaResult: false },
              { type: "OWNED", label: "Owned link", description: "A link to the owner.", settings: ["targetObject", "reparentable", "listLabel"], allowsRequired: false, allowsUnique: true, allowsDefault: false, calculated: false, formulaResult: false },
            ],
          }),
        "POST /api/v1/metadata/objects/Employee__c/fields": () => json({ data: CODE_FIELD }, 201),
      }),
    );
    render(<ObjectDetailPanel />);
    await screen.findAllByTestId("field-row");

    fireEvent.change(addForm().getByLabelText("Type"), { target: { value: "REF" } });
    expect(addForm().queryByLabelText("A record may be moved to another master")).toBeNull();
    fireEvent.change(addForm().getByLabelText("Name"), { target: { value: "department" } });
    fireEvent.change(addForm().getByLabelText("Label"), { target: { value: "Department" } });
    fireEvent.change(addForm().getByLabelText("Points to"), { target: { value: "Account" } });
    fireEvent.change(addForm().getByLabelText("When the other record is removed"), { target: { value: "REFUSE" } });
    fireEvent.change(addForm().getByLabelText("Label of the list on the other object"), { target: { value: "Staff" } });
    fireEvent.click(addForm().getByLabelText(/Unique/));
    fireEvent.click(addForm().getByRole("button", { name: "Add the field" }));

    await screen.findByTestId("object-notice");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("POST"))?.body ?? "{}");
    expect(sent.unique).toBe(true);
    expect(sent.settings).toEqual({ targetObject: "Account", onDelete: "REFUSE", listLabel: "Staff" });

    fireEvent.change(addForm().getByLabelText("Type"), { target: { value: "OWNED" } });
    expect(addForm().queryByLabelText("When the other record is removed")).toBeNull();
    fireEvent.click(addForm().getByLabelText("A record may be moved to another master"));
    fireEvent.click(addForm().getByRole("button", { name: "Add the field" }));
    await vi.waitFor(() => expect(calls.filter((call) => call.key.startsWith("POST"))).toHaveLength(2));
    const second = JSON.parse(calls.filter((call) => call.key.startsWith("POST"))[1]?.body ?? "{}");
    expect(second.settings.reparentable).toBe(true);
    expect(second.settings.onDelete).toBeUndefined();
  });

  it("records a change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
        "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<ObjectDetailPanel />);
    const rows = await screen.findAllByTestId("field-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_FIELD", objectApiName: "Employee__c", itemApiName: "code__c" });
    window.sessionStorage.clear();
  });

  it("shows the relationships and the record types of the object", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "GET /api/v1/metadata/objects/Employee__c/relationships": () =>
          json({ data: { parents: [], children: [], manyToMany: [] } }),
        "GET /api/v1/metadata/objects/Employee__c/record-types": () => json({ data: [] }),
      }),
    );
    render(<ObjectDetailPanel />);

    expect(await screen.findByRole("heading", { name: "Relationships" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Record types" })).toBeInTheDocument();
  });

  it("says in the API's words when removing a field is refused because a record type needs it", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c": () =>
          refusal(
            "CONFLICT",
            "record type Remote__c of Employee__c offers field Employee__c.code__c, which does not exist after this change.",
            409,
          ),
      }),
    );
    render(<ObjectDetailPanel />);
    const rows = await screen.findAllByTestId("field-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("object-problem")).toHaveTextContent("record type Remote__c of Employee__c offers field");
  });

  it("shows what the API answered for an object that does not exist", async () => {
    state.object = "Ghost__c";
    fakeApi({
      "GET /api/v1/metadata/objects/Ghost__c": () => refusal("NOT_FOUND", "This object does not exist.", 404),
    });
    render(<ObjectDetailPanel />);

    expect(await screen.findByTestId("object-failed")).toHaveTextContent("This object does not exist.");
  });
});
