import { describe, expect, it } from "vitest";
import { lastUsedWords, usedByWords } from "./model";

describe("the words for a credential", () => {
  it("say never used when the API sends no hour", () => {
    expect(lastUsedWords({ lastUsedAt: undefined })).toBe("Never used");
  });

  it("show the hour of the latest use in the reader's own format", () => {
    expect(lastUsedWords({ lastUsedAt: "2026-10-30T10:00:00Z" })).toBe(new Date("2026-10-30T10:00:00Z").toLocaleString());
  });

  it("count the connections that name the credential", () => {
    expect(usedByWords({ usedBy: 0 })).toBe("No connection");
    expect(usedByWords({ usedBy: 1 })).toBe("1 connection");
    expect(usedByWords({ usedBy: 3 })).toBe("3 connections");
  });
});
