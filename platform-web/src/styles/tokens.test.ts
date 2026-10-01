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

  it("redefine the colour roles for dark mode instead of leaving them light", () => {
    const dark = css.slice(css.indexOf("prefers-color-scheme: dark"));

    for (const role of ["--color-background", "--color-surface", "--color-text", "--color-accent", "--color-danger"]) {
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
});
