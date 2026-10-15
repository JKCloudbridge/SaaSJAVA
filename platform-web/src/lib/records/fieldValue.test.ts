import { describe, expect, it } from "vitest";
import { FIELD_TYPES } from "@/components/records/FieldRegistry";
import { everyType, field } from "@/test/records";
import {
  controlOf,
  defaultInput,
  emptyInput,
  formatValue,
  hintFor,
  hrefOfValue,
  inputFromValue,
  instantOfLocalInput,
  isEmptyInput,
  linkIdentifiers,
  localInputOfInstant,
  recordValue,
  sameInput,
  valueFromInput,
} from "./fieldValue";
import { DecimalText } from "./json";

describe("the 19 field types", () => {
  it("are all registered: a type the platform adds fails here until it is given a control and a reading", () => {
    const registered = everyType().map((one) => one.type);

    expect([...registered].sort()).toEqual([...FIELD_TYPES].sort());
    for (const one of everyType()) {
      expect(controlOf(one), one.type).toBeTruthy();
      expect(typeof formatValue(one, undefined), one.type).toBe("string");
    }
  });

  it("have a control that a person can type into, except what the platform calculates", () => {
    const controls = Object.fromEntries(everyType().map((one) => [one.type, controlOf(one)]));

    expect(controls).toMatchObject({
      TEXT: "text",
      LONG_TEXT: "textarea",
      NUMBER: "number",
      DECIMAL: "number",
      CURRENCY: "number",
      PERCENT: "number",
      BOOLEAN: "checkbox",
      DATE: "date",
      DATETIME: "datetime",
      TIME: "time",
      EMAIL: "email",
      PHONE: "tel",
      URL: "url",
      PICKLIST: "select",
      MULTI_PICKLIST: "multiselect",
      LOOKUP: "lookup",
      MASTER_DETAIL: "lookup",
      FORMULA: "none",
      AUTO_NUMBER: "none",
    });
  });
});

describe("what a control holds", () => {
  it("is empty for a field with no value, by type", () => {
    expect(emptyInput(field("a", "TEXT"))).toBe("");
    expect(emptyInput(field("a", "BOOLEAN"))).toBe(false);
    expect(emptyInput(field("a", "MULTI_PICKLIST"))).toEqual([]);
    expect(isEmptyInput(field("a", "BOOLEAN"), false)).toBe(false);
    expect(isEmptyInput(field("a", "TEXT"), "  ")).toBe(true);
    expect(isEmptyInput(field("a", "MULTI_PICKLIST"), [])).toBe(true);
  });

  it("is built from a stored value, and a number comes out without an exponent", () => {
    expect(inputFromValue(field("a", "NUMBER"), 1e21)).not.toMatch(/e/i);
    expect(inputFromValue(field("a", "DECIMAL"), 1500.5)).toBe("1500.5");
    expect(inputFromValue(field("a", "BOOLEAN"), true)).toBe(true);
    expect(inputFromValue(field("a", "MULTI_PICKLIST"), ["A", "B"])).toEqual(["A", "B"]);
    expect(inputFromValue(field("a", "TEXT"), null)).toBe("");
  });

  it("takes the default a field says, written as text", () => {
    expect(defaultInput(field("a", "BOOLEAN", { defaultValue: "true" }))).toBe(true);
    expect(defaultInput(field("a", "MULTI_PICKLIST", { defaultValue: "A;B" }))).toEqual(["A", "B"]);
    expect(defaultInput(field("a", "TEXT", { defaultValue: "hello" }))).toBe("hello");
    expect(defaultInput(field("a", "TEXT"))).toBe("");
  });

  it("compares inputs, lists in order", () => {
    expect(sameInput(["A"], ["A"])).toBe(true);
    expect(sameInput(["A", "B"], ["B", "A"])).toBe(false);
    expect(sameInput("x", "x")).toBe(true);
    expect(sameInput(true, false)).toBe(false);
  });
});

describe("what is sent for what was typed", () => {
  it("keeps a number as the digits typed, so nothing is rounded on the way", () => {
    const value = valueFromInput(field("a", "DECIMAL"), " 12345678901234567890.5 ");

    expect(value).toBeInstanceOf(DecimalText);
    expect((value as DecimalText).text).toBe("12345678901234567890.5");
  });

  it("turns a local date and time into the instant it names, and back", () => {
    // The tests run in UTC, so local time and UTC are the same here; the conversion itself is what is proved.
    expect(instantOfLocalInput("2030-01-31T09:30")).toBe("2030-01-31T09:30:00.000Z");
    expect(instantOfLocalInput("2030-01-31T09:30:15")).toBe("2030-01-31T09:30:15.000Z");
    expect(instantOfLocalInput("not a date")).toBeNull();
    expect(localInputOfInstant("2030-01-31T09:30:00Z")).toBe("2030-01-31T09:30");
    expect(localInputOfInstant("2030-01-31T09:30:15Z")).toBe("2030-01-31T09:30:15");
    expect(localInputOfInstant("nonsense")).toBe("");
  });

  it("trims what is not text, and sends a checkbox and a list as they are", () => {
    expect(valueFromInput(field("a", "DATE"), " 2030-01-31 ")).toBe("2030-01-31");
    expect(valueFromInput(field("a", "BOOLEAN"), true)).toBe(true);
    expect(valueFromInput(field("a", "MULTI_PICKLIST"), ["A"])).toEqual(["A"]);
    expect(valueFromInput(field("a", "TEXT"), "  keep my spaces  ")).toBe("  keep my spaces  ");
  });
});

describe("hints before a save is tried", () => {
  it("point at an empty required field a person can type into, and at nothing else that is empty", () => {
    expect(hintFor(field("a", "TEXT", { required: true }), "")).toBe("Enter a value for this field.");
    expect(hintFor(field("a", "TEXT", { required: true, editable: false }), "")).toBeUndefined();
    expect(hintFor(field("a", "TEXT"), "")).toBeUndefined();
  });

  it("use the server's words for a text that is too long", () => {
    expect(hintFor(field("a", "TEXT", { maxLength: 3 }), "abcd")).toBe("Is longer than the 3 characters this field allows.");
    expect(hintFor(field("a", "TEXT", { maxLength: 3 }), "abc")).toBeUndefined();
  });

  it("check a whole number's shape and its digits", () => {
    const whole = field("a", "NUMBER", { digits: 3 });

    expect(hintFor(whole, "12.5")).toBe("Enter a whole number.");
    expect(hintFor(whole, "abc")).toBe("Enter a whole number.");
    expect(hintFor(whole, "1234")).toBe("Has more than the 3 digits this field allows.");
    expect(hintFor(whole, "-123")).toBeUndefined();
    expect(hintFor(whole, "0007")).toBeUndefined();
  });

  it("check a decimal's shape, its decimals and its digits before the point", () => {
    const money = field("a", "CURRENCY", { precision: 6, scale: 2 });

    expect(hintFor(money, "1.5x")).toBe("Enter a number.");
    expect(hintFor(money, "1.234")).toBe("Has more than the 2 decimals this field allows.");
    expect(hintFor(money, "1.500")).toBeUndefined();
    expect(hintFor(money, "12345.5")).toBe("Has more than the 4 digits before the decimal point this field allows.");
    expect(hintFor(money, "1234.56")).toBeUndefined();
    expect(hintFor(money, "0.5")).toBeUndefined();
  });

  it("never claim a value is fine for what only the server can judge", () => {
    expect(hintFor(field("a", "EMAIL"), "not-an-address")).toBeUndefined();
    expect(hintFor(field("a", "PICKLIST"), "whatever")).toBeUndefined();
    expect(hintFor(field("a", "DATETIME"), "garbage")).toBe("Enter a date and time.");
  });
});

describe("what is shown for a value", () => {
  const stage = everyType().find((one) => one.apiName === "stage__c")!;
  const tags = everyType().find((one) => one.apiName === "tags__c")!;

  it("reads a checkbox, numbers, a percentage and an amount in words", () => {
    expect(formatValue(field("a", "BOOLEAN"), true)).toBe("Yes");
    expect(formatValue(field("a", "BOOLEAN"), false)).toBe("No");
    expect(formatValue(field("a", "NUMBER"), 12345)).toBe("12,345");
    expect(formatValue(field("a", "CURRENCY", { scale: 2 }), 1500.5)).toBe("1,500.50");
    expect(formatValue(field("a", "PERCENT", { scale: 1 }), 12.5)).toBe("12.5%");
    expect(formatValue(field("a", "DECIMAL", { scale: 2 }), 3.14159)).toBe("3.14");
  });

  it("reads a day without a time zone moving it", () => {
    expect(formatValue(field("a", "DATE"), "2030-01-31")).toMatch(/2030/);
    expect(formatValue(field("a", "DATE"), "2030-01-31")).toMatch(/31/);
  });

  it("reads picklists by label, and a value the list lost as it was stored", () => {
    expect(formatValue(stage, "WON")).toBe("Won");
    expect(formatValue(stage, "GONE")).toBe("GONE");
    expect(formatValue(tags, ["A", "B"])).toBe("Alpha, Beta");
  });

  it("reads a formula as the type of its result", () => {
    expect(formatValue(field("a", "FORMULA", { resultType: "BOOLEAN", calculated: true }), true)).toBe("Yes");
    expect(formatValue(field("a", "FORMULA", { resultType: "CURRENCY", scale: 2, calculated: true }), 5)).toBe("5.00");
  });

  it("names a link, and never shows an identifier when the name is not known", () => {
    const link = field("a", "LOOKUP");

    expect(formatValue(link, "11111111-1111-1111-1111-111111111111", { "11111111-1111-1111-1111-111111111111": "User A" })).toBe("User A");
    expect(formatValue(link, "22222222-2222-2222-2222-222222222222", {})).toBe("Linked record");
  });

  it("is empty for no value", () => {
    expect(formatValue(field("a", "TEXT"), undefined)).toBe("");
    expect(formatValue(field("a", "TEXT"), null)).toBe("");
    expect(formatValue(field("a", "TEXT"), "")).toBe("");
  });

  it("makes a link only of a mail address, a telephone number and a web address that is http or https", () => {
    expect(hrefOfValue(field("a", "EMAIL"), "user-a@example.test")).toBe("mailto:user-a@example.test");
    expect(hrefOfValue(field("a", "PHONE"), "+1 (555) 010-0000")).toBe("tel:+15550100000");
    expect(hrefOfValue(field("a", "URL"), "https://example.test/x")).toBe("https://example.test/x");
    expect(hrefOfValue(field("a", "URL"), "javascript:alert(1)")).toBeUndefined();
    expect(hrefOfValue(field("a", "URL"), "data:text/html,x")).toBeUndefined();
    expect(hrefOfValue(field("a", "TEXT"), "https://example.test")).toBeUndefined();
  });
});

describe("a record's values", () => {
  const record = { id: "i", ownerId: "o", createdAt: "c", updatedAt: "u", fields: { name__c: "n", id2__c: "x" } };

  it("come from the values, and for the system fields from beside them", () => {
    expect(recordValue(record, "name__c")).toBe("n");
    expect(recordValue(record, "ownerId")).toBe("o");
    expect(recordValue(record, "createdAt")).toBe("c");
    expect(recordValue(record, "updatedAt")).toBe("u");
    expect(recordValue(record, "id")).toBe("i");
    expect(recordValue(record, "missing__c")).toBeUndefined();
  });

  it("name the links to ask the API about, once each, the owner included", () => {
    const fields = [field("ownerId", "LOOKUP", { kind: "SYSTEM" }), field("account__c", "LOOKUP"), field("name__c", "TEXT")];
    const found = linkIdentifiers(fields, [
      { id: "1", ownerId: "u1", fields: { account__c: "a1" } },
      { id: "2", ownerId: "u1", fields: { account__c: "a1" } },
      { id: "3", ownerId: "u2", fields: {} },
    ]);

    expect(found).toEqual({ ownerId: ["u1", "u2"], account__c: ["a1"] });
  });
});
