import { describe, expect, it } from "vitest";
import { cloneHref, objectNewHref, relatedListHref, whereOf } from "./paths";

const ID = "3f2a9c1e-8b7d-4e6f-9a10-5c4d3b2a1f00";

describe("the addresses of the record page", () => {
  it("narrows a list to the records that point at one record, with names and an identifier only", () => {
    expect(relatedListHref("Contact__c", "deal__c", ID)).toBe(`/records/Contact__c?where=deal__c:${ID}`);
  });

  it("starts the create form from a copy of a record", () => {
    expect(cloneHref(objectNewHref("Deal__c"), "r 1")).toBe("/records/Deal__c/new?cloneFrom=r%201");
  });

  it("reads a narrowed list's address back", () => {
    expect(whereOf(`deal__c:${ID}`)).toEqual({ field: "deal__c", value: ID });
  });

  it.each([null, "", "deal__c", ":x", `1bad:${ID}`, `deal__c:not-an-identifier`, `de al:${ID}`, `deal__c;drop:${ID}`])(
    "ignores an address that is not a field and an identifier: %s",
    (value) => {
      expect(whereOf(value)).toBeUndefined();
    },
  );
});
