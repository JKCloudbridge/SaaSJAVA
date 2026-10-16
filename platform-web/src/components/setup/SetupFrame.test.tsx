import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SetupFrame } from "./SetupFrame";

vi.mock("next/navigation", () => ({ usePathname: () => "/setup/profiles", useSearchParams: () => new URLSearchParams() }));

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

  it("lists the Tabs page between Applications and Change sets", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const labels = screen.getAllByRole("link").map((link) => link.textContent);
    expect(labels.indexOf("Tabs")).toBe(labels.indexOf("Applications") + 1);
    expect(screen.getByRole("link", { name: "Tabs" })).toHaveAttribute("href", "/setup/tabs");
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
