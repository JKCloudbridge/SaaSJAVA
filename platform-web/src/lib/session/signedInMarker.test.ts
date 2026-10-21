import { afterEach, describe, expect, it } from "vitest";
import { safeReturnPath } from "./returnPath";
import { returnPathAfterSignIn, takeSignedInMarker } from "./signedInMarker";

afterEach(() => {
  window.history.replaceState({}, "", "/");
});

describe("returnPathAfterSignIn", () => {
  it("marks a return to the home page", () => {
    expect(returnPathAfterSignIn("/")).toBe("/?signedIn=1");
  });

  it("leaves a deeper page the person asked for untouched", () => {
    expect(returnPathAfterSignIn("/records/Deal__c?tab=open")).toBe("/records/Deal__c?tab=open");
  });

  it("gives a path the sign-in page and the server both accept", () => {
    expect(safeReturnPath(returnPathAfterSignIn("/"))).toBe("/?signedIn=1");
  });
});

describe("takeSignedInMarker", () => {
  it("reads the marker once and removes it from the address, leaving the rest", () => {
    window.history.replaceState({}, "", "/?signedIn=1&a=b#x");

    expect(takeSignedInMarker()).toBe(true);
    expect(window.location.search).toBe("?a=b");
    expect(window.location.hash).toBe("#x");
    expect(takeSignedInMarker()).toBe(false);
  });

  it("is false for an ordinary visit and changes nothing", () => {
    window.history.replaceState({}, "", "/?a=b");

    expect(takeSignedInMarker()).toBe(false);
    expect(window.location.search).toBe("?a=b");
  });

  it("ignores any other value of the parameter", () => {
    window.history.replaceState({}, "", "/?signedIn=yes");

    expect(takeSignedInMarker()).toBe(false);
  });
});
