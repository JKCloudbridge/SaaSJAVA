import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { tabProps } from "@/test/objectTab";
import { ValidationRulesPanel } from "./ValidationRulesPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const SECTION = "/setup/objects/Order__c?section=rules";

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

const LIST = "GET /api/v1/metadata/objects/Order__c/validation-rules";
const CREATE = "POST /api/v1/metadata/objects/Order__c/validation-rules";
const CHANGE = "PUT /api/v1/metadata/objects/Order__c/validation-rules/NoNegative__c";
const REMOVE = "DELETE /api/v1/metadata/objects/Order__c/validation-rules/NoNegative__c";
const CHECK = "POST /api/v1/metadata/expressions/check";

function field(apiName: string, type = "TEXT", extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type, required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const ORDER = {
  apiName: "Order__c", label: "Order", pluralLabel: "Orders", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [field("id", "TEXT", { kind: "SYSTEM", editable: false }), field("quantity__c", "NUMBER"), field("status__c")],
};

const RULE = {
  apiName: "NoNegative__c", objectApiName: "Order__c", label: "No negatives", description: "Quantities are never below zero",
  expression: "quantity__c < 0", errorMessage: "No negative quantities.", errorField: "quantity__c", active: true, version: 4,
};

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

async function openRule() {
  const row = await screen.findByTestId("rule-row");
  fireEvent.click(within(row).getByRole("button", { name: "No negatives" }));
  return screen.findByTestId("rule-detail");
}

describe("ValidationRulesPanel", () => {
  it("lists the rules with their API name and status, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json({ data: [RULE, { ...RULE, apiName: "Other__c", label: "Other", active: false }] }) });

    render(
      <StrictMode>
        <ValidationRulesPanel {...tabProps(ORDER)} />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("rule-row");
    expect(screen.getByRole("heading", { name: "Order — Validation rules" })).toBeInTheDocument();
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("No negatives");
    expect(rows[0]).toHaveTextContent("NoNegative__c");
    expect(rows[0]).toHaveTextContent("Active");
    expect(rows[1]).toHaveTextContent("Not active");
  });

  it("opens a rule's page with its condition, message and where it is shown", async () => {
    fakeApi({ [LIST]: () => json({ data: [RULE] }) });
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);

    const page = await openRule();

    expect(currentUrl()).toBe(`${SECTION}&rule=NoNegative__c`);
    expect(page).toHaveTextContent("quantity__c < 0");
    expect(page).toHaveTextContent("No negative quantities.");
    expect(page).toHaveTextContent("quantity__c");
    expect(page).toHaveTextContent("Quantities are never below zero");
  });

  it("adds a rule from the form behind New and sends what the API checks, leaving the field of the message out", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [] }), [CREATE]: () => json({ data: RULE }, 201) });
    const props = tabProps(ORDER);
    render(<ValidationRulesPanel {...props} />);
    await screen.findByText("No validation rules yet.");

    fireEvent.click(screen.getByRole("button", { name: "New" }));
    const add = form("Add a validation rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "NoNegative" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "No negatives" } });
    fireEvent.change(add.getByLabelText("Condition"), { target: { value: "quantity__c < 0" } });
    fireEvent.change(add.getByLabelText("Message"), { target: { value: "No negative quantities." } });
    fireEvent.click(screen.getByRole("button", { name: "Add the validation rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was added.");
    expect(currentUrl()).toBe(SECTION);
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent).toMatchObject({
      name: "NoNegative",
      label: "No negatives",
      expression: "quantity__c < 0",
      errorMessage: "No negative quantities.",
      active: true,
    });
    expect(sent.errorField).toBeUndefined();
    expect(props.reload).toHaveBeenCalled();
  });

  it("offers the fields of the object for the message and sends the one that was chosen", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [] }), [CREATE]: () => json({ data: RULE }, 201) });
    setUrl(`${SECTION}&new=1`);
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    await screen.findByRole("form", { name: "Add a validation rule" });
    const add = form("Add a validation rule");

    const offered = within(add.getByLabelText("Show the message on")).getAllByRole("option");
    expect(offered.map((option) => option.textContent)).toEqual([
      "The record as a whole",
      "quantity__c (quantity__c)",
      "status__c (status__c)",
    ]);
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "A" } });
    fireEvent.change(add.getByLabelText("Show the message on"), { target: { value: "status__c" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the validation rule" }));

    await screen.findByTestId("rules-notice");
    expect(JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}").errorField).toBe("status__c");
  });

  it("changes a rule with the version it read and never lets the name be changed", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [CHANGE]: () => json({ data: RULE }) });
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    const page = await openRule();

    fireEvent.click(within(page).getByRole("button", { name: "Edit" }));
    const change = form("Change the validation rule");
    expect(change.queryByLabelText("Name")).toBeNull();
    expect(change.getByLabelText("Condition")).toHaveValue("quantity__c < 0");
    fireEvent.change(change.getByLabelText("Condition"), { target: { value: "quantity__c < -1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the validation rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === CHANGE)?.body ?? "{}");
    expect(sent.version).toBe(4);
    expect(sent.expression).toBe("quantity__c < -1");
    expect(sent.errorField).toBe("quantity__c");
  });

  it("switches a rule off with one step that keeps everything else as it was", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [CHANGE]: () => json({ data: RULE }) });
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    const page = await openRule();

    fireEvent.click(within(page).getByRole("button", { name: "Switch off" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The rule was switched off.");
    const sent = JSON.parse(calls.find((call) => call.key === CHANGE)?.body ?? "{}");
    expect(sent).toMatchObject({ active: false, expression: "quantity__c < 0", errorMessage: "No negative quantities.", version: 4 });
  });

  it("removes a rule only after a second click, then goes back to the list", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [REMOVE]: () => new Response(null, { status: 204 }) });
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    const page = await openRule();

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was removed.");
    expect(currentUrl()).toBe(SECTION);
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    setUrl(`${SECTION}&new=1`);
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    await screen.findByRole("form", { name: "Add a validation rule" });

    const add = form("Add a validation rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "NoNegative" } });
    fireEvent.change(add.getByLabelText("Condition"), { target: { value: "quantity__c < 0" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the validation rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === CREATE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent.kind).toBe("CREATE_VALIDATION_RULE");
    expect(sent.objectApiName).toBe("Order__c");
    expect(sent.createValidationRule.name).toBe("NoNegative");
  });

  it("drafts a removal and a switch in the chosen change set", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [RULE] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    setUrl(`${SECTION}&rule=NoNegative__c`);
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    const page = await screen.findByTestId("rule-detail");

    fireEvent.click(within(page).getByRole("button", { name: "Switch off" }));
    await screen.findByTestId("rules-notice");
    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the rule" }));

    await vi.waitFor(() => {
      const kinds = calls.filter((call) => call.key.endsWith("/changes")).map((call) => JSON.parse(call.body).kind);
      expect(kinds).toEqual(["UPDATE_VALIDATION_RULE", "DELETE_VALIDATION_RULE"]);
    });
    expect(calls.some((call) => call.key === REMOVE || call.key === CHANGE)).toBe(false);
  });

  it("shows the words of the API with the place that is wrong, and stays on the form", async () => {
    fakeApi({
      [LIST]: () => json({ data: [] }),
      [CREATE]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request has problems.",
              fields: { expression: ["Line 1, column 14: The expression ends where a value is missing."] },
            },
          },
          400,
        ),
    });
    setUrl(`${SECTION}&new=1`);
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    await screen.findByRole("form", { name: "Add a validation rule" });

    fireEvent.change(form("Add a validation rule").getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(screen.getByRole("button", { name: "Add the validation rule" }));

    expect(await screen.findByTestId("rules-problem")).toHaveTextContent(
      "Condition: Line 1, column 14: The expression ends where a value is missing.",
    );
    expect(screen.getByRole("form", { name: "Add a validation rule" })).toBeInTheDocument();
  });

  it("has a check and a test box for the condition that ask the API", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["quantity__c"], usesClock: false } }),
    });
    setUrl(`${SECTION}&new=1`);
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);
    await screen.findByRole("form", { name: "Add a validation rule" });
    const add = form("Add a validation rule");

    fireEvent.change(add.getByLabelText("Condition"), { target: { value: "quantity__c < 0" } });
    fireEvent.click(add.getByRole("button", { name: "Check the condition" }));

    expect(await screen.findByTestId("rule-check-valid")).toHaveTextContent("It gives true or false.");
    const sent = JSON.parse(calls.find((call) => call.key === CHECK)?.body ?? "{}");
    expect(sent).toMatchObject({ objectApiName: "Order__c", purpose: "VALIDATION_RULE", expression: "quantity__c < 0" });
    expect(sent.resultType).toBeUndefined();
  });

  it("says that an object of the platform has no validation rules", () => {
    fakeApi();
    render(<ValidationRulesPanel {...tabProps({ ...ORDER, managedBy: "identity" })} />);

    expect(screen.getByText(/belong to the platform, so it has no validation rules/)).toBeInTheDocument();
  });

  it("shows the words of the API when the rules cannot be read", async () => {
    fakeApi({
      [LIST]: () => json({ error: { code: "FORBIDDEN", message: "You do not have the permission to do this." } }, 403),
    });
    render(<ValidationRulesPanel {...tabProps(ORDER)} />);

    expect(await screen.findByTestId("rules-failed")).toHaveTextContent("You do not have the permission");
  });
});
