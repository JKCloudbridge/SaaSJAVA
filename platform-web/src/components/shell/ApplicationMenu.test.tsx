import { render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApplicationMenu } from "./ApplicationMenu";

afterEach(() => {
  vi.unstubAllGlobals();
});

const SALES = {
  apiName: "Sales__c",
  label: "Sales",
  iconKey: "briefcase",
  items: [
    { type: "HEADING", label: "Work" },
    { type: "OBJECT", label: "Deals", objectApiName: "Deal__c" },
    { type: "RECORD_PAGE", label: "Overview", objectApiName: "Deal__c", pageApiName: "Overview__c" },
    { type: "LINK", label: "Members", path: "/members" },
  ],
};

describe("ApplicationMenu", () => {
  it("draws the applications and the navigation exactly as the API lists them, under React strict mode", async () => {
    fakeApi({ "GET /api/v1/applications": () => json({ data: [SALES] }) });

    render(
      <StrictMode>
        <ul>
          <ApplicationMenu />
        </ul>
      </StrictMode>,
    );

    const menu = await screen.findByTestId("application-menu");
    expect(menu).toHaveAttribute("data-icon", "briefcase");
    const list = within(menu).getByRole("list", { name: "Sales" });
    expect(within(list).getByText("Work")).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: "Members" })).toHaveAttribute("href", "/members");
  });

  it("shows an object and a record page as a label, not as a link, until there is a page to open", async () => {
    fakeApi({ "GET /api/v1/applications": () => json({ data: [SALES] }) });

    render(
      <ul>
        <ApplicationMenu />
      </ul>,
    );

    const menu = await screen.findByTestId("application-menu");
    expect(within(menu).getByText("Deals")).toBeInTheDocument();
    expect(within(menu).getByText("Overview")).toBeInTheDocument();
    expect(within(menu).getAllByRole("link")).toHaveLength(1);
  });

  it("draws nothing when the API refuses or has no applications", async () => {
    fakeApi({ "GET /api/v1/applications": () => refusal("NOT_FOUND", "This is not available at this address.", 404) });

    render(
      <ul>
        <ApplicationMenu />
      </ul>,
    );

    await vi.waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    expect(screen.queryByTestId("application-menu")).toBeNull();
  });
});
