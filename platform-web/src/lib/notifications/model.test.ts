import { describe, expect, it, vi } from "vitest";
import { announceChange, badgeText, bellLabel, NOTIFICATIONS_CHANGED, safePath } from "./model";

describe("the number on the bell", () => {
  it("is empty for none, the number up to 99 and 99+ beyond", () => {
    expect(badgeText(0)).toBe("");
    expect(badgeText(-3)).toBe("");
    expect(badgeText(1)).toBe("1");
    expect(badgeText(99)).toBe("99");
    expect(badgeText(100)).toBe("99+");
  });

  it("is spoken as a count only when there is one", () => {
    expect(bellLabel(0)).toBe("Notifications");
    expect(bellLabel(7)).toBe("Notifications, 7 unread");
  });
});

describe("the place a notice leads to", () => {
  it("follows a path on the organization's own pages", () => {
    expect(safePath("/approvals")).toBe("/approvals");
    expect(safePath("/records/Expense__c/11111111-2222-3333-4444-555555555555")).toBe(
      "/records/Expense__c/11111111-2222-3333-4444-555555555555",
    );
  });

  it("follows nothing else: no address with a scheme, no path with two slashes, no backslash, nothing", () => {
    expect(safePath("https://elsewhere.example.test/")).toBeUndefined();
    expect(safePath("javascript:alert(1)")).toBeUndefined();
    expect(safePath("//elsewhere.example.test/")).toBeUndefined();
    expect(safePath("/\\elsewhere")).toBeUndefined();
    expect(safePath("")).toBeUndefined();
    expect(safePath(null)).toBeUndefined();
    expect(safePath(undefined)).toBeUndefined();
  });
});

describe("telling the rest of the page that the count changed", () => {
  it("dispatches one event the bell listens for", () => {
    const heard = vi.fn();
    window.addEventListener(NOTIFICATIONS_CHANGED, heard);

    announceChange();

    expect(heard).toHaveBeenCalledTimes(1);
    window.removeEventListener(NOTIFICATIONS_CHANGED, heard);
  });
});
