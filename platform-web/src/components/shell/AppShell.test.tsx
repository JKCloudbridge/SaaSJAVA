import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppShell } from "./AppShell";

describe("AppShell", () => {
  it("has the landmarks of the application frame and renders its content", () => {
    render(
      <AppShell>
        <p>page content</p>
      </AppShell>,
    );

    expect(screen.getByRole("banner")).toHaveTextContent("Platform");
    expect(screen.getByRole("navigation", { name: "Main" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("page content");
  });

  it("offers a way past the navigation for keyboard users", () => {
    render(<AppShell>content</AppShell>);

    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
  });

  it("is honest that no organization or user is established yet", () => {
    render(<AppShell>content</AppShell>);

    expect(screen.getByText("No organization selected")).toBeInTheDocument();
    expect(screen.getByText("Not signed in")).toBeInTheDocument();
  });
});
