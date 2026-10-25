import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OpenRequestsMark } from "./OpenRequestsMark";

describe("the asterisk of a template with open change requests (FA6E)", () => {
  it("is a mark with a text alternative that says how many", () => {
    render(<OpenRequestsMark count={2} />);

    expect(screen.getByRole("img", { name: "Has open change requests" })).toHaveAttribute("title", "2 open change requests");
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
