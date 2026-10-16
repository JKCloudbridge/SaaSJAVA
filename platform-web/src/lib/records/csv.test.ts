import { describe, expect, it } from "vitest";
import { everyType } from "@/test/records";
import { guessDelimiter, importableFields, parseHeader, suggestMapping } from "./csv";

describe("the header row of a file", () => {
  it("is split at the delimiter", () => {
    expect(parseHeader("name,amount,stage", ",")).toEqual(["name", "amount", "stage"]);
    expect(parseHeader("name;amount", ";")).toEqual(["name", "amount"]);
    expect(parseHeader("name\tamount", "\t")).toEqual(["name", "amount"]);
  });

  it("reads a quoted name that holds the delimiter or a doubled quote as one column", () => {
    expect(parseHeader('"Last, First",amount,"say ""hi"""', ",")).toEqual(["Last, First", "amount", 'say "hi"']);
  });

  it("stops at the end of the first line and trims", () => {
    expect(parseHeader(" a , b \nc,d", ",")).toEqual(["a", "b"]);
    expect(parseHeader("a,b\r\nc,d", ",")).toEqual(["a", "b"]);
  });

  it("does not count a trailing delimiter as a column", () => {
    expect(parseHeader("a,b,", ",")).toEqual(["a", "b"]);
  });
});

describe("the delimiter a file seems to use", () => {
  it("is the one that splits the first line into the most columns", () => {
    expect(guessDelimiter("a,b,c")).toBe("COMMA");
    expect(guessDelimiter("a;b;c")).toBe("SEMICOLON");
    expect(guessDelimiter("a\tb\tc")).toBe("TAB");
  });
});

describe("which column fills which field", () => {
  const fields = importableFields(everyType().map((one) => (one.apiName === "text__c" ? { ...one, label: "Full name" } : one)));

  it("is guessed from the column's name: the API name or the label, in any case and punctuation", () => {
    const mapping = suggestMapping(["Full Name", "WHOLE", "stage", "unknown column"], fields);

    expect(mapping).toEqual({ "Full Name": "text__c", WHOLE: "whole__c", stage: "stage__c" });
  });

  it("gives one field to one column only", () => {
    const mapping = suggestMapping(["Full name", "full_name"], fields);

    expect(Object.values(mapping)).toEqual(["text__c"]);
  });

  it("offers what a person can type into, and not what the platform calculates", () => {
    const names = fields.map((one) => one.apiName);

    expect(names).toContain("text__c");
    expect(names).not.toContain("calc__c");
    expect(names).not.toContain("auto__c");
  });
});
