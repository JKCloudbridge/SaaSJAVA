import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { AuditViewer } from "./AuditViewer";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const ACTOR = "11111111-1111-4111-8111-111111111111";

function event(id: string, type: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    occurredAt: "2026-10-04T10:00:00Z",
    type,
    outcome: "SUCCESS",
    actorUserId: ACTOR,
    source: "API",
    attributes: {},
    ...extra,
  };
}

const PAGE_ONE = {
  data: [
    event("aaaaaaaa-0000-4000-8000-000000000001", "access.profile.created", { attributes: { profile: "profile-a" } }),
    event("aaaaaaaa-0000-4000-8000-000000000002", "access.member.profile_set", {
      oldValue: "profile-a",
      newValue: "profile-b",
    }),
  ],
  pagination: { limit: 50, hasMore: true, nextCursor: "next-1" },
};
const PAGE_TWO = {
  data: [event("aaaaaaaa-0000-4000-8000-000000000003", "membership.action.refused", { outcome: "DENIED", reason: "missing_ability" })],
  pagination: { limit: 50, hasMore: false },
};

describe("AuditViewer", () => {
  it("lists the events newest first as the API reports them, with a sentence for the common kinds", async () => {
    fakeApi({ "GET /api/v1/audit-events": () => json(PAGE_ONE) });
    render(<AuditViewer scope="organization" />);

    const rows = await screen.findAllByTestId("audit-event");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("A profile was created");
    expect(within(rows[0] as HTMLElement).getByTestId("audit-details")).toHaveTextContent("profile: profile-a");
    expect(within(rows[1] as HTMLElement).getByTestId("audit-change")).toHaveTextContent("profile-a → profile-b");
    expect(within(rows[1] as HTMLElement).getByTestId("audit-actor")).toHaveTextContent("11111111");
  });

  it("says plainly when there is nothing to show", async () => {
    fakeApi({ "GET /api/v1/audit-events": () => json({ data: [], pagination: { limit: 50, hasMore: false } }) });
    render(<AuditViewer scope="organization" />);

    expect(await screen.findByTestId("audit-empty")).toHaveTextContent("No events yet.");
  });

  it("shows the API's own words when it refuses", async () => {
    fakeApi({ "GET /api/v1/audit-events": () => refusal("FORBIDDEN", "You are not allowed to do this.", 403) });
    render(<AuditViewer scope="organization" />);

    expect(await screen.findByTestId("audit-problem")).toHaveTextContent("You are not allowed to do this.");
    expect(screen.queryByTestId("audit-event")).toBeNull();
  });

  it("shows more events with the cursor and keeps the ones already shown", async () => {
    const calls: string[] = [];
    fakeApi({
      "GET /api/v1/audit-events": (request) => {
        const url = new URL(request.url);
        calls.push(url.search);
        return json(url.searchParams.get("cursor") === "next-1" ? PAGE_TWO : PAGE_ONE);
      },
    });
    render(<AuditViewer scope="organization" />);
    await screen.findAllByTestId("audit-event");

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    await waitFor(() => expect(screen.getAllByTestId("audit-event")).toHaveLength(3));
    expect(screen.getByTestId("audit-reason")).toHaveTextContent("missing_ability");
    expect(calls[1]).toContain("cursor=next-1");
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("sends the filters and starts again from the first page", async () => {
    const queries: string[] = [];
    fakeApi({
      "GET /api/v1/audit-events": (request) => {
        queries.push(new URL(request.url).search);
        return json(PAGE_ONE);
      },
    });
    render(<AuditViewer scope="organization" />);
    await screen.findAllByTestId("audit-event");

    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "access" } });
    fireEvent.change(screen.getByLabelText("About (identifier)"), { target: { value: "record-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Show" }));

    await waitFor(() => expect(queries).toHaveLength(2));
    expect(queries[1]).toContain("kind=access");
    expect(queries[1]).toContain("target=record-1");
    expect(queries[1]).not.toContain("cursor");
  });

  it("says that nothing matches when a filter finds nothing", async () => {
    fakeApi({
      "GET /api/v1/audit-events": (request) =>
        new URL(request.url).searchParams.get("kind")
          ? json({ data: [], pagination: { limit: 50, hasMore: false } })
          : json(PAGE_ONE),
    });
    render(<AuditViewer scope="organization" />);
    await screen.findAllByTestId("audit-event");

    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "retention" } });
    fireEvent.click(screen.getByRole("button", { name: "Show" }));

    expect(await screen.findByTestId("audit-empty")).toHaveTextContent("No events match.");
  });

  it("reads the platform's events from the platform endpoint", async () => {
    const calls = fakeApi({ "GET /api/v1/platform/audit-events": () => json(PAGE_ONE) });
    render(<AuditViewer scope="platform" />);

    await screen.findAllByTestId("audit-event");
    expect(calls.map((call) => call.key)).toEqual(["GET /api/v1/platform/audit-events"]);
    expect(screen.getByLabelText("Kind")).toHaveDisplayValue("Everything");
    expect(screen.getByRole("option", { name: "Platform actions" })).toBeInTheDocument();
  });

  it("shows one list under React strict mode (effects run twice in development)", async () => {
    fakeApi({ "GET /api/v1/audit-events": () => json(PAGE_ONE) });
    render(
      <StrictMode>
        <AuditViewer scope="organization" />
      </StrictMode>,
    );

    expect(await screen.findAllByTestId("audit-event")).toHaveLength(2);
  });
});
