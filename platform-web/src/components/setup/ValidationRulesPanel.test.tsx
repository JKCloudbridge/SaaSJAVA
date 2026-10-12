import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { ValidationRulesPanel } from "./ValidationRulesPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
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

describe("ValidationRulesPanel", () => {
  it("lists the rules with their condition, message and state, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json({ data: [RULE, { ...RULE, apiName: "Other__c", label: "Other", active: false }] }) });

    render(
      <StrictMode>
        <ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("rule-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("No negatives");
    expect(rows[0]).toHaveTextContent("Rejects when quantity__c < 0");
    expect(rows[0]).toHaveTextContent("Message: No negative quantities. (shown on quantity__c)");
    expect(rows[0]).toHaveTextContent("Yes");
    expect(rows[1]).toHaveTextContent("No");
    expect(within(rows[1] as HTMLElement).getByRole("button", { name: "Switch on" })).toBeInTheDocument();
  });

  it("adds a rule and sends what the API checks, leaving the field of the message out when none is chosen", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [] }), [CREATE]: () => json({ data: RULE }, 201) });
    const changed = vi.fn();
    render(<ValidationRulesPanel object={ORDER} onChanged={changed} />);
    await screen.findByText("No validation rules yet.");

    const add = form("Add a validation rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "NoNegative" } });
    fireEvent.change(add.getByLabelText("Label"), { target: { value: "No negatives" } });
    fireEvent.change(add.getByLabelText("Condition"), { target: { value: "quantity__c < 0" } });
    fireEvent.change(add.getByLabelText("Message"), { target: { value: "No negative quantities." } });
    fireEvent.click(add.getByRole("button", { name: "Add the validation rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was added.");
    const sent = JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}");
    expect(sent).toMatchObject({
      name: "NoNegative",
      label: "No negatives",
      expression: "quantity__c < 0",
      errorMessage: "No negative quantities.",
      active: true,
    });
    expect(sent.errorField).toBeUndefined();
    expect(changed).toHaveBeenCalled();
  });

  it("offers the fields of the object for the message and sends the one that was chosen", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [] }), [CREATE]: () => json({ data: RULE }, 201) });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    await screen.findByText("No validation rules yet.");
    const add = form("Add a validation rule");

    const offered = within(add.getByLabelText("Show the message on")).getAllByRole("option");
    expect(offered.map((option) => option.textContent)).toEqual([
      "The record as a whole",
      "quantity__c (quantity__c)",
      "status__c (status__c)",
    ]);
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "A" } });
    fireEvent.change(add.getByLabelText("Show the message on"), { target: { value: "status__c" } });
    fireEvent.click(add.getByRole("button", { name: "Add the validation rule" }));

    await screen.findByTestId("rules-notice");
    expect(JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}").errorField).toBe("status__c");
  });

  it("changes a rule with the version it read and never lets the name be changed", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [CHANGE]: () => json({ data: RULE }) });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("rule-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    const change = form("Change the validation rule");
    expect(change.queryByLabelText("Name")).toBeNull();
    expect(change.getByLabelText("Condition")).toHaveValue("quantity__c < 0");
    fireEvent.change(change.getByLabelText("Condition"), { target: { value: "quantity__c < -1" } });
    fireEvent.click(change.getByRole("button", { name: "Save the validation rule" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === CHANGE)?.body ?? "{}");
    expect(sent.version).toBe(4);
    expect(sent.expression).toBe("quantity__c < -1");
    expect(sent.errorField).toBe("quantity__c");
  });

  it("switches a rule off with one step that keeps everything else as it was", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [CHANGE]: () => json({ data: RULE }) });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("rule-row");

    fireEvent.click(within(row).getByRole("button", { name: "Switch off" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The rule was switched off.");
    const sent = JSON.parse(calls.find((call) => call.key === CHANGE)?.body ?? "{}");
    expect(sent).toMatchObject({ active: false, expression: "quantity__c < 0", errorMessage: "No negative quantities.", version: 4 });
  });

  it("removes a rule", async () => {
    const calls = fakeApi({ [LIST]: () => json({ data: [RULE] }), [REMOVE]: () => new Response(null, { status: 204 }) });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("rule-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("rules-notice")).toHaveTextContent("The validation rule was removed.");
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    await screen.findByText("No validation rules yet.");

    const add = form("Add a validation rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "NoNegative" } });
    fireEvent.change(add.getByLabelText("Condition"), { target: { value: "quantity__c < 0" } });
    fireEvent.click(add.getByRole("button", { name: "Add the validation rule" }));

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
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("rule-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));
    await screen.findByTestId("rules-notice");
    fireEvent.click(within(row).getByRole("button", { name: "Switch off" }));

    await vi.waitFor(() => {
      const kinds = calls.filter((call) => call.key.endsWith("/changes")).map((call) => JSON.parse(call.body).kind);
      expect(kinds).toEqual(["DELETE_VALIDATION_RULE", "UPDATE_VALIDATION_RULE"]);
    });
    expect(calls.some((call) => call.key === REMOVE || call.key === CHANGE)).toBe(false);
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
              fields: { expression: ["Line 1, column 14: The expression ends where a value is missing."] },
            },
          },
          400,
        ),
    });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    await screen.findByText("No validation rules yet.");

    fireEvent.change(form("Add a validation rule").getByLabelText("Name"), { target: { value: "Odd" } });
    fireEvent.click(form("Add a validation rule").getByRole("button", { name: "Add the validation rule" }));

    expect(await screen.findByTestId("rules-problem")).toHaveTextContent(
      "Condition: Line 1, column 14: The expression ends where a value is missing.",
    );
  });

  it("has a check and a test box for the condition that ask the API", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [] }),
      [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["quantity__c"], usesClock: false } }),
    });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);
    await screen.findByText("No validation rules yet.");
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
    render(<ValidationRulesPanel object={{ ...ORDER, managedBy: "identity" }} onChanged={vi.fn()} />);

    expect(screen.getByText(/belong to the platform, so it has no validation rules/)).toBeInTheDocument();
  });

  it("shows the words of the API when the rules cannot be read", async () => {
    fakeApi({
      [LIST]: () => json({ error: { code: "FORBIDDEN", message: "You do not have the permission to do this." } }, 403),
    });
    render(<ValidationRulesPanel object={ORDER} onChanged={vi.fn()} />);

    expect(await screen.findByTestId("rules-failed")).toHaveTextContent("You do not have the permission");
  });
});
