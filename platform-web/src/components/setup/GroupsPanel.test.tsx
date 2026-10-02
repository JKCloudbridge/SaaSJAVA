import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { GroupsPanel } from "./GroupsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const GROUP_A = "11111111-1111-4111-8111-111111111111";
const GROUP_B = "22222222-2222-4222-8222-222222222222";
const PERSON = "33333333-3333-4333-8333-333333333333";
const POLICY = "44444444-4444-4444-8444-444444444444";
const LICENCE_POLICY = "55555555-5555-4555-8555-555555555555";

const GROUPS = {
  data: [
    { id: GROUP_A, name: "group-a", description: "", people: [PERSON], groups: [], policies: [] },
    { id: GROUP_B, name: "group-b", description: "", people: [], groups: [{ id: GROUP_A, name: "group-a", direct: true }], policies: [] },
  ],
};
const POLICIES = {
  data: [
    { id: POLICY, name: "policy-a", description: "", abilities: [], members: 0, groups: 0 },
    { id: LICENCE_POLICY, name: "policy-b", description: "", abilities: [], requiredLicenceType: "admin", members: 0, groups: 0 },
  ],
};
const MEMBERS = {
  data: [{ id: PERSON, displayName: "User A", email: "user-a@example.test", status: "ACTIVE", since: "2026-10-02T10:00:00Z", policies: [] }],
};

function api(extra: Record<string, () => Response> = {}) {
  return fakeApi({
    "GET /api/v1/groups": () => json(GROUPS),
    "GET /api/v1/access-policies": () => json(POLICIES),
    "GET /api/v1/members": () => json(MEMBERS),
    ...extra,
  });
}

describe("GroupsPanel", () => {
  it("lists the groups with their people, nested groups and policies as the API reports them", async () => {
    api();
    render(<GroupsPanel />);

    const groups = await screen.findAllByTestId("group");
    expect(groups).toHaveLength(2);
    expect(within(groups[0] as HTMLElement).getByTestId("group-people")).toHaveTextContent("User A");
    expect(within(groups[1] as HTMLElement).getByTestId("group-groups")).toHaveTextContent("group-a");
  });

  it("creates a group and shows the API's notice", async () => {
    const calls = api({ "POST /api/v1/groups": () => json({ data: GROUPS.data[0] }, 201) });
    render(<GroupsPanel />);
    await screen.findAllByTestId("group");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "group-c" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the group" }));

    expect(await screen.findByTestId("groups-notice")).toHaveTextContent("The group was created.");
    expect(calls.find((call) => call.key === "POST /api/v1/groups")?.body).toBe(
      JSON.stringify({ name: "group-c", description: "" }),
    );
  });

  it("puts a group inside another and shows the API's own words when that would make a loop", async () => {
    const calls = api({
      [`POST /api/v1/groups/${GROUP_A}/members`]: () =>
        refusal("CONFLICT", "A group cannot contain itself, directly or through other groups.", 409),
    });
    render(<GroupsPanel />);
    const groups = await screen.findAllByTestId("group");

    fireEvent.change(within(groups[0] as HTMLElement).getByLabelText("Put a group inside"), {
      target: { value: GROUP_B },
    });
    fireEvent.click(within(groups[0] as HTMLElement).getByRole("button", { name: "Put the group inside" }));

    expect(await screen.findByTestId("groups-problem")).toHaveTextContent("cannot contain itself");
    expect(calls.find((call) => call.key === `POST /api/v1/groups/${GROUP_A}/members`)?.body).toBe(
      JSON.stringify({ groupId: GROUP_B }),
    );
  });

  it("gives an access policy to a group and shows the API's words for a policy that needs a licence", async () => {
    api({
      [`POST /api/v1/groups/${GROUP_A}/policies`]: () =>
        refusal("CONFLICT", "An access policy that needs a licence cannot be given to a group.", 409),
    });
    render(<GroupsPanel />);
    const groups = await screen.findAllByTestId("group");
    const first = groups[0] as HTMLElement;

    expect(within(first).getByRole("option", { name: "policy-b (needs a licence)" })).toBeInTheDocument();
    fireEvent.change(within(first).getByLabelText("Give an access policy"), { target: { value: LICENCE_POLICY } });
    fireEvent.click(within(first).getByRole("button", { name: "Give the access policy" }));

    expect(await screen.findByTestId("groups-problem")).toHaveTextContent("needs a licence");
  });

  it("takes a person out of a group", async () => {
    const calls = api({
      [`DELETE /api/v1/groups/${GROUP_A}/members/people/${PERSON}`]: () => json({ data: GROUPS.data[0] }),
    });
    render(<GroupsPanel />);
    const groups = await screen.findAllByTestId("group");

    fireEvent.click(within(within(groups[0] as HTMLElement).getByTestId("group-people")).getByRole("button", { name: "Take out" }));

    expect(await screen.findByTestId("groups-notice")).toHaveTextContent("The person was taken out of the group.");
    expect(calls.map((call) => call.key)).toContain(`DELETE /api/v1/groups/${GROUP_A}/members/people/${PERSON}`);
  });

  it("shows the API's refusal of the page itself, and loads the same under strict mode", async () => {
    api({ "GET /api/v1/groups": () => refusal("FORBIDDEN", "You are not allowed to do this.", 403) });
    render(
      <StrictMode>
        <GroupsPanel />
      </StrictMode>,
    );

    expect(await screen.findByTestId("groups-failed")).toHaveTextContent("not allowed");
    await waitFor(() => expect(screen.queryByText("Loading…")).toBeNull());
  });
});
