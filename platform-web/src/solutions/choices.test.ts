import { describe, expect, it } from "vitest";
import { choiceFormFor } from "./choices";

describe("the forms registered for approval steps that ask for a choice", () => {
  it("has one for the template and none for any other object, nor for an inherited name", () => {
    expect(choiceFormFor("InspectionTemplate")).toBeDefined();
    expect(choiceFormFor("Expense__c")).toBeUndefined();
    expect(choiceFormFor("toString")).toBeUndefined();
    expect(choiceFormFor("constructor")).toBeUndefined();
  });
});
