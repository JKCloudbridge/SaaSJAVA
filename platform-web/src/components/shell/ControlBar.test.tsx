import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json } from "@/test/fakeApi";
import { FIELD, SALES, shellApi, ShellProviders } from "@/test/shellApi";
import { ControlBar } from "./ControlBar";

let pathname = "/apps/Field__c/c-Inspections__c";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

beforeEach(() => {
  pathname = "/apps/Field__c/c-Inspections__c";
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderBar() {
  return render(
    <ShellProviders>
      <ControlBar />
    </ShellProviders>,
  );
}

/**
 * The strip, once the session and the list of applications have arrived: the list is asked for only after the
 * sign-in is known, so a test that asserts something is absent does not pass because the answer is still on its way.
 */
async function strip() {
  const bar = await screen.findByTestId("control-bar");
  await waitFor(() => {
    const paths = vi.mocked(globalThis.fetch).mock.calls.map(([request]) => new URL((request as Request).url).pathname);
    expect(paths).toContain("/api/v1/applications");
  });
  await new Promise((resolve) => setTimeout(resolve, 20));
  return bar;
}

describe("the strip in an application", () => {
  it("shows the open application and its pages in the order the API lists them", async () => {
    shellApi();
    renderBar();

    const pages = await screen.findByRole("navigation", { name: "Field Service pages" });

    expect(within(screen.getByTestId("control-app")).getByText("Field Service")).toBeInTheDocument();
    expect(within(pages).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Work",
      "Inspections",
      "Deals",
      "Members",
    ]);
  });

  it("marks the page that is open and leads each page to its own address", async () => {
    shellApi();
    renderBar();
    const pages = await screen.findByRole("navigation", { name: "Field Service pages" });

    const inspections = within(pages).getByRole("link", { name: "Inspections" });

    expect(inspections).toHaveAttribute("aria-current", "page");
    expect(inspections).toHaveAttribute("href", "/apps/Field__c/c-Inspections__c");
    expect(within(pages).getByRole("link", { name: "Deals" })).not.toHaveAttribute("aria-current");
    expect(within(pages).getByRole("link", { name: "Deals" })).toHaveAttribute("href", "/apps/Field__c/o-Deal__c");
  });

  it("draws a heading as a label, not a link", async () => {
    shellApi();
    renderBar();
    const pages = await screen.findByRole("navigation", { name: "Field Service pages" });

    expect(within(pages).getByText("Work")).toBeInTheDocument();
    expect(within(pages).queryByRole("link", { name: "Work" })).toBeNull();
  });

  it("leads a link to the path it holds and never marks it as the page that is open", async () => {
    shellApi();
    renderBar();
    const pages = await screen.findByRole("navigation", { name: "Field Service pages" });

    const members = within(pages).getByRole("link", { name: "Members" });

    expect(members).toHaveAttribute("href", "/members");
    expect(members).not.toHaveAttribute("aria-current");
  });

  it("shows an entry that cannot be opened dimmed and not clickable, with its reason", async () => {
    shellApi({
      applications: [
        {
          ...FIELD,
          items: [{ ...FIELD.items[1]!, available: false }, FIELD.items[2]!],
        },
      ],
    });
    renderBar();
    const pages = await screen.findByRole("navigation", { name: "Field Service pages" });

    const dimmed = within(pages).getByText("Inspections");

    expect(dimmed).toHaveAttribute("aria-disabled", "true");
    expect(dimmed).toHaveAttribute("title", "Not available right now");
    expect(dimmed.tagName).not.toBe("A");
    expect(within(pages).getByRole("link", { name: "Deals" })).toBeInTheDocument();
  });

  it("names an application the member does not have as unavailable, without pages", async () => {
    pathname = "/apps/Gone__c/c-Whatever__c";
    shellApi({ applications: [SALES] });
    renderBar();

    expect(await screen.findByText("Application")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: /pages/ })).toBeNull();
  });

  it("has the gear back to settings only for a member with an ability the setup pages are for", async () => {
    shellApi({ abilities: ["metadata.view"] });
    const { unmount } = renderBar();
    expect(await screen.findByRole("link", { name: "Setup" })).toHaveAttribute("href", "/setup/applications");
    unmount();

    // An ability no setup page is for (a member may share list views) gives no gear.
    shellApi({ abilities: ["views.share"] });
    renderBar();
    await strip();
    await waitFor(() => expect(screen.getByTestId("control-app")).toHaveTextContent("Field Service"));
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
  });

  it("leads the gear to the first page of settings the member may open", async () => {
    shellApi({ abilities: ["members.view", "access.manage", "metadata.view"] });
    renderBar();

    expect(await screen.findByRole("link", { name: "Setup" })).toHaveAttribute("href", "/members");
  });

  it("never shows the settings tabs or the gear to a member with no setup ability", async () => {
    shellApi({ abilities: [] });
    renderBar();
    await strip();
    await waitFor(() => expect(screen.getByTestId("control-app")).toHaveTextContent("Field Service"));

    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
    expect(screen.queryByRole("navigation", { name: "Settings" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Home" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Object settings" })).toBeNull();
  });
});

describe("the strip in settings", () => {
  it("shows the two tabs Home and Object settings, and no gear: the person is already in settings", async () => {
    pathname = "/members";
    shellApi({ abilities: ["members.view", "metadata.view"] });
    renderBar();

    const tabs = await screen.findByRole("navigation", { name: "Settings" });

    expect(within(tabs).getAllByRole("link").map((link) => link.textContent)).toEqual(["Home", "Object settings"]);
    expect(within(tabs).getByRole("link", { name: "Home" })).toHaveAttribute("href", "/members");
    expect(within(tabs).getByRole("link", { name: "Home" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "Object settings" })).toHaveAttribute("href", "/setup/objects");
    expect(within(tabs).getByRole("link", { name: "Object settings" })).not.toHaveAttribute("aria-current");
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
    expect(screen.getByRole("button", { name: "Applications" })).toBeInTheDocument();
  });

  it("marks Object settings on the object pages, builders included", async () => {
    pathname = "/setup/objects/Account/layouts/Main";
    shellApi({ abilities: ["metadata.view"] });
    renderBar();

    const tabs = await screen.findByRole("navigation", { name: "Settings" });

    expect(within(tabs).getByRole("link", { name: "Object settings" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });

  it("draws only the tab the person has an ability for", async () => {
    pathname = "/audit";
    shellApi({ abilities: ["audit.view"] });
    renderBar();

    const tabs = await screen.findByRole("navigation", { name: "Settings" });

    expect(within(tabs).getAllByRole("link").map((link) => link.textContent)).toEqual(["Home"]);
    expect(within(tabs).getByRole("link", { name: "Home" })).toHaveAttribute("href", "/audit");
  });
});

describe("the strip on the pages that are neither", () => {
  it("names the area and offers the gear to a person who may set things up", async () => {
    pathname = "/approvals";
    shellApi({ abilities: ["metadata.view"] });
    renderBar();

    expect(await screen.findByRole("link", { name: "Setup" })).toHaveAttribute("href", "/setup/applications");
    expect(screen.getByTestId("control-app")).toHaveTextContent("Approvals");
    expect(screen.queryByRole("navigation", { name: /pages|Settings/ })).toBeNull();
  });

  it("names the area and has no gear for a member with no setup ability", async () => {
    pathname = "/notifications";
    shellApi({ abilities: [] });
    renderBar();
    await strip();

    expect(screen.getByTestId("control-app")).toHaveTextContent("Notifications");
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
    expect(screen.getByRole("button", { name: "Applications" })).toBeInTheDocument();
  });

  it("names a record screen outside an application, and the switching of organization", async () => {
    pathname = "/records/Deal__c";
    shellApi();
    const { unmount } = renderBar();
    await strip();
    expect(screen.getByTestId("control-app")).toHaveTextContent("Records");
    unmount();

    pathname = "/switch";
    shellApi();
    renderBar();
    await strip();
    expect(screen.getByTestId("control-app")).toHaveTextContent("Switch organization");
  });

  it("says no application is open on any other page", async () => {
    pathname = "/";
    shellApi();
    renderBar();

    expect(await screen.findByText("No application open")).toBeInTheDocument();
  });
});

describe("the strip elsewhere", () => {
  it("is not shown on an application's page at the platform's address", async () => {
    shellApi({ platformHost: true });
    renderBar();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByTestId("control-bar")).toBeNull();
  });

  it("names the console on the platform's console, with no launcher and no settings tabs", async () => {
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
    renderBar();

    expect(await screen.findByTestId("control-app")).toHaveTextContent("Platform console");
    expect(screen.queryByRole("button", { name: "Applications" })).toBeNull();
    expect(screen.queryByRole("navigation", { name: "Settings" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
  });

  it("is not shown on the console to a person without a platform role", async () => {
    pathname = "/console";
    shellApi({ platformHost: true });
    renderBar();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByTestId("control-bar")).toBeNull();
  });
});
