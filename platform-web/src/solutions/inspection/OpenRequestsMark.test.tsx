import { readFileSync } from "node:fs";
import path from "node:path";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OpenRequestsMark } from "./OpenRequestsMark";

const css = readFileSync(path.resolve(__dirname, "..", "..", "app", "globals.css"), "utf8");

describe("the asterisk of a template with open change requests (FA6E)", () => {
  it("is a mark with a text alternative that says how many", () => {
    render(<OpenRequestsMark count={2} />);

    expect(screen.getByRole("img", { name: "Open requests" })).toHaveAttribute("title", "2 open change requests");
  });

  it("says one in the singular", () => {
    render(<OpenRequestsMark count={1} />);

    expect(screen.getByTestId("open-requests-mark")).toHaveAttribute("title", "1 open change request");
  });

  it("draws nothing for none, and nothing when the server did not send a number", () => {
    const none = render(<OpenRequestsMark count={0} />);
    expect(none.container).toBeEmptyDOMElement();
    const unknown = render(<OpenRequestsMark count={undefined as unknown as number} />);
    expect(unknown.container).toBeEmptyDOMElement();
  });
});

describe("the asterisk stays inside its tab (FA10)", () => {
  it("is drawn in the flow of the tab, after the word, not positioned outside the tab's box", () => {
    const rule = /\.open-requests-mark\s*\{([^}]*)\}/.exec(css)?.[1] ?? "";

    expect(rule).not.toBe("");
    expect(rule).not.toMatch(/position:\s*absolute/);
    expect(rule).not.toMatch(/inset-inline-end|inset-inline-start|\bright:|\bleft:|calc\(-1/);
    expect(rule).toMatch(/display:\s*inline-block/);
  });

  it("leaves the row of tabs to scroll only when the tabs truly do not fit", () => {
    const row = /\.detail-tabs\s*\{([^}]*)\}/.exec(css)?.[1] ?? "";

    expect(row).toMatch(/overflow-x:\s*auto/);
  });

  it("is a child of the tab it marks", () => {
    render(
      <button type="button" role="tab">
        Change requests
        <OpenRequestsMark count={1} />
      </button>,
    );

    expect(screen.getByRole("tab")).toContainElement(screen.getByTestId("open-requests-mark"));
  });
});
