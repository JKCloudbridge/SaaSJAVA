import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { lookSection } from "./caseFile";
import { DARK_INK_LUMINANCE, inkFor, LIGHT_INK_LUMINANCE, luminanceOf } from "./ink";

const tokens = readFileSync(path.join(process.cwd(), "src/styles/tokens.css"), "utf8");
const token = (name: string): string => {
  const found = new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`).exec(tokens);
  if (!found?.[1]) throw new Error(`${name} is not a plain colour`);
  return found[1];
};

// The ink of a cell with a fill and no stated text colour (SH4).
describe("the automatic ink", () => {
  for (const line of lookSection("ink")) {
    it(`line ${line.number}: a fill of ${line.fields[0]} gets the ${line.fields[1]} ink`, () => {
      expect(inkFor(`#${line.fields[0]}`)).toBe(line.fields[1]);
    });
  }

  it("is worked out from the luminance of the two ink tokens, written here as numbers", () => {
    expect(luminanceOf(token("--sheet-ink-dark"))).toBeCloseTo(DARK_INK_LUMINANCE, 3);
    expect(luminanceOf(token("--sheet-ink-light"))).toBeCloseTo(LIGHT_INK_LUMINANCE, 3);
  });

  it("reads at 4.5 to 1 or better on every fill when the ink is chosen this way, for the greys", () => {
    for (let grey = 0; grey <= 255; grey += 5) {
      const hex = grey.toString(16).padStart(2, "0");
      const fill = luminanceOf(`#${hex}${hex}${hex}`);
      const ink = inkFor(`#${hex}${hex}${hex}`) === "dark" ? DARK_INK_LUMINANCE : LIGHT_INK_LUMINANCE;
      const [high = 0, low = 0] = [fill, ink].sort((a, b) => b - a);
      expect((high + 0.05) / (low + 0.05), hex).toBeGreaterThanOrEqual(4.5);
    }
  });
});

// The marks that must show over any fill: two tones, so one of them always contrasts (SH4 item 8).
describe("the focus ring, the selection and the input mark", () => {
  it("are two tones, a dark and a light, so that over any fill one of them reads", () => {
    expect(luminanceOf(token("--sheet-ring-dark"))).toBeLessThan(0.05);
    expect(luminanceOf(token("--sheet-ring-light"))).toBeGreaterThan(0.9);
    expect(luminanceOf(token("--sheet-mark-ink"))).toBeLessThan(0.05);
    expect(luminanceOf(token("--sheet-mark-edge"))).toBeGreaterThan(0.9);
  });

  it("have a ring pair that reads at 3 to 1 or better against every grey fill, on at least one of the two", () => {
    const dark = luminanceOf(token("--sheet-ring-dark"));
    const light = luminanceOf(token("--sheet-ring-light"));
    for (let grey = 0; grey <= 255; grey += 5) {
      const hex = grey.toString(16).padStart(2, "0");
      const fill = luminanceOf(`#${hex}${hex}${hex}`);
      const ratio = (a: number, b: number) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      expect(Math.max(ratio(fill, dark), ratio(fill, light)), hex).toBeGreaterThanOrEqual(3);
    }
  });
});
