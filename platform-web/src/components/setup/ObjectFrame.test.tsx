import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { publishOpenObject, useOpenObject } from "@/lib/shell/objectContext";
import { ObjectFrame } from "./ObjectFrame";

const state = vi.hoisted(() => ({ object: "Employee__c" }));
vi.mock("next/navigation", async () => ({
  ...(await import("@/test/navigation")).navigationMock,
  useParams: () => ({ objectApiName: state.object }),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

function open(section?: string, extra = "") {
  const query = [section ? `section=${section}` : "", extra].filter(Boolean).join("&");
  setUrl(`/setup/objects/${state.object}${query ? `?${query}` : ""}`);
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  state.object = "Employee__c";
  open("fields");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  publishOpenObject(undefined);
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
  required: false, unique: true, indexed: true, settings: { maxLength: 36 }, retired: false, editable: false, version: 0,
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

function answers(object: unknown, extra: Record<string, () => Response> = NONE) {
  const name = (object as { apiName: string }).apiName;
  return {
    [`GET /api/v1/metadata/objects/${name}`]: () => json({ data: object }),
    "GET /api/v1/metadata/field-types": () => json(TYPES),
    "GET /api/v1/metadata/objects": () => json(OBJECTS),
    ...extra,
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

/** Opens the page of a field by its name in the list. */
async function openField(label: string) {
  const rows = await screen.findAllByTestId("field-row");
  expect(rows.length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole("button", { name: label }));
  return screen.findByTestId("field-detail");
}

describe("ObjectFrame: the Fields tab", () => {
  it("lists the fields with label, API name and type, under React strict mode, and says the object's name in the title", async () => {
    fakeApi(answers(EMPLOYEE));
    render(
      <StrictMode>
        <ObjectFrame />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("field-row");

    expect(screen.getByRole("heading", { name: "Employee — Fields" })).toBeInTheDocument();
    const trail = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(trail).getByRole("link", { name: "Object settings" })).toHaveAttribute("href", "/setup/objects");
    expect(within(trail).getByRole("link", { name: "Employee" })).toHaveAttribute(
      "href",
      "/setup/objects/Employee__c?section=fields",
    );
    expect(within(trail).getByText("Fields")).toHaveAttribute("aria-current", "page");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Record ID");
    expect(rows[0]).toHaveTextContent("id");
    expect(rows[1]).toHaveTextContent("Code");
    expect(rows[1]).toHaveTextContent("code__c");
    expect(rows[1]).toHaveTextContent("Text");
    // The old long page's description paragraphs are gone.
    expect(screen.queryByText("People who work here.")).not.toBeInTheDocument();
    expect(screen.queryByText(/custom object\./i)).not.toBeInTheDocument();
  });

  it("tells the shell the object's name for the rail, and forgets it when the page goes", async () => {
    fakeApi(answers(EMPLOYEE));
    function Probe() {
      return <span data-testid="published">{useOpenObject()?.label ?? "nothing"}</span>;
    }
    const { unmount } = render(
      <>
        <ObjectFrame />
        <Probe />
      </>,
    );

    await screen.findAllByTestId("field-row");
    await vi.waitFor(() => expect(screen.getByTestId("published")).toHaveTextContent("Employee"));
    unmount();
    publishOpenObject(undefined);
  });

  it("opens a field's page with its details, the settings of its type and who made it", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "GET /api/v1/metadata/objects/Employee__c/authorship": () =>
          json({
            data: [
              {
                kind: "FIELD",
                apiName: "code__c",
                createdBy: "Member A",
                createdAt: "2026-10-01T10:00:00Z",
                updatedBy: "Member B",
                updatedAt: "2026-10-02T10:00:00Z",
              },
            ],
          }),
      }),
    );
    render(<ObjectFrame />);

    const detail = await openField("Code");

    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields&field=code__c");
    expect(within(detail).getByRole("heading", { name: "Code" })).toBeInTheDocument();
    expect(detail).toHaveTextContent("code__c");
    expect(detail).toHaveTextContent("Required");
    expect(detail).toHaveTextContent("Maximum length");
    expect(detail).toHaveTextContent("20");
    await vi.waitFor(() => expect(detail).toHaveTextContent("Member A"));
    expect(detail).toHaveTextContent("Member B");
    const trail = within(detail).getByRole("navigation", { name: "Breadcrumb" });
    expect(within(trail).getByRole("button", { name: "Fields" })).toBeInTheDocument();
  });

  it("says the platform made a field of the platform, and shows a dash where a name cannot be shown", async () => {
    fakeApi(answers(EMPLOYEE));
    render(<ObjectFrame />);

    const detail = await openField("Record ID");

    expect(detail).toHaveTextContent("Created by");
    expect(detail).toHaveTextContent("Platform");
    expect(within(detail).queryByRole("button", { name: "Edit" })).toBeNull();
    expect(within(detail).queryByRole("button", { name: "Remove" })).toBeNull();
  });

  it("goes back to the list from the field's page", async () => {
    fakeApi(answers(EMPLOYEE));
    render(<ObjectFrame />);
    const detail = await openField("Code");

    fireEvent.click(within(detail).getByRole("button", { name: "Fields" }));

    expect(await screen.findByTestId("fields")).toBeInTheDocument();
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields");
  });

  it("says plainly that a field that is not there any more does not exist", async () => {
    open("fields", "field=gone__c");
    fakeApi(answers(EMPLOYEE));
    render(<ObjectFrame />);

    expect(await screen.findByText("This field does not exist any more.")).toBeInTheDocument();
  });

  it("searches the fields", async () => {
    fakeApi(answers(EMPLOYEE));
    render(<ObjectFrame />);
    await screen.findAllByTestId("field-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search fields" }), { target: { value: "code" } });

    expect(screen.getAllByTestId("field-row")).toHaveLength(1);
  });

  it("offers no New and no Edit for an object the platform defines and no extra fields on one that does not allow them", async () => {
    state.object = "User";
    open("fields");
    fakeApi(answers(USER));
    render(<ObjectFrame />);

    await screen.findAllByTestId("field-row");

    expect(screen.getByText("The platform does not allow extra fields on this object.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New" })).toBeNull();
  });

  it("offers New on a standard object that allows extra fields but no Edit on its own fields", async () => {
    state.object = "Account";
    open("fields");
    fakeApi(answers(ACCOUNT));
    render(<ObjectFrame />);
    await screen.findAllByTestId("field-row");

    expect(screen.getByRole("button", { name: "New" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account name" }));
    const detail = await screen.findByTestId("field-detail");
    expect(within(detail).queryByRole("button", { name: "Edit" })).toBeNull();
  });

  it("adds a field with the settings of its type, and only those, from the form behind New", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/objects/Employee__c/fields": () => json({ data: CODE_FIELD }, 201),
      }),
    );
    render(<ObjectFrame />);
    await screen.findAllByTestId("field-row");

    expect(screen.queryByRole("form", { name: "Add a field" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "New" }));
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields&new=1");

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "badge" } });
    fireEvent.change(form("Add a field").getByLabelText("Label"), { target: { value: "Badge" } });
    fireEvent.change(form("Add a field").getByLabelText("Maximum length"), { target: { value: "12" } });
    fireEvent.click(form("Add a field").getByLabelText("Required"));
    fireEvent.click(screen.getByRole("button", { name: "Add the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was added.");
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields");
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
    open("fields", "new=1");
    render(<ObjectFrame />);
    await screen.findByRole("form", { name: "Add a field" });

    fireEvent.change(form("Add a field").getByLabelText("Type"), { target: { value: "BOOLEAN" } });
    expect(form("Add a field").queryByLabelText("Required")).toBeNull();
    expect(form("Add a field").queryByLabelText("Maximum length")).toBeNull();

    fireEvent.change(form("Add a field").getByLabelText("Type"), { target: { value: "PICKLIST" } });
    fireEvent.click(form("Add a field").getByRole("button", { name: "Add a value" }));
    fireEvent.change(form("Add a field").getByLabelText("Value 1"), { target: { value: "Red" } });
    fireEvent.change(form("Add a field").getByLabelText("Label of value 1"), { target: { value: "Red colour" } });
    fireEvent.change(form("Add a field").getByLabelText("Name"), { target: { value: "colour" } });
    fireEvent.change(form("Add a field").getByLabelText("Label"), { target: { value: "Colour" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the field" }));

    await screen.findByTestId("object-notice");
    const body = JSON.parse(calls.find((call) => call.key.startsWith("POST"))?.body ?? "{}");
    expect(body.type).toBe("PICKLIST");
    expect(body.settings).toEqual({ values: [{ value: "Red", label: "Red colour", active: true }] });
  });

  it("shows the API's own words with the invalid settings named when a field is refused, and stays on the form", async () => {
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
    open("fields", "new=1");
    render(<ObjectFrame />);
    await screen.findByRole("form", { name: "Add a field" });

    fireEvent.change(form("Add a field").getByLabelText("Name"), { target: { value: "badge" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the field" }));

    const problem = await screen.findByTestId("object-problem");
    expect(problem).toHaveTextContent("Maximum length: Must be between 1 and 255.");
    expect(problem).toHaveTextContent("Default value: Must be true or false.");
    expect(screen.getByRole("form", { name: "Add a field" })).toBeInTheDocument();
  });

  it("changes a field with the version it was read at, and keeps its type and name fixed", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "PUT /api/v1/metadata/objects/Employee__c/fields/code__c": () => json({ data: CODE_FIELD }),
      }),
    );
    render(<ObjectFrame />);
    const detail = await openField("Code");

    fireEvent.click(within(detail).getByRole("button", { name: "Edit" }));
    expect(form("Change the field").queryByLabelText("Type")).toBeNull();
    expect(screen.getByText(/never change/)).toBeInTheDocument();
    fireEvent.change(form("Change the field").getByLabelText("Label"), { target: { value: "Product code" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was saved.");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}");
    expect(sent.version).toBe(3);
    expect(sent.label).toBe("Product code");
    expect(sent.settings).toEqual({ maxLength: 20 });
    expect(sent.type).toBeUndefined();
    expect(screen.queryByRole("form", { name: "Change the field" })).toBeNull();
  });

  it("removes a field only after a second click, then goes back to the list with a notice", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<ObjectFrame />);
    const detail = await openField("Code");

    fireEvent.click(within(detail).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("The field was removed.");
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields");
    expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c")).toBe(true);
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
    render(<ObjectFrame />);
    const detail = await openField("Code");

    fireEvent.click(within(detail).getByRole("button", { name: "Remove" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the field" }));

    expect(await screen.findByTestId("object-problem")).toHaveTextContent("record type Remote__c of Employee__c offers field");
  });

  it("records a change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
        "DELETE /api/v1/metadata/objects/Employee__c/fields/code__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<ObjectFrame />);
    const detail = await openField("Code");

    fireEvent.click(within(detail).getByRole("button", { name: "Remove" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the field" }));

    expect(await screen.findByTestId("object-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_FIELD", objectApiName: "Employee__c", itemApiName: "code__c" });
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
    open("fields", "new=1");
    render(<ObjectFrame />);
    await screen.findByRole("form", { name: "Add a field" });

    fireEvent.change(form("Add a field").getByLabelText("Type"), { target: { value: "REF" } });
    expect(form("Add a field").queryByLabelText("A record may be moved to another master")).toBeNull();
    fireEvent.change(form("Add a field").getByLabelText("Name"), { target: { value: "department" } });
    fireEvent.change(form("Add a field").getByLabelText("Label"), { target: { value: "Department" } });
    fireEvent.change(form("Add a field").getByLabelText("Points to"), { target: { value: "Account" } });
    fireEvent.change(form("Add a field").getByLabelText("When the other record is removed"), { target: { value: "REFUSE" } });
    fireEvent.change(form("Add a field").getByLabelText("Label of the list on the other object"), { target: { value: "Staff" } });
    fireEvent.click(form("Add a field").getByLabelText(/Unique/));
    fireEvent.click(screen.getByRole("button", { name: "Add the field" }));

    await screen.findByTestId("object-notice");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("POST"))?.body ?? "{}");
    expect(sent.unique).toBe(true);
    expect(sent.settings).toEqual({ targetObject: "Account", onDelete: "REFUSE", listLabel: "Staff" });
  });

  it("says in the API's words that a stored formula is not valid and is not calculated", async () => {
    const formula = {
      ...CODE_FIELD,
      apiName: "total__c",
      label: "Total",
      type: "FORMULA",
      required: false,
      settings: { expression: "quantity * 2 +", resultType: "TEXT" },
      problem: "Line 1, column 15: The expression ends where a value is missing.",
    };
    fakeApi(answers({ ...EMPLOYEE, fields: [SYSTEM_FIELD, formula] }));
    render(<ObjectFrame />);

    const detail = await openField("Total");
    const note = within(detail).getByTestId("formula-problem");

    expect(note).toHaveTextContent("This formula is not valid, so it is not calculated.");
    expect(note).toHaveTextContent("Line 1, column 15: The expression ends where a value is missing.");
    expect(within(detail).getByText("quantity * 2 +")).toBeInTheDocument();
  });

  it("marks a formula that is not valid in the list", async () => {
    const formula = { ...CODE_FIELD, apiName: "total__c", label: "Total", type: "FORMULA", settings: {}, problem: "No." };
    fakeApi(answers({ ...EMPLOYEE, fields: [SYSTEM_FIELD, formula] }));
    render(<ObjectFrame />);

    const rows = await screen.findAllByTestId("field-row");

    expect(rows[1]).toHaveTextContent("Formula not valid");
  });

  it("offers to check a formula when the type Formula is chosen and asks the API", async () => {
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "POST /api/v1/metadata/expressions/check": () =>
          json({ data: { valid: true, type: "TEXT", fields: ["code__c"], usesClock: false } }),
      }),
    );
    open("fields", "new=1");
    render(<ObjectFrame />);
    await screen.findByRole("form", { name: "Add a field" });

    fireEvent.change(form("Add a field").getByLabelText("Type"), { target: { value: "FORMULA" } });
    fireEvent.change(form("Add a field").getByLabelText("Formula"), { target: { value: "code__c" } });
    fireEvent.change(form("Add a field").getByLabelText("Result"), { target: { value: "TEXT" } });
    fireEvent.click(form("Add a field").getByRole("button", { name: "Check the formula" }));

    expect(await screen.findByTestId("formula-check-valid")).toHaveTextContent("It is valid. It gives text.");
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/expressions/check")?.body ?? "{}");
    expect(sent).toEqual({ objectApiName: "Employee__c", expression: "code__c", purpose: "FORMULA", resultType: "TEXT" });
  });
});

describe("ObjectFrame: the object and its tabs", () => {
  it("falls back to the fields for a tab it does not know", async () => {
    open("nonsense");
    fakeApi(answers(EMPLOYEE));
    render(<ObjectFrame />);

    expect(await screen.findByTestId("fields")).toBeInTheDocument();
  });

  it("shows the relationships and the record types as lists of their own", async () => {
    fakeApi(
      answers(EMPLOYEE, {
        "GET /api/v1/metadata/objects/Employee__c/relationships": () =>
          json({ data: { parents: [], children: [], manyToMany: [] } }),
        "GET /api/v1/metadata/objects/Employee__c/record-types": () => json({ data: [] }),
        "GET /api/v1/metadata/objects/Employee__c/layouts": () => json({ data: [] }),
      }),
    );
    open("relationships");
    const { unmount } = render(<ObjectFrame />);
    expect(await screen.findByRole("heading", { name: "Employee — Relationships" })).toBeInTheDocument();
    unmount();

    open("record-types");
    render(<ObjectFrame />);
    expect(await screen.findByRole("heading", { name: "Employee — Record types" })).toBeInTheDocument();
  });

  it("shows what the API answered for an object that does not exist", async () => {
    state.object = "Ghost__c";
    open("fields");
    fakeApi({
      "GET /api/v1/metadata/objects/Ghost__c": () => refusal("NOT_FOUND", "This object does not exist.", 404),
    });
    render(<ObjectFrame />);

    expect(await screen.findByTestId("object-failed")).toHaveTextContent("This object does not exist.");
  });
});

describe("ObjectFrame: the Details tab", () => {
  it("shows the labels, the type and who made the object, and offers Edit and Remove for the organization's own", async () => {
    open("details");
    fakeApi(
      answers(EMPLOYEE, {
        "GET /api/v1/metadata/objects/Employee__c/authorship": () =>
          json({
            data: [
              {
                kind: "OBJECT",
                apiName: "Employee__c",
                createdBy: "Member A",
                createdAt: "2026-10-01T10:00:00Z",
                updatedBy: "Member A",
                updatedAt: "2026-10-01T10:00:00Z",
              },
            ],
          }),
      }),
    );
    render(<ObjectFrame />);

    const page = await screen.findByTestId("object-details");

    expect(within(page).getByRole("heading", { name: "Employee — Details" })).toBeInTheDocument();
    expect(page).toHaveTextContent("Employees");
    expect(page).toHaveTextContent("Custom");
    expect(page).toHaveTextContent("People who work here.");
    await vi.waitFor(() => expect(page).toHaveTextContent("Member A"));
    expect(within(page).getByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(within(page).getByRole("button", { name: "Remove the object" })).toBeInTheDocument();
  });

  it("offers neither saving the labels nor removing an object the platform defines, without a paragraph about it", async () => {
    state.object = "Account";
    open("details");
    fakeApi(answers(ACCOUNT));
    render(<ObjectFrame />);

    const page = await screen.findByTestId("object-details");

    expect(page).toHaveTextContent("Standard");
    expect(screen.queryByRole("button", { name: "Remove the object" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.queryByText(/defined by the platform/)).toBeNull();
  });

  it("says which module manages the records of an object it does", async () => {
    state.object = "User";
    open("details");
    fakeApi(answers(USER));
    render(<ObjectFrame />);

    expect(await screen.findByText("identity")).toBeInTheDocument();
  });

  it("changes the labels with the version it read, and shows the API's words when someone else changed the object", async () => {
    open("details");
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "PUT /api/v1/metadata/objects/Employee__c": () =>
          refusal("CONCURRENT_MODIFICATION", "The resource was changed by someone else. Reload it and try again.", 409),
      }),
    );
    render(<ObjectFrame />);

    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Worker" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the labels" }));

    expect(await screen.findByTestId("object-problem")).toHaveTextContent("changed by someone else");
    const sent = JSON.parse(calls.find((call) => call.key.startsWith("PUT"))?.body ?? "{}");
    expect(sent).toEqual({ label: "Worker", pluralLabel: "Employees", description: "People who work here.", version: 2 });
  });

  it("removes the object only after a second click, then returns to the list", async () => {
    open("details");
    const calls = fakeApi(
      answers(EMPLOYEE, {
        "DELETE /api/v1/metadata/objects/Employee__c": () => new Response(null, { status: 204 }),
      }),
    );
    render(<ObjectFrame />);

    fireEvent.click(await screen.findByRole("button", { name: "Remove the object" }));
    expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/objects/Employee__c")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the object" }));

    await vi.waitFor(() => expect(currentUrl()).toBe("/setup/objects"));
    expect(calls.some((call) => call.key === "DELETE /api/v1/metadata/objects/Employee__c")).toBe(true);
  });
});
