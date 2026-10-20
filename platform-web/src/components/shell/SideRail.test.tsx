import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { RailEntry } from "@/lib/shell/areas";
import { SideRail } from "./SideRail";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const ENTRIES: RailEntry[] = [
  { kind: "link", href: "/setup/general", label: "General", soon: true },
  { kind: "link", href: "/members", label: "Members" },
  {
    kind: "group",
    key: "access",
    label: "Access",
    items: [
      { kind: "link", href: "/setup/profiles", label: "Profiles" },
      { kind: "link", href: "/setup/roles", label: "Roles" },
    ],
  },
  {
    kind: "group",
    key: "automation",
    label: "Automation",
    items: [
      { kind: "link", href: "/setup/workflows", label: "Workflows" },
      { kind: "link", href: "/workflows/executions", label: "Workflow runs" },
    ],
  },
  { kind: "link", href: "/audit", label: "Audit trail" },
];

function renderRail(pathname: string, entries: RailEntry[] = ENTRIES) {
  const view = render(<SideRail label="Home" entries={entries} pathname={pathname} />);
  return {
    ...view,
    again: (next: string) => view.rerender(<SideRail label="Home" entries={entries} pathname={next} />),
  };
}

function header(name: string): HTMLElement {
  return screen.getByRole("button", { name });
}

describe("the side rail", () => {
  it("is a navigation named after the rail, with plain links for the entries that belong to no group", () => {
    renderRail("/members");

    const nav = screen.getByRole("navigation", { name: "Home" });
    expect(within(nav).getByRole("link", { name: "Members" })).toHaveAttribute("href", "/members");
    expect(within(nav).getByRole("link", { name: "Audit trail" })).toHaveAttribute("href", "/audit");
  });

  it("marks the current page with aria-current and no other", () => {
    renderRail("/members");

    expect(screen.getByRole("link", { name: "Members" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Audit trail" })).not.toHaveAttribute("aria-current");
  });

  it("shows the General marker with its badge, as a page that is not built yet", () => {
    renderRail("/members");

    expect(screen.getByRole("link", { name: /General/ })).toHaveTextContent("Soon");
  });

  it("draws a group as a button with its sub-items as links, one level deep", () => {
    renderRail("/members");

    const access = header("Access");
    const sub = document.getElementById(access.getAttribute("aria-controls") ?? "");
    expect(sub).not.toBeNull();
    expect(sub!.querySelectorAll("button")).toHaveLength(0);
    expect(Array.from(sub!.querySelectorAll("a")).map((link) => link.textContent)).toEqual(["Profiles", "Roles"]);
    expect(screen.queryByRole("link", { name: "Access" })).toBeNull();
  });

  it("keeps every group closed on a page that is in none, and says so with aria-expanded", () => {
    renderRail("/members");

    expect(header("Access")).toHaveAttribute("aria-expanded", "false");
    expect(header("Automation")).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: "Profiles" })).toBeNull();
  });

  it("opens the group that holds the current page when the person arrives on it", () => {
    renderRail("/setup/roles");

    expect(header("Access")).toHaveAttribute("aria-expanded", "true");
    expect(header("Automation")).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("link", { name: "Roles" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Profiles" })).not.toHaveAttribute("aria-current");
  });

  it("opens a group when its header is pressed and closes it when it is pressed again", () => {
    renderRail("/members");

    fireEvent.click(header("Access"));
    expect(header("Access")).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "Profiles" })).toBeInTheDocument();

    fireEvent.click(header("Access"));
    expect(header("Access")).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: "Profiles" })).toBeNull();
  });

  it("is an accordion: opening one group closes the others, even the one that holds the current page", () => {
    renderRail("/setup/roles");
    expect(header("Access")).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(header("Automation"));

    expect(header("Automation")).toHaveAttribute("aria-expanded", "true");
    expect(header("Access")).toHaveAttribute("aria-expanded", "false");
    const expanded = screen.getAllByRole("button", { expanded: true });
    const open = expanded.filter((button) => button.className.includes("group"));
    expect(open).toHaveLength(1);
  });

  it("opens the group of the page the person moves to, and closes the one it was", () => {
    const { again } = renderRail("/setup/roles");

    again("/setup/workflows");

    expect(header("Automation")).toHaveAttribute("aria-expanded", "true");
    expect(header("Access")).toHaveAttribute("aria-expanded", "false");
  });

  it("leaves the groups as they are when the person moves to a page that stands alone", () => {
    const { again } = renderRail("/setup/roles");

    again("/audit");

    expect(header("Access")).toHaveAttribute("aria-expanded", "true");
  });

  it("makes a group header reachable and usable from the keyboard, and ties it to the list it opens", () => {
    renderRail("/members");
    const access = header("Access");

    access.focus();

    expect(access).toHaveFocus();
    expect(access.tagName).toBe("BUTTON");
    expect(access).toHaveAttribute("type", "button");
    expect(access).toHaveAttribute("aria-controls");
    // Enter and Space on a button are a click for the browser; the click is what opens the group.
    fireEvent.click(access);
    expect(access).toHaveAttribute("aria-expanded", "true");
  });

  it("keeps the links of a closed group out of reach: inert and hidden from assistive technology", () => {
    renderRail("/members");

    const sub = document.getElementById(header("Access").getAttribute("aria-controls") ?? "")!;

    expect(sub).toHaveAttribute("aria-hidden", "true");
    expect(sub).toHaveAttribute("inert");
    fireEvent.click(header("Access"));
    expect(sub).toHaveAttribute("aria-hidden", "false");
    expect(sub).not.toHaveAttribute("inert");
  });

  it("draws exactly the entries it is given: a rail with no group has no group button", () => {
    renderRail("/members", [ENTRIES[1]!, ENTRIES[4]!]);

    expect(screen.queryByRole("button", { name: "Access" })).toBeNull();
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  it("becomes a menu on a narrow screen: a button that opens the list and says where the person is", () => {
    renderRail("/members");

    const toggle = screen.getByRole("button", { name: /Home · Members/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const list = document.getElementById(toggle.getAttribute("aria-controls") ?? "")!;
    expect(list).toHaveAttribute("data-open", "false");

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(list).toHaveAttribute("data-open", "true");
  });

  it("closes the narrow-screen menu when the person has chosen a page", () => {
    const { again } = renderRail("/members");
    fireEvent.click(screen.getByRole("button", { name: /Home · Members/ }));

    again("/audit");

    expect(screen.getByRole("button", { name: /Home · Audit trail/ })).toHaveAttribute("aria-expanded", "false");
  });
});
