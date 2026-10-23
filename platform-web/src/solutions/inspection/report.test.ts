import { describe, expect, it } from "vitest";
import { operatorsFor, splitMany, toRequest, type Question } from "./report";

const BASE: Question = {
  templateId: "0198f5c0-0000-7000-8000-000000000042",
  versionNo: 3,
  columnId: "c1",
  type: "text",
  operator: "EQUALS",
  value: "",
  many: "",
  from: "",
  to: "",
  status: "",
};

describe("the comparisons a column offers", () => {
  it("offers a range only for a number and a date", () => {
    expect(operatorsFor("number")).toEqual(["EQUALS", "ONE_OF", "BETWEEN"]);
    expect(operatorsFor("date")).toEqual(["EQUALS", "ONE_OF", "BETWEEN"]);
    expect(operatorsFor("text")).toEqual(["EQUALS", "ONE_OF"]);
    expect(operatorsFor("picklist")).toEqual(["EQUALS", "ONE_OF"]);
  });

  it("offers only 'is' for a yes or no", () => {
    expect(operatorsFor("boolean")).toEqual(["EQUALS"]);
  });
});

describe("the lines of a box", () => {
  it("trims them and drops the empty ones and the repeats", () => {
    expect(splitMany(" Fail \r\n\r\nPass\nFail\n  ")).toEqual(["Fail", "Pass"]);
  });
});

describe("the question for the API", () => {
  it("asks for what is missing before anything is sent", () => {
    expect(toRequest({ ...BASE, columnId: "" })).toEqual({ ok: false, error: "Choose a template and a column." });
    expect(toRequest({ ...BASE, value: "  " })).toEqual({ ok: false, error: "Give the value to look for." });
    expect(toRequest({ ...BASE, operator: "ONE_OF", many: "\n" }).ok).toBe(false);
    expect(toRequest({ ...BASE, operator: "BETWEEN" }).ok).toBe(false);
  });

  it("sends a value as typed, trimmed, in the list of values", () => {
    const built = toRequest({ ...BASE, value: " Fail ", status: "APPROVED" });
    expect(built).toEqual({
      ok: true,
      request: {
        templateId: BASE.templateId,
        versionNo: 3,
        columnId: "c1",
        operator: "EQUALS",
        status: "APPROVED",
        values: ["Fail"],
      },
    });
  });

  it("sends a range with either bound left out", () => {
    const built = toRequest({ ...BASE, type: "number", operator: "BETWEEN", from: "10", to: "" });
    expect(built.ok && built.request.from).toBe("10");
    expect(built.ok && built.request.to).toBeUndefined();
  });
});
