import { fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ExpressionCheck } from "./ExpressionCheck";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const CHECK = "POST /api/v1/metadata/expressions/check";
const TRY = "POST /api/v1/metadata/expressions/test";

function field(apiName: string, type: string, label = apiName) {
  return {
    apiName, label, description: "", kind: "CUSTOM", type, required: false, unique: false, settings: {},
    retired: false, editable: true, version: 0,
  };
}

const FIELDS = [field("quantity__c", "NUMBER", "Quantity"), field("unitPrice__c", "CURRENCY", "Unit price"),
  field("closeDate__c", "DATE", "Close date")];

const VALID = { data: { valid: true, type: "NUMBER", fields: ["quantity__c", "unitPrice__c"], usesClock: false } };

function renderCheck(extra: Partial<Parameters<typeof ExpressionCheck>[0]> = {}) {
  return render(
    <StrictMode>
      <ExpressionCheck
        objectApiName="Order__c"
        expression="quantity__c * unitPrice__c"
        purpose="FORMULA"
        resultType="CURRENCY"
        fields={FIELDS}
        testId="check"
        {...extra}
      />
    </StrictMode>,
  );
}

describe("ExpressionCheck", () => {
  it("asks the API and shows the type a valid formula gives and the fields it uses", async () => {
    const calls = fakeApi({ [CHECK]: () => json(VALID) });
    renderCheck();

    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));

    expect(await screen.findByTestId("check-valid")).toHaveTextContent(
      "It is valid. It gives a number. It uses quantity__c, unitPrice__c.",
    );
    const sent = JSON.parse(calls.find((call) => call.key === CHECK)?.body ?? "{}");
    expect(sent).toEqual({
      objectApiName: "Order__c",
      expression: "quantity__c * unitPrice__c",
      purpose: "FORMULA",
      resultType: "CURRENCY",
    });
  });

  it("shows the place that is wrong in the API's own words and offers no sample values", async () => {
    fakeApi({
      [CHECK]: () =>
        json({
          data: {
            valid: false,
            fields: ["ghost__c"],
            usesClock: false,
            problem: { kind: "UNKNOWN_FIELD", message: "Line 1, column 1: The field ghost__c does not exist.", line: 1, column: 1 },
          },
        }),
    });
    renderCheck({ expression: "ghost__c * 2" });

    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));

    const invalid = await screen.findByTestId("check-invalid");
    expect(invalid).toHaveTextContent("It is not valid. Line 1, column 1: The field ghost__c does not exist.");
    expect(invalid).toHaveTextContent("It names ghost__c.");
    expect(screen.queryByTestId("check-try")).toBeNull();
  });

  it("sends only the sample values that were typed, for the fields the expression uses, and shows the value", async () => {
    const calls = fakeApi({
      [CHECK]: () => json(VALID),
      [TRY]: () => json({ data: { outcome: "VALUE", type: "NUMBER", value: "6" } }),
    });
    renderCheck();
    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));
    await screen.findByTestId("check-valid");

    expect(screen.getByLabelText(/Quantity/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Unit price/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Close date/)).toBeNull();
    expect(screen.getByText("a number such as 12")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Quantity/), { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-outcome")).toHaveTextContent("The formula gives: 6");
    const sent = JSON.parse(calls.find((call) => call.key === TRY)?.body ?? "{}");
    expect(sent.values).toEqual({ quantity__c: "3" });
    expect(sent.purpose).toBe("FORMULA");
  });

  it("says that a formula gives no value when the API says so", async () => {
    fakeApi({
      [CHECK]: () => json(VALID),
      [TRY]: () => json({ data: { outcome: "VALUE", type: "NUMBER" } }),
    });
    renderCheck();
    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));
    await screen.findByTestId("check-valid");

    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-outcome")).toHaveTextContent("The formula gives no value for these values.");
  });

  it("shows a rule as rejected with its own message, or as passing", async () => {
    let outcome = "REJECTED";
    fakeApi({
      [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["quantity__c"], usesClock: false } }),
      [TRY]: () => json({ data: { outcome, type: "BOOLEAN" } }),
    });
    renderCheck({ expression: "quantity__c < 0", purpose: "VALIDATION_RULE", resultType: undefined, ruleMessage: "No negative quantities." });
    fireEvent.click(screen.getByRole("button", { name: "Check the condition" }));
    await screen.findByTestId("check-valid");

    fireEvent.click(screen.getByRole("button", { name: "Try it" }));
    expect(await screen.findByTestId("check-outcome")).toHaveTextContent("Rejected: No negative quantities.");

    outcome = "PASSED";
    fireEvent.click(screen.getByRole("button", { name: "Try it" }));
    expect(await screen.findByText(/Passes: the condition is false/)).toBeInTheDocument();
  });

  it("shows a failure of the evaluation with its place", async () => {
    fakeApi({
      [CHECK]: () => json(VALID),
      [TRY]: () =>
        json({
          data: {
            outcome: "ERROR",
            problem: { kind: "DIVISION_BY_ZERO", message: "Line 1, column 4: This divides by zero.", line: 1, column: 4 },
          },
        }),
    });
    renderCheck({ expression: "10 / quantity__c" });
    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));
    await screen.findByTestId("check-valid");

    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-outcome")).toHaveTextContent(
      "It could not be worked out. Line 1, column 4: This divides by zero.",
    );
  });

  it("shows the API's words for a sample value that does not fit its field", async () => {
    fakeApi({
      [CHECK]: () => json(VALID),
      [TRY]: () => refusal("VALIDATION_ERROR", "The request has problems.", 400),
    });
    renderCheck();
    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));
    await screen.findByTestId("check-valid");

    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-try-failed")).toHaveTextContent("The request has problems.");
  });

  it("shows the API's words when the person may not check", async () => {
    fakeApi({ [CHECK]: () => refusal("FORBIDDEN", "You do not have the permission to do this.", 403) });
    renderCheck();

    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));

    expect(await screen.findByTestId("check-failed")).toHaveTextContent("You do not have the permission");
  });

  it("forgets an answer when the text it was asked about changes", async () => {
    fakeApi({ [CHECK]: () => json(VALID) });
    const { rerender } = renderCheck();
    fireEvent.click(screen.getByRole("button", { name: "Check the formula" }));
    await screen.findByTestId("check-valid");

    rerender(
      <StrictMode>
        <ExpressionCheck
          objectApiName="Order__c"
          expression="quantity__c * 3"
          purpose="FORMULA"
          resultType="CURRENCY"
          fields={FIELDS}
          testId="check"
        />
      </StrictMode>,
    );

    expect(screen.queryByTestId("check-valid")).toBeNull();
    expect(screen.queryByTestId("check-try")).toBeNull();
  });

  it("cannot check an empty box", () => {
    fakeApi();
    renderCheck({ expression: "  " });

    expect(screen.getByRole("button", { name: "Check the formula" })).toBeDisabled();
  });

  it("says that a rule asks for the moment of the save", async () => {
    fakeApi({ [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["closeDate__c"], usesClock: true } }) });
    renderCheck({ expression: "closeDate__c < TODAY()", purpose: "VALIDATION_RULE", resultType: undefined });

    fireEvent.click(screen.getByRole("button", { name: "Check the condition" }));

    expect(await screen.findByTestId("check-valid")).toHaveTextContent("It asks for the moment of the save.");
  });

  it("tries the condition of a workflow with the workflow's own check and says what the workflow would do", async () => {
    const calls = fakeApi({
      [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["quantity__c"], usesClock: false } }),
      "POST /api/v1/metadata/workflows/Order__c/check": () => json({ data: { outcome: "SKIPPED", actionProblems: {} } }),
    });
    renderCheck({ expression: "quantity__c > 1", purpose: "WORKFLOW_CONDITION", resultType: undefined });

    fireEvent.click(screen.getByRole("button", { name: "Check the condition" }));
    expect(await screen.findByTestId("check-valid")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Quantity/), { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-outcome")).toHaveTextContent("The workflow is skipped");
    expect(JSON.parse(calls.find((call) => call.key === CHECK)?.body ?? "{}")).toMatchObject({
      purpose: "WORKFLOW_CONDITION",
    });
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/workflows/Order__c/check")?.body ?? "{}"))
      .toEqual({ condition: "quantity__c > 1", values: { quantity__c: "0" } });
    expect(calls.some((call) => call.key === TRY)).toBe(false);
  });

  it("says that the workflow runs, and where the condition cannot be worked out", async () => {
    fakeApi({
      [CHECK]: () => json({ data: { valid: true, type: "BOOLEAN", fields: ["quantity__c"], usesClock: false } }),
      "POST /api/v1/metadata/workflows/Order__c/check": () => json({ data: { outcome: "RUNS", actionProblems: {} } }),
    });
    renderCheck({ expression: "quantity__c > 1", purpose: "WORKFLOW_CONDITION", resultType: undefined });
    fireEvent.click(screen.getByRole("button", { name: "Check the condition" }));
    await screen.findByTestId("check-valid");

    fireEvent.click(screen.getByRole("button", { name: "Try it" }));

    expect(await screen.findByTestId("check-outcome")).toHaveTextContent("The workflow runs");
  });
});
