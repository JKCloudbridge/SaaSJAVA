import { describe, expect, it } from "vitest";
import { countPairs, isChecked, pairsOf, ruleOf, setRow, toggled, visibleRows } from "./pairs";

describe("the grid of pairs", () => {
  it("copies the rule the API sent and reads none when the picklist depends on nothing", () => {
    const sent = { controllingField: "kind__c", allowed: { WI: ["PRD"], IMS: [] } };
    const copy = pairsOf(sent);

    copy.WI?.push("QLT");

    expect(sent.allowed.WI).toEqual(["PRD"]);
    expect(pairsOf(undefined)).toEqual({});
  });

  it("checks and unchecks one box without touching the others", () => {
    const start = { WI: ["PRD"], IMS: [] as string[] };

    const added = toggled(start, "WI", "QLT", true);
    const removed = toggled(added, "WI", "PRD", false);

    expect(isChecked(added, "WI", "QLT")).toBe(true);
    expect(removed.WI).toEqual(["QLT"]);
    expect(start.WI).toEqual(["PRD"]);
    expect(toggled(start, "WI", "PRD", true).WI).toEqual(["PRD"]);
    expect(toggled({}, "CSF", "CUSA", true)).toEqual({ CSF: ["CUSA"] });
  });

  it("allows every value or none for a row", () => {
    expect(setRow({}, "WI", ["PRD", "QLT"], true).WI).toEqual(["PRD", "QLT"]);
    expect(setRow({ WI: ["PRD"] }, "WI", ["PRD", "QLT"], false).WI).toEqual([]);
  });

  it("counts the pairs", () => {
    expect(countPairs({ WI: ["PRD", "QLT"], IMS: [], CSF: ["CUSA"] })).toBe(3);
    expect(countPairs({})).toBe(0);
  });

  it("filters a long list of controlling values by value or label, ignoring case", () => {
    const rows = [
      { value: "WI", label: "Work instruction" },
      { value: "SOP", label: "Standard procedure" },
      { value: "CSF", label: "Customer format" },
    ];

    expect(visibleRows(rows, "").length).toBe(3);
    expect(visibleRows(rows, "  ").length).toBe(3);
    expect(visibleRows(rows, "wi").map((row) => row.value)).toEqual(["WI"]);
    expect(visibleRows(rows, "FORMAT").map((row) => row.value)).toEqual(["CSF"]);
    expect(visibleRows(rows, "zzz")).toEqual([]);
  });

  it("builds the rule to send: an empty row for a value nothing goes with, and no value that is gone", () => {
    const rule = ruleOf("kind__c", ["WI", "IMS"], ["PRD", "QLT"], { WI: ["PRD", "GONE"], OTHER: ["QLT"] });

    expect(rule).toEqual({ controllingField: "kind__c", allowed: { WI: ["PRD"], IMS: [] } });
    expect(ruleOf("", ["WI"], ["PRD"], { WI: ["PRD"] })).toBeUndefined();
  });
});
