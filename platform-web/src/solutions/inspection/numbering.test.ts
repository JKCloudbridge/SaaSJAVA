import { describe, expect, it } from "vitest";
import { digitsOnly, hiddenIdentityFields, identityOf, normalizePart, padSerial, sameIdentity } from "./numbering";

describe("the parts of a number (FA11B)", () => {
  it("corrects Part A and Part B as they are typed: capitals, digits and a hyphen, at most 10", () => {
    expect(normalizePart("ab c/1_x-y")).toBe("ABC1X-Y");
    expect(normalizePart("abcdefghijklmnop")).toBe("ABCDEFGHIJ");
    expect(normalizePart("")).toBe("");
  });

  it("allows only digits in a serial, at most 12", () => {
    expect(digitsOnly("1a2-3")).toBe("123");
    expect(digitsOnly("1234567890123")).toBe("123456789012");
  });

  it("writes a serial with the zeros the format pads to and never cuts it", () => {
    expect(padSerial(5, 2)).toBe("05");
    expect(padSerial("123", 2)).toBe("123");
    expect(padSerial(7, 0)).toBe("7");
  });

  it("reads the identity from the fields of a record and compares two", () => {
    const held = identityOf({ category: "WI", partA: "PRD", other: 1 });

    expect(held).toEqual({ category: "WI", partA: "PRD", partB: "" });
    expect(sameIdentity(held, { category: "WI", partA: "PRD", partB: "" })).toBe(true);
    expect(sameIdentity(held, { category: "WI", partA: "QLT", partB: "" })).toBe(false);
  });
});

describe("which parts the screen leaves out (FA11B)", () => {
  const none = { category: "", partA: "", partB: "" };

  it("follows the server's answer for the chosen identification", () => {
    expect(hiddenIdentityFields({ known: true, partA: true, partB: false }, none, true)).toEqual(["serial", "partB"]);
    expect(hiddenIdentityFields({ known: true, partA: true, partB: true }, none, true)).toEqual(["serial"]);
    expect(hiddenIdentityFields({ known: true, partA: false, partB: false }, none, false)).toEqual(["partA", "partB"]);
  });

  it("shows a part that holds a value while nothing is known yet, and no part otherwise", () => {
    expect(hiddenIdentityFields(undefined, { category: "CP", partA: "ABC", partB: "" }, true)).toEqual(["serial", "partB"]);
    expect(hiddenIdentityFields(undefined, none, true)).toEqual(["serial", "partA", "partB"]);
  });

  it("asks for nothing when the identification has no number format", () => {
    expect(hiddenIdentityFields({ known: false, partA: false, partB: false }, none, true)).toEqual(["serial", "partA", "partB"]);
  });
});
