import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const srcDir = path.resolve(__dirname, "..");
const tokensFile = path.join(__dirname, "tokens.css");

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) {
      return name === "generated" ? [] : sourceFiles(full);
    }
    return /\.(css|tsx?)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : [];
  });
}

describe("design tokens", () => {
  const css = readFileSync(tokensFile, "utf8");

  it("define the colour roles, spacing scale, shape and type the components use", () => {
    for (const name of [
      "--color-background",
      "--color-surface",
      "--color-text",
      "--color-text-muted",
      "--color-border",
      "--color-accent",
      "--color-danger",
      "--color-success",
      "--space-1",
      "--space-4",
      "--radius-md",
      "--font-sans",
      "--font-size-md",
    ]) {
      expect(css, name).toContain(`${name}:`);
    }
  });

  it("define the roles of the approved visual design (soft accent, wider radii, half steps, popover shadow)", () => {
    for (const name of [
      "--color-accent-soft",
      "--color-accent-hover",
      "--radius-lg",
      "--radius-pill",
      "--space-2-5",
      "--space-3-5",
      "--space-4-5",
      "--shadow-2",
      "--font-size-xs",
    ]) {
      expect(css, name).toContain(`${name}:`);
    }
  });

  it("serve the fonts from this application and never name a third party's address", () => {
    const layout = readFileSync(path.join(srcDir, "app", "layout.tsx"), "utf8");

    expect(css).toContain("--font-plex-sans");
    expect(layout).toContain("next/font/local");
    for (const file of [...sourceFiles(srcDir), tokensFile]) {
      expect(readFileSync(file, "utf8"), file).not.toMatch(/fonts\.(googleapis|gstatic)\.com/);
    }
  });

  it("redefine the colour roles for dark mode instead of leaving them light", () => {
    const dark = css.slice(css.indexOf("prefers-color-scheme: dark"));

    for (const role of [
      "--color-background",
      "--color-surface",
      "--color-text",
      "--color-accent",
      "--color-accent-soft",
      "--color-brand-panel",
      "--color-danger",
    ]) {
      expect(dark, role).toContain(`${role}:`);
    }
  });

  it("are the only place with a literal colour", () => {
    const literal = /#[0-9a-fA-F]{3,8}\b|\b(rgb|rgba|hsl|hsla)\(/;
    const offenders = sourceFiles(srcDir)
      .filter((file) => file !== tokensFile)
      .filter((file) => literal.test(readFileSync(file, "utf8")))
      .map((file) => path.relative(srcDir, file));

    expect(offenders, "literal colours belong in tokens.css").toEqual([]);
  });

  it("are referenced by the stylesheet that builds the shell", () => {
    const globals = readFileSync(path.join(srcDir, "app", "globals.css"), "utf8");

    expect(globals).toContain('@import "../styles/tokens.css"');
    expect(globals).toContain("var(--color-surface)");
  });
  describe("the plum accent (FA6D)", () => {
    const light = css.slice(0, css.indexOf("prefers-color-scheme: dark"));
    const dark = css.slice(css.indexOf("prefers-color-scheme: dark"));
    const role = (block: string, name: string): string => {
      const found = new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`).exec(block);
      if (!found?.[1]) {
        throw new Error(`${name} is not a plain colour in its block`);
      }
      return found[1].toLowerCase();
    };
    const luminance = (hex: string): number => {
      const [r = 0, g = 0, b = 0] = [1, 3, 5].map((at) => {
        const channel = parseInt(hex.slice(at, at + 2), 16) / 255;
        return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const contrast = (a: string, b: string): number => {
      const [high = 0, low = 0] = [luminance(a), luminance(b)].sort((x, y) => y - x);
      return (high + 0.05) / (low + 0.05);
    };

    it("uses the product colour for the accent, the focus ring and the sign-in panel, and keeps white white", () => {
      expect(role(light, "--color-accent")).toBe("#93418b");
      expect(role(light, "--color-focus-ring")).toBe("#93418b");
      expect(role(light, "--color-accent-soft")).toBe("#f6eef8");
      expect(role(light, "--color-brand-panel")).toBe("#f6eef8");
      expect(role(light, "--color-surface")).toBe("#ffffff");
      expect(role(light, "--color-accent-contrast")).toBe("#ffffff");
    });

    it("keeps the old green nowhere in the tokens", () => {
      expect(css).not.toMatch(/0b6e67|095a54|e3f1ef|2dd4bf|5eead4|0e2e2c/i);
    });

    it("reads at WCAG AA (4.5 to 1) on every pair of accent and background, in both themes", () => {
      for (const [name, block] of [
        ["light", light],
        ["dark", dark],
      ] as const) {
        const accent = role(block, "--color-accent");
        const hover = role(block, "--color-accent-hover");
        const contrastText = role(block, "--color-accent-contrast");
        const soft = role(block, "--color-accent-soft");
        const surface = role(block, "--color-surface");
        const background = role(block, "--color-background");
        for (const [what, a, b] of [
          ["text on the accent", contrastText, accent],
          ["text on the hover colour", contrastText, hover],
          ["accent on a panel", accent, surface],
          ["accent on the page", accent, background],
          ["accent on the soft surface", accent, soft],
          ["hover colour on a panel", hover, surface],
        ] as const) {
          expect(contrast(a, b), `${name}: ${what}`).toBeGreaterThanOrEqual(4.5);
        }
      }
    });

    it("draws a fixed cell of a sheet light grey, a cell to fill white, and keeps both readable in either theme (SH5.1)", () => {
      // The paper is defined once, never in the dark block: a sheet looks the same in both themes.
      expect(dark).not.toContain("--sheet-paper-fixed:");
      const grey = role(light, "--sheet-paper-fixed");
      const white = role(light, "--sheet-paper-surface");
      expect(white).toBe("#ffffff");
      // the text of a sheet and its quieter words read at AA on the grey (the text at AAA)
      expect(contrast(role(light, "--sheet-paper-text"), grey), "text on the grey").toBeGreaterThanOrEqual(7);
      expect(contrast(role(light, "--sheet-paper-text-muted"), grey), "muted text on the grey").toBeGreaterThanOrEqual(4.5);
      expect(contrast(role(light, "--sheet-paper-accent"), grey), "accent on the grey").toBeGreaterThanOrEqual(4.5);
      // the grey is told apart from white, and is still light enough that the dark ink is the one chosen over it
      expect(contrast(grey, white), "grey beside white").toBeGreaterThan(1.15);
      expect(luminance(grey)).toBeGreaterThan(0.7);
    });

    it("defines six series colours in both themes that read as marks (3 to 1) and, as words, at AA (FA9)", () => {
      const mix = (a: string, b: string, share: number): string => {
        const channels = [1, 3, 5].map((at) => {
          const x = parseInt(a.slice(at, at + 2), 16);
          const y = parseInt(b.slice(at, at + 2), 16);
          return Math.round(x * share + y * (1 - share))
            .toString(16)
            .padStart(2, "0");
        });
        return `#${channels.join("")}`;
      };
      for (const [name, block] of [
        ["light", light],
        ["dark", dark],
      ] as const) {
        const surface = role(block, "--color-surface");
        const text = role(block, "--color-text");
        const seen = new Set<string>();
        for (let n = 1; n <= 6; n += 1) {
          const colour = role(block, `--series-${n}`);
          seen.add(colour);
          expect(contrast(colour, surface), `${name}: series ${n} as a mark`).toBeGreaterThanOrEqual(3);
          expect(contrast(mix(colour, text, 0.7), surface), `${name}: series ${n} as a word`).toBeGreaterThanOrEqual(
            4.5,
          );
        }
        expect(seen.size, `${name}: the six series are six different colours`).toBe(6);
      }
    });

    it("reads at AA (4.5 to 1) for every status pair of the plant board, in both themes (ADR-0266)", () => {
      for (const [name, block] of [
        ["light", light],
        ["dark", dark],
      ] as const) {
        const surface = role(block, "--color-surface");
        const sunken = role(block, "--color-surface-sunken");
        const muted = role(block, "--color-text-muted");
        for (const [what, a, b] of [
          ["success on its surface", role(block, "--color-success"), role(block, "--color-success-surface")],
          ["warning on its surface", role(block, "--color-warning"), role(block, "--color-warning-surface")],
          ["danger on its surface", role(block, "--color-danger"), role(block, "--color-danger-surface")],
          ["success on a panel", role(block, "--color-success"), surface],
          ["warning on a panel", role(block, "--color-warning"), surface],
          ["danger on a panel", role(block, "--color-danger"), surface],
          ["muted words on a panel", muted, surface],
          ["muted words on the sunken surface", muted, sunken],
        ] as const) {
          expect(contrast(a, b), `${name}: ${what}`).toBeGreaterThanOrEqual(4.5);
        }
      }
    });

    it("defines six plant colours for the Management Core board that read as marks (3 to 1) in both themes", () => {
      for (const [name, block] of [
        ["light", light],
        ["dark", dark],
      ] as const) {
        const surface = role(block, "--color-surface");
        const seen = new Set<string>();
        for (let n = 1; n <= 6; n += 1) {
          const colour = role(block, `--plant-${n}`);
          seen.add(colour);
          expect(contrast(colour, surface), `${name}: plant ${n}`).toBeGreaterThanOrEqual(3);
        }
        expect(seen.size, `${name}: six different colours`).toBe(6);
      }
    });

    it("gives the board look white text on its navy band and readable ink on a panel in both themes (FA13C)", () => {
      for (const [name, block] of [
        ["light", light],
        ["dark", dark],
      ] as const) {
        expect(
          contrast(role(block, "--board-band-text"), role(block, "--board-band")),
          `${name}: text on the band`,
        ).toBeGreaterThanOrEqual(4.5);
        expect(
          contrast(role(block, "--board-ink"), role(block, "--color-surface")),
          `${name}: ink on a panel`,
        ).toBeGreaterThanOrEqual(4.5);
      }
    });
  });
});
