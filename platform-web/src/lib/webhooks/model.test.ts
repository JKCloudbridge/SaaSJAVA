import { describe, expect, it } from "vitest";
import {
  canReplay,
  deliveryStateWords,
  eventsWords,
  isFailing,
  isSwitchedOff,
  objectsText,
  objectsWords,
  parseObjects,
  stateWords,
  stoppedForMember,
  whenWords,
} from "./model";

const catalogue = {
  events: [
    { key: "record.created", title: "Record created", description: "A record was made." },
    { key: "record.updated", title: "Record changed", description: "A record was changed." },
  ],
  deliveryStates: [{ key: "DEAD_LETTERED", title: "Set aside", description: "Will not succeed by itself." }],
};

describe("the webhook model", () => {
  it("reads the objects typed one per line, separated by commas or spaces, without repeats", () => {
    expect(parseObjects("ObjectA__c\nObjectB__c, ObjectA__c ;ObjectC__c")).toEqual(["ObjectA__c", "ObjectB__c", "ObjectC__c"]);
  });

  it("treats an empty box as no list at all and says so in words", () => {
    expect(parseObjects("  \n ")).toBeUndefined();
    expect(objectsText(undefined)).toBe("");
    expect(objectsText(["ObjectA__c", "ObjectB__c"])).toBe("ObjectA__c\nObjectB__c");
    expect(objectsWords({})).toBe("Every object its member may read");
    expect(objectsWords({ objects: ["ObjectA__c"] })).toBe("ObjectA__c");
  });

  it("names the events of a webhook with the titles of the catalogue and falls back to the key", () => {
    expect(eventsWords(["record.created", "record.updated"], catalogue)).toBe("Record created, Record changed");
    expect(eventsWords(["record.future"], catalogue)).toBe("record.future");
    expect(eventsWords(["record.created"], undefined)).toBe("record.created");
  });

  it("names a state of a delivery with the catalogue's title and falls back to plain words", () => {
    expect(deliveryStateWords("DEAD_LETTERED", catalogue)).toBe("Set aside");
    expect(deliveryStateWords("SOMETHING_NEW", catalogue)).toBe("Something new");
    expect(deliveryStateWords("DONE", undefined)).toBe("Done");
  });

  it("shows a state as words and a missing time as the words given for none", () => {
    expect(stateWords("ACTIVE")).toBe("Active");
    expect(whenWords(undefined, "Never")).toBe("Never");
    expect(whenWords("2026-11-20T10:00:00Z", "Never")).not.toBe("Never");
  });

  it("recognises only the states the screens must tell apart", () => {
    expect(isSwitchedOff({ state: "DISABLED" })).toBe(true);
    expect(isSwitchedOff({ state: "ACTIVE" })).toBe(false);
    expect(isFailing({ state: "FAILING" })).toBe(true);
    expect(isFailing({ state: "ACTIVE" })).toBe(false);
    expect(stoppedForMember({ stoppedReason: "member_not_active" })).toBe(true);
    expect(stoppedForMember({ stoppedReason: "disabled" })).toBe(false);
    expect(stoppedForMember({})).toBe(false);
    expect(canReplay({ state: "DEAD_LETTERED" })).toBe(true);
    expect(canReplay({ state: "DONE" })).toBe(false);
  });
});
