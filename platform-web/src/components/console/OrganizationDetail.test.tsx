import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { OrganizationDetail } from "./OrganizationDetail";

const ID = "11111111-1111-4111-8111-111111111111";

vi.mock("next/navigation", () => ({ useParams: () => ({ organizationId: "11111111-1111-4111-8111-111111111111" }) }));

const DETAIL = {
  id: ID,
  slug: "tenant-a",
  displayName: "Tenant A",
  status: "ACTIVE",
  statusChangedAt: "2026-10-01T10:00:00Z",
  subscription: {
    planKey: "trial",
    planName: "Trial",
    status: "TRIAL",
    startedAt: "2026-10-01T10:00:00Z",
    trialEndsAt: "2026-10-31T10:00:00Z",
    trialExpired: false,
  },
  pools: [{ licenceType: "user", name: "User", quantity: 5, assigned: 2, available: 3 }],
  entitlements: [
    { key: "approvals", name: "Approvals", inPlan: true, enabled: true },
    { key: "workflows", name: "Workflows", inPlan: false, override: true, enabled: true },
  ],
  firstAdministrator: { id: "44444444-4444-4444-8444-444444444444", status: "OPEN", expiresAt: "2026-10-09T10:00:00Z", sentCount: 2 },
};

const PLANS = { data: [{ key: "trial", name: "Trial", licences: {}, features: [] }, { key: "plan-a", name: "Plan A", licences: {}, features: [] }] };

function api(extra: Record<string, () => Response> = {}) {
  return fakeApi({
    [`GET /api/v1/platform/organizations/${ID}`]: () => json({ data: DETAIL }),
    "GET /api/v1/platform/plans": () => json(PLANS),
    [`GET /api/v1/platform/organizations/${ID}/support-access`]: () =>
      json({
        data: [
          {
            id: "55555555-5555-4555-8555-555555555555",
            requester: "Support A",
            reason: "customer asked",
            status: "REQUESTED",
            requestedMinutes: 30,
            requestedAt: "2026-10-02T10:00:00Z",
            active: false,
          },
        ],
      }),
    ...extra,
  });
}

describe("OrganizationDetail", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("shows the state, the subscription, the pools, the features and the invitation without any address", async () => {
    api();
    render(<OrganizationDetail />);

    const detail = await screen.findByTestId("organization-detail");
    expect(detail).toHaveTextContent("Tenant A");
    expect(screen.getByTestId("subscription")).toHaveTextContent("Plan Trial (TRIAL)");
    expect(screen.getByTestId("pools")).toHaveTextContent("5");
    expect(screen.getByTestId("entitlements")).toHaveTextContent("Workflows");
    expect(screen.getByTestId("first-administrator")).toHaveTextContent("The address is not shown.");
    expect(screen.getByTestId("support-grants")).toHaveTextContent("Support A");
  });

  it("shows the API's refusal for an organization it will not show", async () => {
    fakeApi({
      [`GET /api/v1/platform/organizations/${ID}`]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
      "GET /api/v1/platform/plans": () => json(PLANS),
    });
    render(<OrganizationDetail />);

    expect(await screen.findByTestId("organization-failed")).toHaveTextContent("not allowed");
  });

  it("suspends with the reason that was typed and reloads", async () => {
    const calls = api();
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");

    fireEvent.click(screen.getByRole("button", { name: "Suspend" }));
    fireEvent.change(screen.getByLabelText("Reason (kept in the audit trail, no personal data)"), {
      target: { value: "payment dispute" },
    });
    fireEvent.submit(screen.getByRole("form", { name: "Suspend" }));

    expect(await screen.findByTestId("organization-notice")).toHaveTextContent("suspended");
    expect(calls.find((call) => call.key === `POST /api/v1/platform/organizations/${ID}/suspend`)?.body).toBe(
      JSON.stringify({ reason: "payment dispute" }),
    );
    expect(calls.filter((call) => call.key === `GET /api/v1/platform/organizations/${ID}`).length).toBeGreaterThanOrEqual(2);
  });

  it("asks to type the short name before closing for good and sends it", async () => {
    const calls = api();
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");

    fireEvent.click(screen.getByRole("button", { name: "Close for good" }));
    const form = screen.getByRole("form", { name: "Close for good" });
    expect(within(form).getByLabelText("Type tenant-a to confirm")).toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText("Reason (kept in the audit trail, no personal data)"), {
      target: { value: "client left" },
    });
    fireEvent.change(within(form).getByLabelText("Type tenant-a to confirm"), { target: { value: "tenant-a" } });
    fireEvent.submit(form);

    await waitFor(() =>
      expect(calls.find((call) => call.key === `POST /api/v1/platform/organizations/${ID}/deactivate`)?.body).toBe(
        JSON.stringify({ reason: "client left", confirm: "tenant-a" }),
      ),
    );
  });

  it("shows the API's words when the role may not do it", async () => {
    api({
      [`POST /api/v1/platform/organizations/${ID}/suspend`]: () =>
        refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");

    fireEvent.click(screen.getByRole("button", { name: "Suspend" }));
    fireEvent.change(screen.getByLabelText("Reason (kept in the audit trail, no personal data)"), { target: { value: "x" } });
    fireEvent.submit(screen.getByRole("form", { name: "Suspend" }));

    expect(await screen.findByTestId("organization-problem")).toHaveTextContent("not allowed");
  });

  it("switches a feature and sets a pool with the page reason, and shows the API's words for a pool below use", async () => {
    const calls = api({
      [`PUT /api/v1/platform/organizations/${ID}/pools/user`]: () =>
        refusal("CONFLICT", "A pool cannot be reduced below the licences that are in use. Release some first.", 409),
      [`PUT /api/v1/platform/organizations/${ID}/entitlements/approvals`]: () => json({ data: DETAIL }),
    });
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");
    fireEvent.change(screen.getByLabelText(/Reason for the changes below/), { target: { value: "pilot" } });

    const row = within(screen.getByTestId("entitlements")).getByText("Approvals").closest("tr") as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Switch off" }));
    await waitFor(() =>
      expect(calls.find((call) => call.key === `PUT /api/v1/platform/organizations/${ID}/entitlements/approvals`)?.body)
        .toBe(JSON.stringify({ enabled: false, reason: "pilot" })),
    );

    fireEvent.change(screen.getByLabelText("New number of User licences"), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: "Set" }));
    expect(await screen.findByTestId("organization-problem")).toHaveTextContent("below the licences that are in use");
    expect(calls.find((call) => call.key === `PUT /api/v1/platform/organizations/${ID}/pools/user`)?.body).toBe(
      JSON.stringify({ quantity: 1, reason: "pilot" }),
    );
  });

  it("sends the first-administrator invitation again and invites a new address", async () => {
    const calls = api({
      [`POST /api/v1/platform/organizations/${ID}/first-administrator/resend`]: () =>
        json({ data: { message: "If this address can be invited, an e-mail with the invitation is on its way." } }, 202),
      [`POST /api/v1/platform/organizations/${ID}/first-administrator`]: () =>
        json({ data: { message: "If this address can be invited, an e-mail with the invitation is on its way." } }, 202),
    });
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");

    fireEvent.click(screen.getByRole("button", { name: "Send the invitation again" }));
    expect(await screen.findByTestId("organization-notice")).toHaveTextContent("on its way");
    fireEvent.change(screen.getByLabelText("Invite a new first administrator", { selector: "input" }), { target: { value: "new-first@example.test" } });
    fireEvent.submit(screen.getByRole("form", { name: "Invite a new first administrator" }));

    await waitFor(() =>
      expect(calls.find((call) => call.key === `POST /api/v1/platform/organizations/${ID}/first-administrator`)?.body)
        .toBe(JSON.stringify({ email: "new-first@example.test" })),
    );
  });

  it("asks for support access and says nothing is granted until the organization approves", async () => {
    const calls = api({
      [`POST /api/v1/platform/organizations/${ID}/support-access`]: () => new Response(null, { status: 201 }),
    });
    render(<OrganizationDetail />);
    await screen.findByTestId("organization-detail");

    fireEvent.change(screen.getByLabelText("Why access is needed (no personal data)"), { target: { value: "customer asked" } });
    fireEvent.change(screen.getByLabelText("How long (15 to 240 minutes)"), { target: { value: "45" } });
    fireEvent.submit(screen.getByRole("form", { name: "Ask for support access" }));

    expect(await screen.findByTestId("organization-notice")).toHaveTextContent("Nothing is granted until the organization approves");
    expect(calls.find((call) => call.key === `POST /api/v1/platform/organizations/${ID}/support-access`)?.body).toBe(
      JSON.stringify({ reason: "customer asked", minutes: 45 }),
    );
  });
});
