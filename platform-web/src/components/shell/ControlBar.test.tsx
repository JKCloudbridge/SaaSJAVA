import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

describe("the control bar", () => {
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

  it("shows no application, and no pages, on a page of the platform itself", async () => {
    pathname = "/members";
    shellApi();
    renderBar();

    expect(await screen.findByText("No application open")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: /pages/ })).toBeNull();
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

  it("offers the way to Setup only to a member whose abilities the API lists for it", async () => {
    shellApi({ abilities: ["metadata.view"] });
    const { unmount } = renderBar();
    expect(await screen.findByRole("link", { name: "Setup" })).toHaveAttribute("href", "/setup/objects");
    unmount();

    shellApi({ abilities: ["members.view"] });
    renderBar();
    await screen.findByTestId("control-bar");
    await waitFor(() => expect(screen.getByTestId("control-app")).toHaveTextContent("Field Service"));
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
  });

  it("is not shown on the platform's address", async () => {
    shellApi({ platformHost: true });
    renderBar();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByTestId("control-bar")).toBeNull();
  });
});
