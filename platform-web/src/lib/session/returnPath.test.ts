import { describe, expect, it } from "vitest";
import { safeReturnPath } from "./returnPath";

describe("safeReturnPath", () => {
  it("keeps a path on this site, with its query", () => {
    expect(safeReturnPath("/")).toBe("/");
    expect(safeReturnPath("/reports/2026?tab=open")).toBe("/reports/2026?tab=open");
  });

  it("takes the first value when the address repeats the parameter", () => {
    expect(safeReturnPath(["/a", "/b"])).toBe("/a");
  });

  it("turns anything that could leave the site into the home page", () => {
    for (const candidate of [
      undefined,
      null,
      "",
      "https://evil.example.test",
      "//evil.example.test",
      "///evil.example.test",
      "\\\\evil.example.test",
      "/\\evil.example.test",
      "evil.example.test/path",
      "javascript:alert(1)",
      "/ok\r\nSet-Cookie: x=y",
      "/" + "a".repeat(300),
    ]) {
      expect(safeReturnPath(candidate as string | undefined)).toBe("/");
    }
  });
});
