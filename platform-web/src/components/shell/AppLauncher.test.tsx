import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppLauncher } from "./AppLauncher";

describe("the application launcher button", () => {
  it("is a link to the grid of applications, not a popup", () => {
    render(<AppLauncher />);

    const link = screen.getByRole("link", { name: "Applications" });

    expect(link).toHaveAttribute("href", "/");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(link).not.toHaveAttribute("aria-haspopup");
  });

  it("tells the grid which application is open, so the grid can mark it", () => {
    render(<AppLauncher current="quality" />);

    expect(screen.getByRole("link", { name: "Applications" })).toHaveAttribute("href", "/?from=quality");
  });

  it("encodes a name that needs it", () => {
    render(<AppLauncher current="Field__c & more" />);

    expect(screen.getByRole("link", { name: "Applications" })).toHaveAttribute(
      "href",
      "/?from=Field__c%20%26%20more",
    );
  });
});
