import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { ShellRail } from "./ShellRail";

let pathname = "/members";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  pathname = "/members";
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderRail() {
  return render(
    <ShellProviders>
      <ShellRail />
    </ShellProviders>,
  );
}

const ALL = [
  "members.view",
  "access.manage",
  "metadata.view",
  "workflows.view",
  "approvals.view",
  "automation.view",
  "audit.view",
];

describe("the rail beside the content", () => {
  it("offers the Home rail on a setup page, with the entries the abilities allow", async () => {
    pathname = "/setup/profiles";
    shellApi({ abilities: ALL });
    renderRail();

    const rail = await screen.findByRole("navigation", { name: "Home" });

    expect(within(rail).getByRole("link", { name: "Members" })).toHaveAttribute("href", "/members");
    expect(within(rail).getByRole("link", { name: "Profiles" })).toHaveAttribute("aria-current", "page");
    expect(within(rail).getByRole("button", { name: "Access" })).toHaveAttribute("aria-expanded", "true");
    expect(within(rail).getByRole("link", { name: "Audit trail" })).toHaveAttribute("href", "/audit");
  });

  it("is also on the pages the setup frame never held: members, audit trail and the workflow runs", async () => {
    for (const address of ["/members", "/audit", "/workflows/executions", "/workflows/executions/run-1"]) {
      pathname = address;
      shellApi({ abilities: ALL });
      const { unmount } = renderRail();
      expect(await screen.findByRole("navigation", { name: "Home" }), address).toBeInTheDocument();
      unmount();
    }
  });

  it("leaves out what the person may not open, and a group with nothing left", async () => {
    shellApi({ abilities: ["members.view", "audit.view"] });
    renderRail();

    const rail = await screen.findByRole("navigation", { name: "Home" });

    expect(within(rail).getByRole("link", { name: "Members" })).toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "Audit trail" })).toBeInTheDocument();
    for (const group of ["Access", "Applications", "Automation", "Releases"]) {
      expect(within(rail).queryByRole("button", { name: group }), group).toBeNull();
    }
  });

  it("is not drawn for a person with no ability the pages are for, even on a settings address", async () => {
    shellApi({ abilities: [] });
    renderRail();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByRole("navigation", { name: "Home" })).toBeNull();
  });

  it("is not drawn on pages that belong to no rail: the approvals inbox, object pages, an application", async () => {
    for (const address of ["/approvals", "/notifications", "/setup/objects", "/apps/Field__c/o-Deal__c", "/"]) {
      pathname = address;
      shellApi({ abilities: ALL });
      const { unmount } = renderRail();
      await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(screen.queryByRole("navigation"), address).toBeNull();
      unmount();
    }
  });

  it("offers the console rail on the platform's address to a person with a platform role", async () => {
    pathname = "/console/plans";
    shellApi({
      platformHost: true,
      more: {
        "GET /api/v1/auth/me": () =>
          json({
            data: {
              id: "p-1",
              email: "platform-a@example.test",
              displayName: "Platform A",
              platformRoles: ["PLATFORM_ADMIN"],
              abilities: [],
            },
          }),
      },
    });
    renderRail();

    const rail = await screen.findByRole("navigation", { name: "Console" });

    expect(within(rail).getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Organizations",
      "Set up an organization",
      "Plans",
      "Platform people",
      "Sessions",
      "Automation health",
      "Audit trail",
    ]);
    expect(within(rail).getByRole("link", { name: "Plans" })).toHaveAttribute("aria-current", "page");
  });

  it("offers no console rail to a person without a platform role", async () => {
    pathname = "/console";
    shellApi({ platformHost: true });
    renderRail();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByRole("navigation", { name: "Console" })).toBeNull();
  });
});
