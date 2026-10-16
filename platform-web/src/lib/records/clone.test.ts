import { describe, expect, it } from "vitest";
import { DEAL_FORM, deal, field } from "@/test/records";
import { cloneableInputs } from "./clone";
import type { ObjectForm } from "./types";

describe("what a copy of a record starts with", () => {
  it("keeps the values a person could type, as the text the form holds", () => {
    const inputs = cloneableInputs(DEAL_FORM, deal("r1", { name__c: "Acme", amount__c: 12.5, stage__c: "WON" }));

    expect(inputs).toMatchObject({ name__c: "Acme", stage__c: "WON" });
    expect(inputs.amount__c).toBe("12.5");
  });

  it("leaves out what a person cannot type: a calculated value, a system field", () => {
    const inputs = cloneableInputs(DEAL_FORM, deal("r1"));

    expect(Object.keys(inputs)).not.toContain("margin__c");
    expect(Object.keys(inputs)).not.toContain("createdAt");
  });

  it("never copies the owner, a unique value or an identifier from another system", () => {
    const form: ObjectForm = {
      ...DEAL_FORM,
      fields: [
        ...DEAL_FORM.fields.map((candidate) => (candidate.apiName === "ownerId" ? { ...candidate, editable: true } : candidate)),
        field("code__c", "TEXT", { unique: true }),
        field("ref__c", "TEXT", { externalId: true }),
      ],
    };

    const inputs = cloneableInputs(form, deal("r1", { code__c: "A-1", ref__c: "X-9" }));

    expect(Object.keys(inputs)).not.toContain("ownerId");
    expect(Object.keys(inputs)).not.toContain("code__c");
    expect(Object.keys(inputs)).not.toContain("ref__c");
  });
});
