import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { OrganizationsPanel } from "./OrganizationsPanel";

const FIRST = {
  id: "11111111-1111-4111-8111-111111111111",
  slug: "tenant-a",
  displayName: "Tenant A",
  status: "ACTIVE",
  plan: "Trial",
  subscriptionStatus: "TRIAL",
  trialEndsAt: "2026-10-30T10:00:00Z",
  trialExpired: false,
};
const SECOND = {
  id: "22222222-2222-4222-8222-222222222222",
  slug: "tenant-b",
  displayName: "Tenant B",
  status: "PROVISIONING",
  trialExpired: false,
};

describe("OrganizationsPanel", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("lists what the API reports, with links to each organization and nothing about its members", async () => {
    fakeApi({
      "GET /api/v1/platform/organizations": () =>
        json({ data: [FIRST, SECOND], pagination: { limit: 50, hasMore: false } }),
    });

    render(<OrganizationsPanel />);

    const rows = await screen.findAllByTestId("organization-row");
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByRole("link", { name: "Tenant A" })).toHaveAttribute(
      "href",
      `/console/organizations/${FIRST.id}`,
    );
    expect(rows[0]).toHaveTextContent("Trial (TRIAL)");
    expect(rows[1]).toHaveTextContent("PROVISIONING");
    expect(rows[1]).toHaveTextContent("none");
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("searches, and shows more pages with the cursor the API gave", async () => {
    const calls = fakeApi({
      "GET /api/v1/platform/organizations": (request) => {
        const url = new URL(request.url);
        if (url.searchParams.get("cursor") === "next-page") {
          return json({ data: [SECOND], pagination: { limit: 50, hasMore: false } });
        }
        return json({ data: [FIRST], pagination: { limit: 50, hasMore: true, nextCursor: "next-page" } });
      },
    });
    render(<OrganizationsPanel />);
    await screen.findAllByTestId("organization-row");

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    await waitFor(() => expect(screen.getAllByTestId("organization-row")).toHaveLength(2));
    fireEvent.change(screen.getByLabelText("Search by name or short name"), { target: { value: "tenant-b" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() =>
      expect(calls.some((call) => call.key === "GET /api/v1/platform/organizations")).toBe(true),
    );
  });

  it("shows the API's words when the caller may not use the console", async () => {
    fakeApi({
      "GET /api/v1/platform/organizations": () =>
        refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });

    render(<OrganizationsPanel />);

    expect(await screen.findByTestId("organizations-failed")).toHaveTextContent("not allowed");
    expect(screen.queryByTestId("organization-row")).toBeNull();
  });

  it("says so when there are none", async () => {
    fakeApi({
      "GET /api/v1/platform/organizations": () => json({ data: [], pagination: { limit: 50, hasMore: false } }),
    });

    render(<OrganizationsPanel />);

    expect(await screen.findByText("No organizations found.")).toBeInTheDocument();
  });
});
