import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { MembersPanel } from "./MembersPanel";
import { SupportAccessPanel } from "./SupportAccessPanel";

const MEMBERS = {
  data: [
    {
      id: "11111111-1111-4111-8111-111111111111",
      email: "admin-a@example.test",
      displayName: "Admin A",
      status: "ACTIVE",
      administrator: true,
      foundingAdministrator: true,
      since: "2026-10-01T10:00:00Z",
      you: true,
    },
    {
      id: "22222222-2222-4222-8222-222222222222",
      email: "user-a@example.test",
      displayName: "User A",
      status: "ACTIVE",
      administrator: false,
      foundingAdministrator: false,
      since: "2026-10-01T11:00:00Z",
      you: false,
      licence: "user",
    },
    {
      id: "33333333-3333-4333-8333-333333333333",
      email: "user-b@example.test",
      displayName: "User B",
      status: "DEACTIVATED",
      administrator: false,
      foundingAdministrator: false,
      since: "2026-10-01T12:00:00Z",
      you: false,
    },
  ],
};

const POOLS = {
  data: [
    { licenceType: "user", name: "User", quantity: 5, assigned: 3, available: 2 },
    { licenceType: "admin", name: "Administrator", quantity: 1, assigned: 0, available: 1 },
  ],
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

describe("MembersPanel licences", () => {
  function api(extra: Record<string, () => Response> = {}) {
    return fakeApi({
      "GET /api/v1/members": () => json(MEMBERS),
      "GET /api/v1/invitations": () => json({ data: [] }),
      "GET /api/v1/licences": () => json(POOLS),
      ...extra,
    });
  }

  it("shows the numbers per licence type and the licence each member holds", async () => {
    api();
    render(<MembersPanel />);

    const pools = await screen.findByTestId("licence-pools");
    expect(pools).toHaveTextContent("Administrator");
    expect(within(pools).getAllByRole("row")).toHaveLength(3);
    const rows = await screen.findAllByTestId("member-row");
    expect(within(rows[1] as HTMLElement).getByTestId("member-licence")).toHaveTextContent("user");
    expect(within(rows[0] as HTMLElement).getByTestId("member-licence")).toHaveTextContent("none");
    expect(within(rows[2] as HTMLElement).queryByRole("button", { name: /Give/ })).toBeNull();
    expect(screen.getByText(/it grants no permission/)).toBeInTheDocument();
  });

  it("gives and takes back a licence and shows the API's words when none is free", async () => {
    const calls = api({
      "PUT /api/v1/members/11111111-1111-4111-8111-111111111111/licence": () =>
        refusal("CONFLICT", "No licence of this type is free.", 409),
    });
    render(<MembersPanel />);
    const rows = await screen.findAllByTestId("member-row");

    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Give Administrator" }));
    expect(await screen.findByTestId("members-problem")).toHaveTextContent("No licence of this type is free.");
    expect(calls.find((c) => c.key === "PUT /api/v1/members/11111111-1111-4111-8111-111111111111/licence")?.body).toBe(
      JSON.stringify({ licenceType: "admin" }),
    );

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Take back" }));
    await waitFor(() =>
      expect(calls.map((c) => c.key)).toContain("DELETE /api/v1/members/22222222-2222-4222-8222-222222222222/licence"),
    );
    expect(await screen.findByTestId("members-notice")).toHaveTextContent("The licence was taken back.");
  });
});

describe("SupportAccessPanel", () => {
  const REQUESTS = {
    data: [
      {
        id: "55555555-5555-4555-8555-555555555555",
        requester: "Support A",
        reason: "customer asked for help",
        status: "REQUESTED",
        requestedMinutes: 30,
        requestedAt: "2026-10-02T10:00:00Z",
        active: false,
      },
      {
        id: "66666666-6666-4666-8666-666666666666",
        requester: "Support B",
        reason: "investigating",
        status: "APPROVED",
        requestedMinutes: 60,
        requestedAt: "2026-10-02T09:00:00Z",
        accessExpiresAt: "2026-10-02T11:00:00Z",
        active: true,
      },
    ],
  };

  it("shows who asked and what became of it, and lets the administrator decide", async () => {
    const calls = fakeApi({
      "GET /api/v1/support-access": () => json(REQUESTS),
      "POST /api/v1/support-access/55555555-5555-4555-8555-555555555555/approve": () => new Response(null, { status: 204 }),
      "POST /api/v1/support-access/66666666-6666-4666-8666-666666666666/revoke": () => new Response(null, { status: 204 }),
    });
    render(<SupportAccessPanel />);

    const rows = await screen.findAllByTestId("support-request-row");
    expect(rows[0]).toHaveTextContent("Support A");
    expect(rows[0]).toHaveTextContent("REQUESTED");
    expect(rows[1]).toHaveTextContent("ACTIVE");
    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Approve" }));
    expect(await screen.findByTestId("support-notice")).toHaveTextContent("approved for a limited time");
    expect(calls.find((c) => c.key.endsWith("/approve"))?.body).toBe(JSON.stringify({}));
    fireEvent.click(within((await screen.findAllByTestId("support-request-row"))[1] as HTMLElement).getByRole("button", { name: "End now" }));
    await waitFor(() => expect(calls.map((c) => c.key)).toContain("POST /api/v1/support-access/66666666-6666-4666-8666-666666666666/revoke"));
  });

  it("denies a request", async () => {
    const calls = fakeApi({
      "GET /api/v1/support-access": () => json(REQUESTS),
    });
    render(<SupportAccessPanel />);
    const rows = await screen.findAllByTestId("support-request-row");

    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Deny" }));

    await waitFor(() => expect(calls.map((c) => c.key)).toContain("POST /api/v1/support-access/55555555-5555-4555-8555-555555555555/deny"));
  });

  it("stays out of the way when the API refuses the list (a member who is not an administrator)", async () => {
    fakeApi({
      "GET /api/v1/support-access": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    const { container } = render(<SupportAccessPanel />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("says plainly that nobody has access without approval", async () => {
    fakeApi({ "GET /api/v1/support-access": () => json({ data: [] }) });
    render(<SupportAccessPanel />);

    expect(await screen.findByText("No requests.")).toBeInTheDocument();
    expect(screen.getByText(/can only look at this organization after you approve a request/)).toBeInTheDocument();
  });
});
