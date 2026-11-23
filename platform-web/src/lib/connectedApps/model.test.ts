import { describe, expect, it } from "vitest";
import {
  isSwitchedOff,
  lastUsedWords,
  objectsText,
  objectsWords,
  parseObjects,
  secretEnded,
  stateWords,
  stoppedForMember,
} from "./model";

describe("the connected application model", () => {
  it("reads the objects typed one per line, separated by commas or spaces, without repeats", () => {
    expect(parseObjects("ObjectA__c\nObjectB__c, ObjectA__c ;ObjectC__c")).toEqual(["ObjectA__c", "ObjectB__c", "ObjectC__c"]);
  });

  it("treats an empty box as no list at all", () => {
    expect(parseObjects("  \n ")).toBeUndefined();
    expect(objectsText(undefined)).toBe("");
    expect(objectsText(["ObjectA__c", "ObjectB__c"])).toBe("ObjectA__c\nObjectB__c");
  });

  it("says an application with no list may use every object its member may reach", () => {
    expect(objectsWords({})).toBe("Every object its member may reach");
    expect(objectsWords({ objects: ["ObjectA__c"] })).toBe("ObjectA__c");
  });

  it("shows a state as words and never a time as a guess", () => {
    expect(stateWords("ACTIVE")).toBe("Active");
    expect(lastUsedWords({})).toBe("Never used");
    expect(lastUsedWords({ lastUsedAt: "2026-11-20T10:00:00Z" })).not.toBe("Never used");
  });

  it("recognises only the states the screens must tell apart", () => {
    expect(isSwitchedOff({ state: "DISABLED" })).toBe(true);
    expect(isSwitchedOff({ state: "ACTIVE" })).toBe(false);
    expect(secretEnded({ state: "ENDED" })).toBe(true);
    expect(secretEnded({ state: "ENDING" })).toBe(false);
    expect(stoppedForMember({ stoppedReason: "member_not_active" })).toBe(true);
    expect(stoppedForMember({ stoppedReason: "disabled" })).toBe(false);
    expect(stoppedForMember({})).toBe(false);
  });
});
