import { beforeEach, describe, expect, it } from "vitest";
import { readHomeLook, writeHomeLook } from "./look";

describe("the look of the home page", () => {
  beforeEach(() => window.localStorage.clear());

  it("is the first look until the person chooses the second", () => {
    expect(readHomeLook()).toBe(1);

    writeHomeLook(2);

    expect(readHomeLook()).toBe(2);
  });
});
