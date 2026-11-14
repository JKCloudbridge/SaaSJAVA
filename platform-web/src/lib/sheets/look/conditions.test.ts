import { describe, expect, it } from "vitest";
import { lookSection } from "./caseFile";
import { judge, OPERATORS, readConditions, wordsOf, type Operator } from "./conditions";

// The conditional colours (SH4): a closed comparison over the displayed number. The cases are the shared file's.
describe("conditions against the shared cases", () => {
  it("has a case for every operator", () => {
    const seen = new Set(lookSection("condition").map((line) => line.fields[0]));
    for (const operator of OPERATORS) {
      expect(seen.has(operator), operator).toBe(true);
    }
  });

  for (const line of lookSection("condition")) {
    const [operator, value, value2, cell, expected] = line.fields;
    it(`line ${line.number}: ${operator} ${value} ${value2} on "${cell}" is ${expected}`, () => {
      const stored = { from: "r:a", to: "r:a", operator, value, ...(value2 ? { value2 } : {}), fill: "#ffff00" };
      const rules = readConditions([stored], () => 0, () => 0);
      const number = cell !== undefined && /^-?\d+(\.\d+)?$/.test(cell) ? Number(cell) : null;

      expect(judge(rules, 0, 0, number) !== null).toBe(expected === "yes");
    });
  }

  for (const line of lookSection("words")) {
    const [operator, value, value2, words] = line.fields;
    it(`line ${line.number}: the words of ${operator}`, () => {
      expect(wordsOf(operator as Operator, value ?? "", value2 || undefined)).toBe(words);
    });
  }
});

describe("reading and applying the stored rules", () => {
  const rowAt = (id: string) => ({ r1: 0, r2: 1, r3: 2 })[id as "r1"];
  const columnAt = (id: string) => ({ a: 0, b: 1 })[id as "a"];

  it("puts the range in display positions, whichever corner comes first", () => {
    const [rule] = readConditions([{ from: "r3:b", to: "r1:a", operator: "GT", value: "1", fill: "#ff0000" }], rowAt, columnAt);

    expect(rule).toMatchObject({ r0: 0, r1: 2, c0: 0, c1: 1 });
  });

  it("colours only the cells of its range", () => {
    const rules = readConditions([{ from: "r2:a", to: "r2:b", operator: "GT", value: "1", fill: "#ff0000" }], rowAt, columnAt);

    expect(judge(rules, 1, 1, 5)?.fill).toBe("#ff0000");
    expect(judge(rules, 0, 1, 5)).toBeNull();
    expect(judge(rules, 2, 0, 5)).toBeNull();
  });

  it("takes the first fill and the first text colour in the stored order, and the words of the first rule that met the cell", () => {
    const rules = readConditions(
      [
        { from: "r1:a", to: "r3:b", operator: "GT", value: "10", fill: "#111111" },
        { from: "r1:a", to: "r3:b", operator: "GT", value: "1", fill: "#222222", colour: "#333333" },
        { from: "r1:a", to: "r3:b", operator: "GT", value: "0", colour: "#444444" },
      ],
      rowAt,
      columnAt,
    );

    expect(judge(rules, 0, 0, 20)).toEqual({ fill: "#111111", colour: "#333333", words: "highlighted: above 10" });
    expect(judge(rules, 0, 0, 5)).toEqual({ fill: "#222222", colour: "#333333", words: "highlighted: above 1" });
    expect(judge(rules, 0, 0, 0.5)).toEqual({ colour: "#444444", words: "highlighted: above 0" });
  });

  it("leaves out a rule that is not exactly the allowed shape", () => {
    const bad = [
      { from: "r1:a", to: "r1:a", operator: "CONTAINS", value: "1", fill: "#ff0000" },
      { from: "r1:a", to: "r1:a", operator: "GT", value: "1e3", fill: "#ff0000" },
      { from: "r1:a", to: "r1:a", operator: "GT", value: "=A1", fill: "#ff0000" },
      { from: "r1:a", to: "r1:a", operator: "BETWEEN", value: "1", fill: "#ff0000" },
      { from: "gone:a", to: "r1:a", operator: "GT", value: "1", fill: "#ff0000" },
      { from: "r1:a", to: "r1:a", operator: "GT", value: "1" },
      { from: "r1:a", to: "r1:a", operator: "GT", value: "1", fill: "red" },
      { from: 5, to: "r1:a", operator: "GT", value: "1", fill: "#ff0000" },
    ];

    expect(readConditions(bad, rowAt, columnAt)).toEqual([]);
    expect(readConditions(undefined, rowAt, columnAt)).toEqual([]);
  });
});
