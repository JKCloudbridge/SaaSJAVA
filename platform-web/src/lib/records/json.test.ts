import { describe, expect, it } from "vitest";
import { DecimalText, plainNumber, stringifyBody } from "./json";

describe("a decimal that must reach the API exactly", () => {
  it("is written with the digits that were typed, not as a rounded number", () => {
    const text = stringifyBody({ fields: { amount__c: new DecimalText("12345678901234567890.123456"), name__c: "x" } });

    expect(text).toBe('{"fields":{"amount__c":12345678901234567890.123456,"name__c":"x"}}');
    // A plain number would have lost the digits: this is why the serializer exists.
    expect(JSON.stringify({ a: Number("12345678901234567890.123456") })).not.toContain("890.123456");
  });

  it("is written as a number, also inside lists and for negative values and whole numbers", () => {
    expect(stringifyBody([new DecimalText("-4.50"), new DecimalText("7")])).toBe("[-4.50,7]");
  });

  it("refuses anything that is not plain digits, so nothing but a number is ever written unquoted", () => {
    for (const hostile of ['1,"x":2', "1e5", "0x10", " 1", "1 ", "", "--1", "1.", ".5", '1}{"y":1']) {
      expect(() => stringifyBody({ a: new DecimalText(hostile) }), hostile).toThrow();
    }
  });

  it("never lets a typed text become a number, whatever it looks like", () => {
    expect(stringifyBody({ a: "12345", b: "-1.5", c: '1,"x":2' })).toBe('{"a":"12345","b":"-1.5","c":"1,\\"x\\":2"}');
  });

  it("writes everything else exactly as JSON does, and leaves out what JSON leaves out", () => {
    const body = { s: "é\n", n: 1.5, t: true, f: false, z: null, u: undefined, list: [1, undefined, "x"], nested: { k: [] } };

    expect(stringifyBody(body)).toBe(JSON.stringify(body));
    expect(stringifyBody(undefined)).toBe("null");
  });
});

describe("a number shown as text", () => {
  it("has no exponent", () => {
    expect(plainNumber(1500.5)).toBe("1500.5");
    expect(plainNumber(1e21)).not.toMatch(/e/i);
    expect(plainNumber(0.0000001)).not.toMatch(/e/i);
    expect(plainNumber(Number.NaN)).toBe("");
  });
});
