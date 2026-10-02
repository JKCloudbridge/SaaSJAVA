import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MembersPanel } from "./MembersPanel";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const MEMBERS = [
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
];

const INVITATIONS = [
  {
    id: "44444444-4444-4444-8444-444444444444",
    email: "invited-a@example.test",
    administrator: false,
    status: "OPEN",
    expiresAt: "2026-10-09T10:00:00Z",
    sentCount: 1,
    createdAt: "2026-10-02T10:00:00Z",
  },
];

type Handler = (request: Request) => Response | Promise<Response>;

function fakeApi(overrides: Record<string, Handler> = {}) {
  const calls: Array<{ key: string; body: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      calls.push({ key, body: await request.clone().text() });
      const handler = overrides[key];
      if (handler) {
        return handler(request);
      }
      switch (key) {
        case "GET /api/v1/members":
          return json({ data: MEMBERS });
        case "GET /api/v1/invitations":
          return json({ data: INVITATIONS });
        default:
          return new Response(null, { status: 204 });
      }
    }),
  );
  return calls;
}

describe("MembersPanel", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("lists the members and the invitations as the API reports them", async () => {
    fakeApi();
    render(<MembersPanel />);

    const rows = await screen.findAllByTestId("member-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Admin A (you)");
    expect(rows[0]).toHaveTextContent("administrator");
    expect(rows[2]).toHaveTextContent("Deactivated");
    expect(within(screen.getByTestId("invitations")).getByText("invited-a@example.test")).toBeInTheDocument();
  });

  it("shows the API's refusal instead of the lists when the caller is not an administrator", async () => {
    fakeApi({
      "GET /api/v1/members": () => json({ error: { code: "FORBIDDEN", message: "You are not allowed to perform this action." } }, 403),
      "GET /api/v1/invitations": () => json({ error: { code: "FORBIDDEN", message: "You are not allowed to perform this action." } }, 403),
    });
    render(<MembersPanel />);

    expect(await screen.findByTestId("members-failed")).toHaveTextContent("not allowed");
    expect(screen.queryByTestId("member-row")).toBeNull();
  });

  it("invites an address, shows the one sentence the API gives and clears the form", async () => {
    const calls = fakeApi({
      "POST /api/v1/invitations": () =>
        json({ data: { message: "If this address can be invited, an e-mail with the invitation is on its way." } }, 202),
    });
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.change(screen.getByLabelText("E-mail address"), { target: { value: "new-a@example.test" } });
    fireEvent.click(screen.getByLabelText(/Make this person an administrator/));
    fireEvent.submit(screen.getByLabelText("E-mail address").closest("form")!);

    expect(await screen.findByTestId("members-notice")).toHaveTextContent("on its way");
    expect(calls.find((call) => call.key === "POST /api/v1/invitations")?.body).toBe(
      JSON.stringify({ email: "new-a@example.test", administrator: true }),
    );
    expect(screen.getByLabelText("E-mail address")).toHaveValue("");
  });

  it("deactivates and reactivates members through the API and reloads", async () => {
    const calls = fakeApi();
    render(<MembersPanel />);
    const rows = await screen.findAllByTestId("member-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Deactivate" }));
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain(
        "POST /api/v1/members/22222222-2222-4222-8222-222222222222/deactivate",
      ),
    );
    expect(await screen.findByTestId("members-notice")).toHaveTextContent("deactivated");
    fireEvent.click(within((await screen.findAllByTestId("member-row"))[2] as HTMLElement).getByRole("button", { name: "Reactivate" }));
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain(
        "POST /api/v1/members/33333333-3333-4333-8333-333333333333/reactivate",
      ),
    );
    expect(calls.filter((call) => call.key === "GET /api/v1/members").length).toBeGreaterThanOrEqual(3);
  });

  it("shows the API's words when the last administrator cannot be removed", async () => {
    fakeApi({
      "POST /api/v1/members/11111111-1111-4111-8111-111111111111/deactivate": () =>
        json(
          {
            error: {
              code: "CONFLICT",
              message: "The last administrator of an organization cannot be removed. Name another administrator first.",
            },
          },
          409,
        ),
    });
    render(<MembersPanel />);
    const rows = await screen.findAllByTestId("member-row");

    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Deactivate" }));

    expect(await screen.findByTestId("members-problem")).toHaveTextContent("last administrator");
  });

  it("names and releases administrators with the value the button promises", async () => {
    const calls = fakeApi();
    render(<MembersPanel />);
    const rows = await screen.findAllByTestId("member-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Make administrator" }));

    await waitFor(() =>
      expect(calls.find((call) => call.key === "PUT /api/v1/members/22222222-2222-4222-8222-222222222222/administrator")?.body)
        .toBe(JSON.stringify({ administrator: true })),
    );
  });

  it("withdraws and sends an invitation again", async () => {
    const calls = fakeApi({
      "POST /api/v1/invitations/44444444-4444-4444-8444-444444444444/resend": () =>
        json({ data: { message: "If this address can be invited, an e-mail with the invitation is on its way." } }, 202),
    });
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.click(screen.getByRole("button", { name: "Send again" }));
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain("POST /api/v1/invitations/44444444-4444-4444-8444-444444444444/resend"),
    );
    fireEvent.click(await screen.findByRole("button", { name: "Withdraw" }));
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain("POST /api/v1/invitations/44444444-4444-4444-8444-444444444444/revoke"),
    );
  });
});
