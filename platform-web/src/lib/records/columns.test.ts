import { describe, expect, it } from "vitest";
import { arrange, type ArrangedSection } from "./columns";

const section = (left?: readonly string[]): ArrangedSection => ({
  label: "Details",
  columns: 2,
  fields: ["a", "b", "c", "d", "e"],
  left,
});

describe("sections with two named columns (FA11A)", () => {
  it("leaves a section without a left column to be drawn row by row", () => {
    const result = arrange(section(), ["a", "b", "c"]);

    expect(result.placed).toBe(false);
    expect(result.names).toEqual(["a", "b", "c"]);
    expect(result.styleOf("a")).toBeUndefined();
  });

  it("runs the named fields down the left column and every other down the right, each in its given order", () => {
    const result = arrange(section(["a", "c", "e"]), ["a", "b", "c", "d", "e"]);

    expect(result.placed).toBe(true);
    expect(result.names).toEqual(["a", "c", "e", "b", "d"]);
    const at = (name: string) => result.styleOf(name) as Record<string, number>;
    expect([at("a")["--col"], at("a")["--row"]]).toEqual([1, 1]);
    expect([at("e")["--col"], at("e")["--row"]]).toEqual([1, 3]);
    expect([at("b")["--col"], at("b")["--row"]]).toEqual([2, 1]);
    expect([at("d")["--col"], at("d")["--row"]]).toEqual([2, 2]);
  });

  it("closes the gap when a named field is not drawn", () => {
    const result = arrange(section(["a", "c", "e"]), ["a", "e", "b"]);

    expect(result.names).toEqual(["a", "e", "b"]);
    expect((result.styleOf("e") as Record<string, number>)["--row"]).toBe(2);
  });
});
