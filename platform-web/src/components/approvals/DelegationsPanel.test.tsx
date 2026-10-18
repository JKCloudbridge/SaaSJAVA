import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PROCESS } from "@/test/approvals";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { DelegationsPanel } from "./DelegationsPanel";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/approvals/delegations";
const CREATE = "POST /api/v1/approvals/delegations";

const ROW = {
  id: "d-1",
  delegator: { userId: "u-1", name: "User A" },
  delegate: { userId: "u-2", name: "User B" },
  startsOn: "2026-10-18",
  endsOn: "2026-10-20",
  timeZone: "UTC",
  inForce: true,
};

function api(abilities: string[], rows: object[] = [ROW], more: Record<string, () => Response> = {}) {
  return fakeApi({
    "GET /api/v1/tenant/current": () => json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } }),
    "GET /api/v1/auth/me": () =>
      json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities } }),
    [LIST]: () => json({ data: rows }),
    "GET /api/v1/metadata/approvals": () => json({ data: [PROCESS] }),
    "GET /api/v1/approvals/people": () => json({ data: [{ userId: "u-2", name: "User B" }] }),
    ...more,
  });
}

function panel() {
  return render(
    <SessionProvider>
      <DelegationsPanel />
    </SessionProvider>,
  );
}

describe("DelegationsPanel", () => {
  it("lists the delegations with the days, the zone and whether they are in force", async () => {
    api([]);

    panel();

    const row = await screen.findByTestId("delegation-row");
    expect(row).toHaveTextContent("User A");
    expect(row).toHaveTextContent("User B");
    expect(row).toHaveTextContent("2026-10-18 to 2026-10-20 (UTC)");
    expect(row).toHaveTextContent("Every process");
    expect(row).toHaveTextContent("In force");
  });

  it("offers a member only their own delegation and nothing about other people or processes", async () => {
    api([]);

    panel();
    await screen.findByTestId("delegation-row");

    expect(screen.queryByLabelText(/For \(leave empty for yourself\)/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Only for one process/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Show everybody/)).not.toBeInTheDocument();
  });

  it("offers a member who manages approvals the extra choices and asks for everybody's delegations when told to", async () => {
    const calls = api(["approvals.manage"]);

    panel();
    await screen.findByTestId("delegation-row");
    expect(await screen.findByLabelText(/Only for one process/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/Show everybody/));

    await vi.waitFor(() =>
      expect(calls.filter((call) => call.key === LIST).length).toBeGreaterThan(1),
    );
  });

  it("will not name a delegate before somebody is chosen, then sends the days and the zone", async () => {
    const calls = api([], [], { [CREATE]: () => json({ data: ROW }, 201) });
    panel();
    await screen.findByTestId("delegations-empty");
    expect(screen.getByRole("button", { name: "Name the delegate" })).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Delegate"), { target: { value: "us" } });
    fireEvent.click(await screen.findByRole("button", { name: "User B" }, { timeout: 3000 }));
    fireEvent.change(screen.getByLabelText("First day"), { target: { value: "2026-10-18" } });
    fireEvent.change(screen.getByLabelText(/Last day/), { target: { value: "2026-10-20" } });
    fireEvent.change(screen.getByLabelText("Time zone of the days"), { target: { value: "UTC" } });
    fireEvent.click(screen.getByRole("button", { name: "Name the delegate" }));

    expect(await screen.findByTestId("delegations-notice")).toHaveTextContent("The delegation was made.");
    expect(JSON.parse(calls.find((call) => call.key === CREATE)?.body ?? "{}")).toEqual({
      delegateId: "u-2",
      startsOn: "2026-10-18",
      endsOn: "2026-10-20",
      timeZone: "UTC",
    });
  });

  it("shows the API's words when a delegation is refused", async () => {
    api([], [], { [CREATE]: () => refusal("VALIDATION_ERROR", "The first day cannot be in the past.", 400) });
    panel();
    await screen.findByTestId("delegations-empty");
    fireEvent.change(screen.getByLabelText("Delegate"), { target: { value: "us" } });
    fireEvent.click(await screen.findByRole("button", { name: "User B" }, { timeout: 3000 }));

    fireEvent.click(screen.getByRole("button", { name: "Name the delegate" }));

    expect(await screen.findByTestId("delegations-problem")).toHaveTextContent("cannot be in the past");
  });

  it("ends a delegation and says that tasks the delegate already holds stay", async () => {
    const calls = api([], [ROW], { "DELETE /api/v1/approvals/delegations/d-1": () => new Response(null, { status: 204 }) });
    panel();

    fireEvent.click(within(await screen.findByTestId("delegation-row")).getByRole("button", { name: "End it" }));

    expect(await screen.findByTestId("delegations-notice")).toHaveTextContent("Tasks the delegate already holds stay.");
    expect(calls.some((call) => call.key === "DELETE /api/v1/approvals/delegations/d-1")).toBe(true);
  });

  it("shows the API's words when the list cannot be read", async () => {
    api([], [], { [LIST]: () => refusal("FORBIDDEN", "Approvals are not part of your plan.", 403) });

    panel();

    expect(await screen.findByTestId("delegations-failed")).toHaveTextContent("not part of your plan");
  });
});
