import { describe, expect, it } from "vitest";
import { flatLayout, gridOf, growingLayout, inputs, same, section, specFromCases, wireFromCases } from "./caseFile";
import { evaluate, show } from "./evaluate";
import { parse } from "./parse";
import { canonical, print } from "./print";
import { toExcel, toIds } from "./translate";
import { DEFAULT_BOUNDS, E, FormulaRefused, WORDS, XlError, type Value } from "./types";

/**
 * Holds the browser's translators, gate, printer and interpreter to the one file of cases that the server's tests read
 * too (SH2, ADR-0252). The expected value of each case was written from the documented behaviour of the function before
 * any engine ran. The browser only shows a result while a person types; the server decides, and both agree on every line.
 */

const spec = specFromCases();
const flat = flatLayout();
const bounds = { maxSteps: DEFAULT_BOUNDS.maxSteps, maxTextChars: DEFAULT_BOUNDS.maxTextChars };

function reasonOf(stored: string): string {
  try {
    parse(stored, spec);
  } catch (e) {
    if (e instanceof FormulaRefused) return e.reason;
    throw e;
  }
  return "accepted";
}

describe("the shared case file", () => {
  it("gives the documented value on every evaluation case", () => {
    const wrong: string[] = [];
    const lines = section("eval");
    for (const line of lines) {
      const [name, formula, spec2, expected] = line.fields;
      const tree = parse(toIds(formula ?? "", 1, flat), spec);
      const got = evaluate(tree, "r1", gridOf(inputs(spec2 ?? "")), bounds);
      if (!same(expected ?? "", got)) wrong.push(`line ${line.number} ${name}: expected ${expected} got ${show(got)}`);
    }
    expect(lines.length).toBeGreaterThan(250);
    expect(wrong).toEqual([]);
  });

  it("survives the translators both ways and prints a fixed point", () => {
    for (const line of section("eval")) {
      const excel = line.fields[1] ?? "";
      const stored = toIds(excel, 1, flat);
      expect(toExcel(stored, 1, flat), `line ${line.number}`).toBe(excel);
      const once = print(parse(stored, spec));
      expect(print(parse(once, spec)), `fixed point, line ${line.number}`).toBe(once);
    }
  });

  it("refuses every refused case with its reason and repeats nothing", () => {
    const wrong: string[] = [];
    const lines = section("refuse");
    for (const line of lines) {
      const [name, formula, reason] = line.fields;
      try {
        parse(toIds(formula ?? "", 1, flat), spec);
        wrong.push(`line ${line.number} ${name}: accepted`);
      } catch (e) {
        if (!(e instanceof FormulaRefused)) throw e;
        if (e.reason !== reason) wrong.push(`line ${line.number} ${name}: ${e.reason} not ${reason}`);
        expect(WORDS[e.reason]).toBeTruthy();
        expect(e.message).not.toContain(formula ?? "");
      }
    }
    expect(lines.length).toBeGreaterThan(80);
    expect(wrong).toEqual([]);
  });

  it("has the one canonical form of every canonical case", () => {
    const lines = section("canonical");
    expect(lines.length).toBeGreaterThan(10);
    for (const line of lines) {
      expect(canonical(toIds(line.fields[0] ?? "", 1, flat), spec), `line ${line.number}`).toBe(line.fields[1]);
    }
  });

  it("stores the growing range once and draws it with the rows that exist", () => {
    const lines = section("growing");
    expect(lines.length).toBeGreaterThan(5);
    for (const line of lines) {
      const [, now, row, excel, stored, drawnNow, later, drawnLater] = line.fields;
      const layout = growingLayout(Number(now));
      if ((stored ?? "").startsWith("!")) {
        expect(() => toIds(excel ?? "", Number(row), layout), `line ${line.number}`).toThrowError(
          expect.objectContaining({ reason: (stored ?? "").slice(1) }),
        );
        continue;
      }
      expect(toIds(excel ?? "", Number(row), layout), `line ${line.number}`).toBe(stored);
      expect(toExcel(stored ?? "", Number(row), layout), `drawn now, line ${line.number}`).toBe(drawnNow);
      expect(toExcel(stored ?? "", Number(row), growingLayout(Number(later))), `drawn later, line ${line.number}`).toBe(drawnLater);
      expect(toIds(drawnLater ?? "", Number(row), growingLayout(Number(later))), `back, line ${line.number}`).toBe(stored);
    }
  });

  it("lists the same forty functions and nine formats as the server's table", () => {
    const wire = wireFromCases();
    expect(wire.functions).toHaveLength(40);
    expect(wire.functions.map((one) => one.name)).toEqual(expect.arrayContaining(["SUM", "IF", "TEXT", "COUNT"]));
    expect(wire.functions.map((one) => one.name)).not.toEqual(expect.arrayContaining(["STDEV"]));
    expect(wire.textFormats).toEqual(["0", "0.0", "0.00", "0.000", "0%", "0.0%", "0.00%", "#,##0", "#,##0.00"]);
  });
});

describe("the hostile formulas that are too big for the case file", () => {
  it("refuses a formula over four thousand characters before it is parsed", () => {
    expect(reasonOf("1+".repeat(2000) + "1")).toBe("too_long");
    expect(reasonOf("(".repeat(4001))).toBe("too_long");
  });

  it("refuses a flat sum of thousands of terms for its size and never overflows the stack", () => {
    expect(reasonOf("1+".repeat(1500) + "1")).toBe("too_complex");
    expect(reasonOf("@{c1}+".repeat(600) + "1")).toBe("too_complex");
    expect(reasonOf("SUM(" + "1,".repeat(1500) + "1)")).toBe("too_complex");
  });

  it("bounds nesting whatever form it takes", () => {
    expect(reasonOf("(".repeat(33) + "1" + ")".repeat(33))).toBe("too_deep");
    expect(reasonOf("-".repeat(40) + "1")).toBe("too_deep");
    expect(reasonOf("ABS(".repeat(33) + "1" + ")".repeat(33))).toBe("too_deep");
    expect(reasonOf("(".repeat(32) + "1" + ")".repeat(32))).toBe("accepted");
  });

  it("does not count brackets inside a text constant", () => {
    expect(reasonOf(`"${"(".repeat(100)}"`)).toBe("accepted");
  });

  it("gives the limit error when a bound on steps or on text is reached", () => {
    const tight = { maxSteps: 50, maxTextChars: 10 };
    const sum = parse("SUM(@{c1,r1}:@{c2,r40})", spec);
    expect(evaluate(sum, "r1", gridOf(new Map()), tight)).toEqual(E.LIMIT);
    const joined = parse('CONCATENATE("abcdef","abcdef")', spec);
    expect(evaluate(joined, "r1", gridOf(new Map()), tight)).toEqual(E.LIMIT);
  });

  it("calculates a deep but allowed formula", () => {
    const tree = parse("(".repeat(30) + "1+2" + ")".repeat(30), spec);
    expect(evaluate(tree, "r1", gridOf(new Map()), bounds)).toBe(3);
  });

  it("repeats nothing of a refused formula", () => {
    for (const formula of ['"SECRETTEXT', "MYNAMESECRETTEXT*2", "SECRETTEXT(1)", "'SECRETTEXT'!A1", "@{SECRET TEXT}"]) {
      try {
        parse(formula, spec);
        throw new Error("accepted");
      } catch (e) {
        expect(e).toBeInstanceOf(FormulaRefused);
        expect((e as FormulaRefused).message).not.toContain("SECRETTEXT");
        expect(WORDS[(e as FormulaRefused).reason]).not.toContain("SECRETTEXT");
      }
    }
  });

  it("matches a wildcard criterion without blowing up", () => {
    const cells = new Map<string, Value>();
    cells.set("c1|r1", "a".repeat(5000));
    const none = parse(toIds('COUNTIF(A1,"*a*a*a*a*a*a*a*a*a*b")', 2, flat), spec);
    const all = parse(toIds('COUNTIF(A1,"*a*a*a*a*a*a*a*a*a*a")', 2, flat), spec);
    expect(evaluate(none, "r2", gridOf(cells), bounds)).toBe(0);
    expect(evaluate(all, "r2", gridOf(cells), bounds)).toBe(1);
  });

  it("shows an error as its code", () => {
    expect(show(new XlError("#DIV/0!"))).toBe("#DIV/0!");
    expect(show(null)).toBe("");
    expect(show(true)).toBe("TRUE");
  });
});
