import { describe, expect, it } from "vitest";
import { lifecycleText, stateTag, type LifecycleNumbers } from "./lifecycle";

const none: LifecycleNumbers = {
  state: "NONE",
  activeVersion: 0,
  approvedVersion: 0,
  inReviewVersion: 0,
  draftVersion: 0,
  retiredVersion: 0,
  highestVersion: 0,
};

describe("lifecycleText", () => {
  it("says the revision in use first and what waits behind it", () => {
    expect(lifecycleText({ ...none, state: "ACTIVE", activeVersion: 3, inReviewVersion: 4, highestVersion: 4 })).toBe(
      "Active Rev 02, In review",
    );
    expect(
      lifecycleText({ ...none, state: "ACTIVE", activeVersion: 3, inReviewVersion: 4, draftVersion: 5, highestVersion: 5 }),
    ).toBe("Active Rev 02, In review, Draft");
  });

  it("says that an approved version is not yet published", () => {
    expect(lifecycleText({ ...none, state: "APPROVED", approvedVersion: 2, highestVersion: 2 })).toBe(
      "Approved, not published",
    );
    expect(lifecycleText({ ...none, state: "ACTIVE", activeVersion: 2, approvedVersion: 3, highestVersion: 3 })).toBe(
      "Active Rev 01, Approved, not published",
    );
  });

  it("does not mention a number that is zero, and says so when there is nothing", () => {
    expect(lifecycleText({ ...none, state: "DRAFT", draftVersion: 1, highestVersion: 1 })).toBe("Draft");
    expect(lifecycleText(none)).toBe("No revision yet");
  });

  it("uses the revision number the platform saved on the template, which has no gaps (FA8)", () => {
    expect(lifecycleText({ ...none, state: "ACTIVE", activeVersion: 4, highestVersion: 4 }, "01")).toBe("Active Rev 01");
    expect(lifecycleText({ ...none, state: "RETIRED", retiredVersion: 4, highestVersion: 4 }, "01")).toBe(
      "Retired, Rev 01 was the last in use",
    );
  });

  it("says a retired template is retired and which version was last in use", () => {
    expect(lifecycleText({ ...none, state: "RETIRED", retiredVersion: 3, highestVersion: 3 })).toBe(
      "Retired, Rev 02 was the last in use",
    );
    expect(lifecycleText({ ...none, state: "RETIRED" })).toBe("Retired");
  });
});

describe("stateTag", () => {
  it("gives every state of the API its words, and shows one it does not know as it is", () => {
    expect(stateTag("ACTIVE")).toEqual({ text: "Active", tone: "active" });
    expect(stateTag("APPROVED").text).toBe("Approved, not published");
    expect(stateTag("IN_REVIEW").tone).toBe("review");
    expect(stateTag("RETIRED").tone).toBe("done");
    expect(stateTag("SOMETHING").text).toBe("SOMETHING");
  });
});
