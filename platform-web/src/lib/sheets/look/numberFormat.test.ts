import { describe, expect, it } from "vitest";
import { lookSection } from "./caseFile";
import { NUMBER_FORMAT_KEYS, type NumberFormatKey } from "./look";
import { formatNumber, formatShown, formatsNumbers, isPlainNumber, type AnswerKind } from "./numberFormat";

// The number formats (SH4): the cases of the file the server's tests read too, written from the documented behaviour of a
// spreadsheet program before this code existed.
describe("number formats against the shared cases", () => {
  const cases = lookSection("number");

  it("has a case for every key of the closed list", () => {
    const seen = new Set(cases.map((line) => line.fields[0]));
    for (const key of NUMBER_FORMAT_KEYS) {
      expect(seen.has(key), key).toBe(true);
    }
  });

  for (const line of cases) {
    const [key, kind, value, expected] = line.fields;
    it(`line ${line.number}: ${key} ${kind} "${value}" is shown as "${expected}"`, () => {
      expect(formatShown(key as NumberFormatKey, kind as AnswerKind, value ?? "")).toBe(expected);
    });
  }
});

describe("number formats beyond the cases", () => {
  it("rounds on the decimal digits, half away from zero, never on a binary value", () => {
    expect(formatNumber("TWO_DECIMALS", "2.675")).toBe("2.68");
    expect(formatNumber("TWO_DECIMALS", "1.45")).toBe("1.45");
    expect(formatNumber("WHOLE", "0.5")).toBe("1");
    expect(formatNumber("WHOLE", "-0.5")).toBe("-1");
    expect(formatNumber("TWO_DECIMALS", "0.995")).toBe("1.00");
    expect(formatNumber("TWO_DECIMALS", "9.995")).toBe("10.00");
    expect(formatNumber("WHOLE", "999.5")).toBe("1000");
  });

  it("carries a rounding up through the places", () => {
    expect(formatNumber("THOUSANDS", "999999.5")).toBe("1,000,000");
    expect(formatNumber("PERCENT", "9.995")).toBe("1000%");
  });

  it("limits a number to fifteen significant digits, as the program does", () => {
    expect(formatNumber("THOUSANDS", "1234567890123456789")).toBe("1,234,567,890,123,460,000");
  });

  it("leaves a text that is not a number, a nonsense exponent and every non-numeric key alone", () => {
    expect(formatNumber("WHOLE", "abc")).toBe("abc");
    expect(formatNumber("WHOLE", "")).toBe("");
    expect(formatNumber("WHOLE", "1e999")).toBe("1e999");
    expect(formatNumber("GENERAL", "1.50")).toBe("1.50");
    expect(formatNumber("SHORT_DATE", "45000")).toBe("45000");
    expect(formatsNumbers("MONTH_YEAR")).toBe(false);
    expect(formatsNumbers("ACCOUNTING")).toBe(true);
  });

  it("formats only a number: any other kind is shown as it is", () => {
    expect(formatShown("PERCENT", "text", "0.5")).toBe("0.5");
    expect(formatShown("PERCENT", "boolean", "TRUE")).toBe("TRUE");
    expect(formatShown("PERCENT", "number", "0.5")).toBe("50%");
  });

  it("recognises a plain number", () => {
    for (const text of ["1", "-1", "1.5", ".5", "1.", "1e3", "-1.5E-3"]) expect(isPlainNumber(text), text).toBe(true);
    for (const text of ["", "1,000", "1 2", "abc", "--1", "1e", "0x10", "+1"]) expect(isPlainNumber(text), text).toBe(false);
  });
});
