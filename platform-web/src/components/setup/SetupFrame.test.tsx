import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SetupFrame } from "./SetupFrame";

vi.mock("next/navigation", () => ({ usePathname: () => "/setup/profiles" }));

describe("SetupFrame", () => {
  it("lists the setup pages as links and marks the current one", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const nav = screen.getByRole("navigation", { name: "Setup" });
    expect(nav).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Profiles" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Objects" })).not.toHaveAttribute("aria-current");
    expect(screen.getByText("page content")).toBeInTheDocument();
  });

  it("shows the General tab as a marker that goes nowhere, so no branding feature is implied", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const general = screen.getByTestId("setup-general-placeholder");
    expect(general).toHaveTextContent("General");
    expect(general).toHaveTextContent("Soon");
    expect(general).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByRole("link", { name: /General/ })).toBeNull();
  });
});
