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

  it("lists the workflows and their runs after Tabs, and shows the change-set bar on the workflow pages", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const labels = screen.getAllByRole("link").map((link) => link.textContent);
    expect(labels.indexOf("Workflows")).toBe(labels.indexOf("Tabs") + 1);
    expect(labels.indexOf("Workflow runs")).toBe(labels.indexOf("Workflows") + 1);
    expect(screen.getByRole("link", { name: "Workflows" })).toHaveAttribute("href", "/setup/workflows");
    expect(screen.getByRole("link", { name: "Workflow runs" })).toHaveAttribute("href", "/workflows/executions");
  });

  it("lists the approval processes after the runs of workflows", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const labels = screen.getAllByRole("link").map((link) => link.textContent);
    expect(labels.indexOf("Approval processes")).toBe(labels.indexOf("Workflow runs") + 1);
    expect(screen.getByRole("link", { name: "Approval processes" })).toHaveAttribute("href", "/setup/approvals");
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

describe("SetupFrame (Sprint 25)", () => {
  it("lists the notification text and the health of automation after the approval processes", () => {
    render(
      <SetupFrame>
        <p>page content</p>
      </SetupFrame>,
    );

    const labels = screen.getAllByRole("link").map((link) => link.textContent);
    expect(labels.indexOf("Notification text")).toBe(labels.indexOf("Approval processes") + 1);
    expect(labels.indexOf("Automation health")).toBe(labels.indexOf("Notification text") + 1);
    expect(screen.getByRole("link", { name: "Notification text" })).toHaveAttribute("href", "/setup/notification-text");
    expect(screen.getByRole("link", { name: "Automation health" })).toHaveAttribute("href", "/setup/automation-health");
  });
});
