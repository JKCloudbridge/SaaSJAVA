import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setUrl } from "@/test/navigation";
import { MembersPanel } from "./MembersPanel";

vi.mock("@/lib/navigation", () => ({ navigate: vi.fn() }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const ADMIN_PROFILE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const MEMBER_PROFILE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const ROLE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

const MEMBERS = [
  {
    id: "11111111-1111-4111-8111-111111111111",
    email: "admin-a@example.test",
    displayName: "Admin A",
    status: "ACTIVE",
    foundingAdministrator: true,
    since: "2026-10-01T10:00:00Z",
    you: true,
    licence: "admin",
    profileId: ADMIN_PROFILE,
    profileName: "Organization administrator",
    licensed: true,
    policies: [],
  },
  {
    id: "22222222-2222-4222-8222-222222222222",
    email: "user-a@example.test",
    displayName: "User A",
    status: "ACTIVE",
    foundingAdministrator: false,
    since: "2026-10-01T11:00:00Z",
    you: false,
    profileId: MEMBER_PROFILE,
    profileName: "Member",
    licensed: false,
    roleId: ROLE,
    roleName: "role-a",
    policies: [{ id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd", name: "policy-a" }],
  },
  {
    id: "33333333-3333-4333-8333-333333333333",
    email: "user-b@example.test",
    displayName: "User B",
    status: "DEACTIVATED",
    foundingAdministrator: false,
    since: "2026-10-01T12:00:00Z",
    you: false,
    licensed: false,
    policies: [],
  },
];

const INVITATIONS = [
  {
    id: "44444444-4444-4444-8444-444444444444",
    email: "invited-a@example.test",
    displayName: "Invited A",
    profileName: "Member",
    status: "OPEN",
    expiresAt: "2026-10-09T10:00:00Z",
    sentCount: 1,
    createdAt: "2026-10-02T10:00:00Z",
  },
  {
    id: "45454545-4545-4545-8545-454545454545",
    email: "saved-a@example.test",
    status: "OPEN",
    expiresAt: "2026-10-09T10:00:00Z",
    sentCount: 0,
    createdAt: "2026-10-02T10:00:00Z",
  },
];

const PROFILES = [
  { id: ADMIN_PROFILE, name: "Organization administrator", description: "", licenceType: "admin", abilities: [], system: true, fullAccess: true, defaultProfile: false, members: 1 },
  { id: MEMBER_PROFILE, name: "Member", description: "", licenceType: "user", abilities: [], system: true, fullAccess: false, defaultProfile: true, members: 1 },
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
        case "GET /api/v1/profiles":
          return json({ data: PROFILES });
        case "GET /api/v1/roles":
          return json({ data: [{ id: ROLE, name: "role-a", description: "", members: 1 }] });
        default:
          return new Response(null, { status: 204 });
      }
    }),
  );
  return calls;
}


const USER_A = "22222222-2222-4222-8222-222222222222";
const ADMIN_A = "11111111-1111-4111-8111-111111111111";
const USER_B = "33333333-3333-4333-8333-333333333333";

/** Opens the member's page from the list, by the name in the first column. */
async function openMember(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("member-detail");
}

describe("MembersPanel", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
    act(() => setUrl("/members"));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("lists the members with profile, licence and status tags, and the invitations below, as the API reports them", async () => {
    fakeApi();
    render(<MembersPanel />);

    const rows = await screen.findAllByTestId("member-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Admin A (you)");
    expect(rows[0]).toHaveTextContent("admin-a@example.test");
    expect(rows[0]).toHaveTextContent("Organization administrator");
    expect(rows[0]).toHaveTextContent("Active");
    expect(rows[1]).toHaveTextContent("Member");
    // The mark for a member whose profile has no licence yet stays on the list.
    expect(rows[1]).toHaveTextContent("Waiting for a licence");
    expect(rows[2]).toHaveTextContent("Deactivated");
    const invitations = screen.getByTestId("invitations");
    expect(within(invitations).getByText("invited-a@example.test")).toBeInTheDocument();
    expect(invitations).toHaveTextContent("saved, not sent");
  });

  it("filters the members by name, address, profile or role with the search box", async () => {
    fakeApi();
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search members" }), { target: { value: "role-a" } });
    expect(screen.getAllByTestId("member-row")).toHaveLength(1);
    expect(screen.getByText("Showing 1 of 3.")).toBeInTheDocument();
  });

  it("shows the API's refusal instead of the lists when the caller may not see members", async () => {
    fakeApi({
      "GET /api/v1/members": () => json({ error: { code: "FORBIDDEN", message: "You are not allowed to perform this action." } }, 403),
      "GET /api/v1/invitations": () => json({ error: { code: "FORBIDDEN", message: "You are not allowed to perform this action." } }, 403),
    });
    render(<MembersPanel />);

    expect(await screen.findByTestId("members-failed")).toHaveTextContent("not allowed");
    expect(screen.queryByTestId("member-row")).toBeNull();
  });

  it("creates a member: name, address, profile and role, sent now, and returns to the list with the notice", async () => {
    const calls = fakeApi({
      "POST /api/v1/invitations": () =>
        json({ data: { message: "If this address can be invited, an e-mail with the invitation is on its way." } }, 202),
    });
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.click(screen.getByRole("button", { name: "New member" }));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "New A" } });
    fireEvent.change(screen.getByLabelText("E-mail address"), { target: { value: "new-a@example.test" } });
    fireEvent.change(screen.getByLabelText("Profile"), { target: { value: ADMIN_PROFILE } });
    fireEvent.change(screen.getByLabelText("Role"), { target: { value: ROLE } });
    fireEvent.click(screen.getByRole("button", { name: "Create the member" }));

    expect(await screen.findByTestId("members-notice")).toHaveTextContent("on its way");
    expect(calls.find((call) => call.key === "POST /api/v1/invitations")?.body).toBe(
      JSON.stringify({ email: "new-a@example.test", displayName: "New A", profileId: ADMIN_PROFILE, roleId: ROLE, active: true }),
    );
    expect(await screen.findByTestId("members")).toBeInTheDocument();
  });

  it("saves the member without sending when Active is switched off", async () => {
    const calls = fakeApi({
      "POST /api/v1/invitations": () => json({ data: { message: "Saved." } }, 202),
    });
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.click(screen.getByRole("button", { name: "New member" }));
    fireEvent.change(await screen.findByLabelText("E-mail address"), { target: { value: "later-a@example.test" } });
    fireEvent.click(screen.getByLabelText(/Active: send the link now/));
    fireEvent.click(screen.getByRole("button", { name: "Create the member" }));

    await waitFor(() => expect(calls.some((call) => call.key === "POST /api/v1/invitations")).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/invitations")!.body)).toMatchObject({
      email: "later-a@example.test",
      active: false,
    });
  });

  it("keeps the form and the API's words when a member cannot be created", async () => {
    fakeApi({
      "POST /api/v1/invitations": () => json({ error: { code: "VALIDATION_ERROR", message: "Check the address." } }, 400),
    });
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    fireEvent.click(screen.getByRole("button", { name: "New member" }));
    fireEvent.change(await screen.findByLabelText("E-mail address"), { target: { value: "bad" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the member" }));

    expect(await screen.findByTestId("members-problem")).toHaveTextContent("Check the address.");
    expect(screen.getByLabelText("E-mail address")).toHaveValue("bad");
  });

  it("deactivates and reactivates members from their page through the API and reloads", async () => {
    const calls = fakeApi();
    render(<MembersPanel />);

    const first = await openMember("User A");
    fireEvent.click(within(first).getByRole("button", { name: "Deactivate" }));
    await waitFor(() => expect(calls.map((call) => call.key)).toContain(`POST /api/v1/members/${USER_A}/deactivate`));
    expect(await screen.findByTestId("members-notice")).toHaveTextContent("deactivated");

    fireEvent.click(screen.getByRole("button", { name: "Members" }));
    const second = await openMember("User B");
    fireEvent.click(within(second).getByRole("button", { name: "Reactivate" }));
    await waitFor(() => expect(calls.map((call) => call.key)).toContain(`POST /api/v1/members/${USER_B}/reactivate`));
    expect(calls.filter((call) => call.key === "GET /api/v1/members").length).toBeGreaterThanOrEqual(3);
  });

  it("shows the API's words when the last member who can manage access cannot be removed", async () => {
    fakeApi({
      [`POST /api/v1/members/${ADMIN_A}/deactivate`]: () =>
        json(
          {
            error: {
              code: "CONFLICT",
              message:
                "The organization must keep at least one active member who can manage access. Give another member that ability first.",
            },
          },
          409,
        ),
    });
    render(<MembersPanel />);

    const page = await openMember("Admin A (you)");
    fireEvent.click(within(page).getByRole("button", { name: "Deactivate" }));

    expect(await screen.findByTestId("members-problem")).toHaveTextContent("manage access");
  });

  it("gives an unlicensed member the licence their profile needs, without a body", async () => {
    const calls = fakeApi();
    render(<MembersPanel />);

    const page = await openMember("User A");
    expect(within(page).getByTestId("member-profile")).toHaveTextContent("Member, role-a, policy-a");
    expect(page).toHaveTextContent("Waiting for a licence");
    fireEvent.click(within(page).getByRole("button", { name: "Give a licence" }));

    await waitFor(() => expect(calls.find((call) => call.key === `PUT /api/v1/members/${USER_A}/licence`)?.body).toBe(""));
  });

  it("opens the access of a member on their page and loads it from the API", async () => {
    const calls = fakeApi({
      [`GET /api/v1/members/${USER_A}/access`]: () =>
        json({
          data: {
            membershipId: USER_A,
            profileId: MEMBER_PROFILE,
            profileName: "Member",
            profileLicenceType: "user",
            licenceHeld: false,
            policies: [],
            groups: [{ id: "99999999-9999-4999-8999-999999999999", name: "group-a", direct: false }],
            grants: [{ ability: "members.view", reason: "covers for a colleague", since: "2026-10-02T10:00:00Z" }],
            abilities: ["members.view"],
            data: { everything: false, objects: [{ key: "object-a", actions: ["read", "update"] }], fields: [] },
          },
        }),
      "GET /api/v1/abilities": () => json({ data: [{ key: "members.view", name: "See members", description: "x" }] }),
      "GET /api/v1/access-policies": () => json({ data: [] }),
    });
    render(<MembersPanel />);

    await openMember("User A");

    expect(await screen.findByTestId("member-abilities")).toHaveTextContent("See members");
    expect(screen.getByTestId("member-unlicensed")).toHaveTextContent("does not hold the user licence");
    expect(screen.getByTestId("member-grants")).toHaveTextContent("covers for a colleague");
    expect(screen.getByTestId("member-groups")).toHaveTextContent("group-a (through another group)");
    expect(screen.getByTestId("member-data")).toHaveTextContent("object-a: read, update");
    expect(calls.map((call) => call.key)).toContain(`GET /api/v1/members/${USER_A}/access`);
  });

  it("does not offer the access of a member who was deactivated", async () => {
    fakeApi();
    render(<MembersPanel />);

    await openMember("User B");

    expect(screen.queryByTestId("member-access")).toBeNull();
  });

  it("says plainly that an open member no longer exists", async () => {
    fakeApi();
    act(() => setUrl("/members?open=99999999-9999-4999-8999-999999999999"));
    render(<MembersPanel />);

    expect(await screen.findByText("This member does not exist any more.")).toBeInTheDocument();
  });

  it("withdraws, sends and sends again an invitation", async () => {
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
    expect(screen.getByRole("button", { name: "Send" })).toBeInTheDocument();
    fireEvent.click((await screen.findAllByRole("button", { name: "Withdraw" }))[0] as HTMLElement);
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain("POST /api/v1/invitations/44444444-4444-4444-8444-444444444444/revoke"),
    );
  });

  it("keeps leaving the organization as a section below the list", async () => {
    const calls = fakeApi();
    render(<MembersPanel />);
    await screen.findAllByTestId("member-row");

    expect(screen.getByRole("group", { name: "Leave this organization" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Leave the organization" }));
    await waitFor(() => expect(calls.map((call) => call.key)).toContain("POST /api/v1/organization/leave"));
  });

  it("loads the lists once per mount, also under React strict mode", async () => {
    const calls = fakeApi();
    render(
      <StrictMode>
        <MembersPanel />
      </StrictMode>,
    );

    await screen.findAllByTestId("member-row");
    await waitFor(() => expect(calls.filter((call) => call.key === "GET /api/v1/members").length).toBeLessThanOrEqual(2));
  });
});
