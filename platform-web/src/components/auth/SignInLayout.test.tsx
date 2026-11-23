import { readFileSync } from "node:fs";
import path from "node:path";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SignInLayout } from "./SignInLayout";

describe("the sign-in layout (FA6D)", () => {
  it("draws the form in one half and the product's mark in the other", () => {
    render(
      <SignInLayout title="Sign in">
        <form aria-label="the form">
          <button type="submit">Go</button>
        </form>
      </SignInLayout>,
    );

    const form = screen.getByTestId("sign-in-form-half");
    const brand = screen.getByTestId("sign-in-brand-half");
    expect(within(form).getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(within(form).getByRole("form", { name: "the form" })).toBeInTheDocument();
    expect(within(brand).getByRole("img", { name: "V Way Sync logo" })).toHaveAttribute("src", expect.stringContaining("way-logo.svg"));
    expect(form.compareDocumentPosition(brand) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("gives the mark a text alternative and keeps the file in the project's public folder", () => {
    render(<SignInLayout title="Welcome">content</SignInLayout>);

    expect(screen.getByRole("img").getAttribute("alt")).not.toBe("");
    const file = readFileSync(path.resolve(__dirname, "..", "..", "..", "public", "way-logo.svg"));
    expect(file.subarray(0, 4).toString()).toBe("<svg");
  });

  it("colours the panel with a token and splits to one column on a narrow screen, without a literal colour", () => {
    const css = readFileSync(path.resolve(__dirname, "..", "..", "app", "globals.css"), "utf8");
    const panel = /\.sign-in-brand-half \{[^}]*\}/.exec(css)?.[0] ?? "";

    expect(panel).toContain("background: var(--color-brand-panel)");
    expect(panel).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(css).toMatch(/@media \(max-width: 48rem\) \{\s*\.sign-in-split \{\s*grid-template-columns: minmax\(0, 1fr\)/);
  });
});
