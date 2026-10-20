import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApplicationsPanel } from "./ApplicationsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/metadata/applications";
const REMOVE = "DELETE /api/v1/metadata/applications/Sales__c";
const PROFILE = "0198a1b2-0000-7000-8000-000000000001";
const GONE = "0198a1b2-0000-7000-8000-000000000002";

const SALES = {
  apiName: "Sales__c", label: "Sales", description: "The sales menu", version: 2,
  definition: {
    iconKey: "briefcase",
    profiles: [PROFILE, GONE],
    items: [{ type: "HEADING", label: "Work" }, { type: "OBJECT", object: "Deal__c" }],
  },
};

function answers(applications: unknown[]) {
  return {
    [LIST]: () => json({ data: applications }),
    "GET /api/v1/profiles": () => json({ data: [{ id: PROFILE, name: "Sales" }] }),
  };
}

describe("ApplicationsPanel", () => {
  it("lists the applications with who sees them, under React strict mode", async () => {
    fakeApi(answers([SALES, { ...SALES, apiName: "Open__c", label: "Open", definition: { items: [] } }]));

    render(
      <StrictMode>
        <ApplicationsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("application-row");
    expect(rows[0]).toHaveTextContent("Sales");
    expect(rows[0]).toHaveTextContent("Sales, A profile that was deleted");
    expect(rows[0]).toHaveTextContent("2");
    expect(rows[1]).toHaveTextContent("Every member");
  });

  it("opens the builder for a new application and for an existing one", async () => {
    fakeApi(answers([SALES]));
    render(<ApplicationsPanel />);
    const row = await screen.findByTestId("application-row");

    expect(screen.getByRole("link", { name: "New application" })).toHaveAttribute("href", "/setup/applications/new");
    expect(within(row).getByRole("link", { name: "Sales" })).toHaveAttribute("href", "/setup/applications/Sales__c");
    // A click anywhere on the row opens the builder: the first cell is the real link.
    expect(within(row).getAllByRole("link")).toHaveLength(1);
  });

  it("shows who sees an application as a tag and filters the loaded rows with the search box", async () => {
    fakeApi(answers([SALES, { ...SALES, apiName: "Open__c", label: "Open", definition: { items: [] } }]));
    render(<ApplicationsPanel />);
    const rows = await screen.findAllByTestId("application-row");

    expect(within(rows[1] as HTMLElement).getByText("Every member")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("searchbox", { name: "Search applications" }), { target: { value: "open" } });
    expect(screen.getAllByTestId("application-row")).toHaveLength(1);
    expect(screen.getByText("Showing 1 of 2.")).toBeInTheDocument();
  });

  it("says so when there are none, and shows the API's words when the list cannot be read", async () => {
    fakeApi(answers([]));
    const { unmount } = render(<ApplicationsPanel />);
    expect(await screen.findByText("No applications yet.")).toBeInTheDocument();
    unmount();

    fakeApi({ [LIST]: () => refusal("FORBIDDEN", "You may not view applications.", 403) });
    render(<ApplicationsPanel />);
    expect(await screen.findByTestId("applications-failed")).toHaveTextContent("You may not view applications.");
  });

  it("removes an application live", async () => {
    const calls = fakeApi({ ...answers([SALES]), [REMOVE]: () => new Response(null, { status: 204 }) });
    render(<ApplicationsPanel />);
    const row = await screen.findByTestId("application-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("applications-notice")).toHaveTextContent("The application was removed.");
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("puts the removal into the chosen change set without an object name", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers([SALES]),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<ApplicationsPanel />);
    const row = await screen.findByTestId("application-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("applications-notice")).toHaveTextContent("goes live when the set is published");
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_APPLICATION", itemApiName: "Sales__c" });
  });
});
