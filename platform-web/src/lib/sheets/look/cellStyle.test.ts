import { describe, expect, it } from "vitest";
import { describe as describeLook, descriptionsKept, forgetDescriptions } from "./cellStyle";
import { NO_LOOK, readLook } from "./look";

// How a look reaches the page (SH4): fixed classes and three custom properties, nothing else.
describe("the description of a look", () => {
  it("says nothing for a look that says nothing", () => {
    expect(describeLook(NO_LOOK, {})).toEqual({ classes: [], props: {} });
  });

  it("is made of fixed class names and the three custom properties", () => {
    const look = readLook({
      font: "SERIF_SLAB",
      size: 13,
      colour: "#112233",
      fill: "#ddeeff",
      wrap: true,
      verticalAlignment: "MIDDLE",
    });
    const made = describeLook(look, { right: "THIN", bottom: "DOUBLE" });

    expect(made.classes).toEqual(
      ["sheet-font-SERIF_SLAB", "sheet-pt", "sheet-has-fill", "sheet-has-text", "sheet-wrap", "sheet-v-middle", "sheet-br-thin", "sheet-bb-double"],
    );
    expect(made.props).toEqual({ "--sheet-pt": "13", "--sheet-fill": "#ddeeff", "--sheet-text": "#112233" });
  });

  it("chooses an ink for a fill with no text colour, and none when the text colour is stated", () => {
    expect(describeLook(readLook({ fill: "#000000" }), {}).classes).toContain("sheet-ink-light");
    expect(describeLook(readLook({ fill: "#ffffff" }), {}).classes).toContain("sheet-ink-dark");
    expect(describeLook(readLook({ fill: "#000000", colour: "#ffffff" }), {}).classes.some((name) => name.startsWith("sheet-ink-"))).toBe(false);
    expect(describeLook(readLook({ colour: "#ffffff" }), {}).classes.some((name) => name.startsWith("sheet-ink-"))).toBe(false);
  });

  it("lets a condition paint over the look's own fill and text colour", () => {
    const look = readLook({ fill: "#ffffff", colour: "#000000" });
    const painted = describeLook(look, {}, { fill: "#ff0000", colour: "#eeeeee" });

    expect(painted.props).toEqual({ "--sheet-fill": "#ff0000", "--sheet-text": "#eeeeee" });
    expect(describeLook(look, {}).props).toEqual({ "--sheet-fill": "#ffffff", "--sheet-text": "#000000" });
  });

  it("puts nothing a person typed into a class name or a property: only checked values reach it", () => {
    const made = describeLook(readLook({ font: "x; color: red", fill: "red", colour: "url(javascript:1)", size: "99" }), {});

    expect(made).toEqual({ classes: [], props: {} });
  });

  it("makes one description for one look, so fifty thousand cells make a few dozen, not fifty thousand (threshold T6: at most 2,000)", () => {
    forgetDescriptions();
    const fills = Array.from({ length: 8 }, (_, i) => `#${(i * 30 + 10).toString(16).padStart(2, "0")}aa00`);
    const sizes = [9, 11, 14, 18, 22];
    let same = 0;
    for (let cell = 0; cell < 50_000; cell++) {
      const look = readLook({ fill: fills[cell % fills.length], size: sizes[cell % sizes.length], wrap: cell % 3 === 0 });
      const made = describeLook(look, { right: cell % 2 === 0 ? "THIN" : undefined, bottom: "THIN" });
      if (made === describeLook(look, { right: cell % 2 === 0 ? "THIN" : undefined, bottom: "THIN" })) same++;
    }

    expect(same).toBe(50_000);
    expect(descriptionsKept()).toBeLessThanOrEqual(2000);
    expect(descriptionsKept()).toBeLessThanOrEqual(8 * 5 * 3 * 2);
  });
});
