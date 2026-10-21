import { describe, expect, it } from "vitest";
import { field } from "@/test/records";
import { dependentsOf, isAllowed, offeredField, staleDependents } from "./dependency";

const KIND = field("kind__c", "PICKLIST", {
  values: [
    { value: "WI", label: "Work", active: true },
    { value: "IMS", label: "Integrated", active: true },
    { value: "CSF", label: "Customer", active: true },
  ],
});

const AREA = field("area__c", "PICKLIST", {
  values: [
    { value: "PRD", label: "Production", active: true },
    { value: "QLT", label: "Quality", active: true },
    { value: "CUSA", label: "Customer A", active: true },
  ],
  dependency: { controllingField: "kind__c", allowed: { WI: ["PRD", "QLT"], IMS: [], CSF: ["CUSA"] } },
});

const values = (shown: { values?: Array<{ value: string }> }) => (shown.values ?? []).map((one) => one.value);

describe("what a dependent picklist offers", () => {
  it("offers only the values that go with the chosen value of the other picklist", () => {
    expect(values(offeredField(AREA, "WI", ""))).toEqual(["PRD", "QLT"]);
    expect(values(offeredField(AREA, "CSF", ""))).toEqual(["CUSA"]);
  });

  it("offers nothing when nothing is chosen, or when the chosen value allows nothing or is not known", () => {
    expect(values(offeredField(AREA, "", ""))).toEqual([]);
    expect(values(offeredField(AREA, undefined, ""))).toEqual([]);
    expect(values(offeredField(AREA, "IMS", ""))).toEqual([]);
    expect(values(offeredField(AREA, "NEW", ""))).toEqual([]);
  });

  it("keeps the value the record holds in the list even when it no longer goes with the choice", () => {
    expect(values(offeredField(AREA, "CSF", "PRD"))).toEqual(["PRD", "CUSA"]);
  });

  it("leaves a picklist that depends on nothing as it is, and other types alone", () => {
    expect(offeredField(KIND, "WI", "")).toBe(KIND);
    const text = field("text__c", "TEXT");
    expect(offeredField(text, "WI", "")).toBe(text);
  });

  it("offers a value the member added, and its pairs, at once", () => {
    const added = [{ value: "NEWAB", label: "New", controllingValues: ["WI", "IMS"] }];

    expect(values(offeredField(AREA, "WI", "", added))).toEqual(["PRD", "QLT", "NEWAB"]);
    expect(values(offeredField(AREA, "IMS", "", added))).toEqual(["NEWAB"]);
    expect(values(offeredField(AREA, "CSF", "", added))).toEqual(["CUSA"]);
    expect(values(offeredField(KIND, "WI", "", [{ value: "X", label: "X", controllingValues: [] }]))).toEqual([
      "WI",
      "IMS",
      "CSF",
      "X",
    ]);
  });

  it("does not offer a value twice if the form already had it", () => {
    const added = [{ value: "PRD", label: "Production", controllingValues: ["WI"] }];

    expect(values(offeredField(AREA, "WI", "", added))).toEqual(["PRD", "QLT"]);
  });
});

describe("whether a value goes with a choice", () => {
  it("answers from the pairs, and says yes for a picklist that depends on nothing", () => {
    expect(isAllowed(AREA, "WI", "PRD")).toBe(true);
    expect(isAllowed(AREA, "WI", "CUSA")).toBe(false);
    expect(isAllowed(AREA, "", "PRD")).toBe(false);
    expect(isAllowed(KIND, "", "WI")).toBe(true);
  });
});

describe("what changes when the other picklist changes", () => {
  const fields = [KIND, AREA, field("title__c", "TEXT")];

  it("finds the fields that depend on a field", () => {
    expect(dependentsOf(fields, "kind__c").map((one) => one.apiName)).toEqual(["area__c"]);
    expect(dependentsOf(fields, "title__c")).toEqual([]);
  });

  it("clears a chosen value that stops being allowed and leaves one that still goes with the choice", () => {
    const held = (name: string) => (name === "area__c" ? "PRD" : "");

    expect(staleDependents(fields, "kind__c", "CSF", held)).toEqual(["area__c"]);
    expect(staleDependents(fields, "kind__c", "WI", held)).toEqual([]);
    expect(staleDependents(fields, "kind__c", "", held)).toEqual(["area__c"]);
  });

  it("clears nothing when nothing was chosen in the dependent field", () => {
    expect(staleDependents(fields, "kind__c", "CSF", () => "")).toEqual([]);
  });

  it("counts the values the member added when it judges what still goes", () => {
    const added = { area__c: [{ value: "NEWAB", label: "New", controllingValues: ["CSF"] }] };

    expect(staleDependents(fields, "kind__c", "CSF", () => "NEWAB", added)).toEqual([]);
    expect(staleDependents(fields, "kind__c", "WI", () => "NEWAB", added)).toEqual(["area__c"]);
  });
});
