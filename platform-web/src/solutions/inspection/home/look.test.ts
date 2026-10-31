import { beforeEach, describe, expect, it } from "vitest";
import { readHomeLook, writeHomeLook } from "./look";

describe("the look of the home page", () => {
  beforeEach(() => window.localStorage.clear());

  it("is the first look until the person chooses the second", () => {
    expect(readHomeLook()).toBe(1);

    writeHomeLook(2);

    expect(readHomeLook()).toBe(2);
  });

  it("remembers the board look, and falls back to the first look for anything it does not know", () => {
    writeHomeLook(3);

    expect(readHomeLook()).toBe(3);

    writeHomeLook(4);

    expect(readHomeLook()).toBe(4);

    window.localStorage.setItem("home-look", "9");

    expect(readHomeLook()).toBe(1);
  });
});
