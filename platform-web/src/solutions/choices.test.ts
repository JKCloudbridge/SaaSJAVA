import { describe, expect, it } from "vitest";
import { choiceFormFor, choiceNeeded } from "./choices";

describe("the forms registered for approval steps that ask for a choice", () => {
  it("has one for the template and for the change request, and none for any other object, nor for an inherited name", () => {
    expect(choiceFormFor("InspectionTemplate")).toBeDefined();
    expect(choiceFormFor("InspectionChangeRequest")).toBeDefined();
    expect(choiceFormFor("Expense__c")).toBeUndefined();
    expect(choiceFormFor("toString")).toBeUndefined();
    expect(choiceFormFor("constructor")).toBeUndefined();
  });
});

describe("whether approving a step needs a choice (FA8)", () => {
  it("does when the process marks the step as asking, whatever the object", () => {
    expect(choiceNeeded("InspectionTemplate", true, false)).toBe(true);
    expect(choiceNeeded("InspectionChangeRequest", true, false)).toBe(true);
  });

  it("does at the last approval of a change request even when the step is not marked, because it publishes", () => {
    expect(choiceNeeded("InspectionChangeRequest", false, true)).toBe(true);
  });

  it("does not at an ordinary step, nor at the last step of another object", () => {
    expect(choiceNeeded("InspectionChangeRequest", false, false)).toBe(false);
    expect(choiceNeeded("InspectionTemplate", false, true)).toBe(false);
    expect(choiceNeeded(undefined, false, true)).toBe(false);
    expect(choiceNeeded("Expense__c", false, true)).toBe(false);
  });

  it("follows the part an object plays, so the objects of every application of the kind have their form (FA13B)", () => {
    expect(choiceFormFor("EnvironmentTemplate")).toBeDefined();
    expect(choiceFormFor("EnvironmentChangeRequest")).toBeDefined();
    expect(choiceFormFor("EnvironmentFill")).toBeUndefined();
    expect(choiceNeeded("EnvironmentChangeRequest", false, true)).toBe(true);
    expect(choiceNeeded("EnvironmentTemplate", false, true)).toBe(false);
  });
});
