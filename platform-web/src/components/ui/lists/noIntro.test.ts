import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..", "..");

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) {
      return name === "generated" ? [] : sources(full);
    }
    return /\.(tsx?|css)$/.test(name) && !/\.test\./.test(name) ? [full] : [];
  });
}

/**
 * A screen does not explain itself under its title (UI3, UI3A): the `intro` property of the title band and of the list and
 * detail pages is gone, with the sentences that went into it and the style that drew them. This keeps it from coming back.
 */
describe("the title band", () => {
  const files = sources(src).map((file) => ({ file, text: readFileSync(file, "utf8") }));

  it("reads the sources (the scan is not looking at nothing)", () => {
    expect(files.length).toBeGreaterThan(200);
  });

  it("is given no intro by any screen", () => {
    const found = files.filter(({ text }) => /\bintro=\{|\bintro="/.test(text)).map(({ file }) => path.relative(src, file));
    expect(found).toEqual([]);
  });

  it("has no intro property and no style for one", () => {
    for (const name of ["PageFrame.tsx", "ListPage.tsx", "DetailPage.tsx"]) {
      expect(readFileSync(path.join(src, "components/ui/lists", name), "utf8")).not.toMatch(/\bintro\??:/);
    }
    expect(readFileSync(path.join(src, "app/globals.css"), "utf8")).not.toContain(".page-intro");
  });
});
