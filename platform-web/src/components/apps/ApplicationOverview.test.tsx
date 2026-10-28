import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { MyApplication } from "@/lib/applications/entries";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { ApplicationOverview } from "./ApplicationOverview";

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

const PREPARING: MyApplication = {
  apiName: "stores",
  label: "Stores",
  iconKey: "store",
  group: "Supply chain",
  items: [
    { type: "PLATFORM", label: "Home", pageKey: "home", available: true },
    { type: "PLATFORM", label: "FIFO", pageKey: "fifo", available: true, placeholder: true },
    { type: "PLATFORM", label: "Shelf Life", pageKey: "shelf-life", available: true, placeholder: true },
  ],
};

const MIXED: MyApplication = {
  apiName: "quality",
  label: "Quality",
  items: [
    { type: "PLATFORM", label: "Home", pageKey: "home", available: true },
    { type: "HEADING", label: "Documents", available: true },
    { type: "PLATFORM", label: "Document Control", pageKey: "templates", available: true },
    { type: "PLATFORM", label: "Internal Audit", pageKey: "internal-audit", available: true, placeholder: true },
  ],
};

function renderOverview(application: MyApplication) {
  shellApi({ applications: [application] });
  return render(
    <ShellProviders>
      <ApplicationOverview applicationApiName={application.apiName} />
    </ShellProviders>,
  );
}

describe("the generic Home of an application", () => {
  it("names the application, says it is being prepared, and has a card for each tab but Home", async () => {
    renderOverview(PREPARING);

    const overview = await screen.findByTestId("application-overview");

    expect(within(overview).getByRole("heading", { level: 1, name: "Stores" })).toBeInTheDocument();
    expect(overview).toHaveTextContent("This application is being prepared");
    const cards = within(overview).getAllByRole("link");
    expect(cards.map((card) => card.getAttribute("href"))).toEqual(["/apps/stores/s-fifo", "/apps/stores/s-shelf-life"]);
    expect(within(cards[0]!).getByText("Coming soon")).toBeInTheDocument();
  });

  it("says nothing is being prepared when a tab is real, and tags only the announced ones", async () => {
    renderOverview(MIXED);

    const overview = await screen.findByTestId("application-overview");

    expect(overview).not.toHaveTextContent("This application is being prepared");
    const control = within(overview).getByRole("link", { name: /Document Control/ });
    const audit = within(overview).getByRole("link", { name: /Internal Audit/ });
    expect(within(control).queryByText("Coming soon")).toBeNull();
    expect(within(audit).getByText("Coming soon")).toBeInTheDocument();
    expect(within(overview).queryByText("Documents")).toBeNull();
  });

  it("waits while the list is on its way instead of showing a wrong page", () => {
    shellApi({ applications: [PREPARING] });
    render(
      <ShellProviders>
        <ApplicationOverview applicationApiName="stores" />
      </ShellProviders>,
    );

    expect(screen.queryByTestId("application-overview")).toBeNull();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});
