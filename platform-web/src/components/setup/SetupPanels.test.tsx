import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { AccessPoliciesPanel } from "./AccessPoliciesPanel";
import { ProfilesPanel } from "./ProfilesPanel";
import { RolesPanel } from "./RolesPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const ABILITIES = {
  data: [
    { key: "members.invite", name: "Invite members", description: "Create a new member." },
    { key: "members.view", name: "See members", description: "See the list of members." },
    { key: "access.manage", name: "Manage access", description: "Manage profiles, policies and roles." },
  ],
};
const TYPES = {
  data: [
    { key: "admin", name: "Administrator", kind: "SEAT" },
    { key: "user", name: "User", kind: "SEAT" },
    { key: "addon-a", name: "Add-on A", kind: "ADD_ON" },
  ],
};
const ADMIN_PROFILE = {
  id: "11111111-1111-4111-8111-111111111111",
  name: "Organization administrator",
  description: "Every ability.",
  licenceType: "admin",
  abilities: ["access.manage", "members.invite", "members.view"],
  system: true,
  fullAccess: true,
  defaultProfile: false,
  members: 1,
};
const MEMBER_PROFILE = {
  id: "22222222-2222-4222-8222-222222222222",
  name: "Member",
  description: "",
  licenceType: "user",
  abilities: [],
  system: true,
  fullAccess: false,
  defaultProfile: true,
  members: 2,
};
const CUSTOM_PROFILE = {
  id: "33333333-3333-4333-8333-333333333333",
  name: "profile-a",
  description: "",
  licenceType: "user",
  abilities: ["members.invite"],
  system: false,
  fullAccess: false,
  defaultProfile: false,
  members: 0,
};

describe("ProfilesPanel", () => {
  it("lists the profiles as the API reports them and offers only what the data allows", async () => {
    fakeApi({
      "GET /api/v1/profiles": () => json({ data: [ADMIN_PROFILE, MEMBER_PROFILE, CUSTOM_PROFILE] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
    });
    render(<ProfilesPanel />);

    const rows = await screen.findAllByTestId("profile-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Organization administrator (system)");
    expect(within(rows[0] as HTMLElement).queryByRole("button", { name: "Change" })).toBeNull();
    expect(within(rows[0] as HTMLElement).queryByRole("button", { name: "Remove" })).toBeNull();
    expect(rows[1]).toHaveTextContent("(default for new members)");
    expect(within(rows[1] as HTMLElement).queryByRole("button", { name: "Remove" })).toBeNull();
    expect(rows[2]).toHaveTextContent("Invite members");
    expect(within(rows[2] as HTMLElement).getByRole("button", { name: "Remove" })).toBeInTheDocument();
  });

  it("creates a profile with the chosen licence type and abilities", async () => {
    const calls = fakeApi({
      "GET /api/v1/profiles": () => json({ data: [MEMBER_PROFILE] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
      "POST /api/v1/profiles": () => json({ data: CUSTOM_PROFILE }, 201),
    });
    render(<ProfilesPanel />);
    await screen.findAllByTestId("profile-row");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "profile-a" } });
    fireEvent.change(screen.getByLabelText("Licence type"), { target: { value: "admin" } });
    fireEvent.click(screen.getByLabelText(/Invite members/));
    fireEvent.click(screen.getByRole("button", { name: "Create the profile" }));

    expect(await screen.findByTestId("profiles-notice")).toHaveTextContent("The profile was created.");
    expect(calls.find((call) => call.key === "POST /api/v1/profiles")?.body).toBe(
      JSON.stringify({ name: "profile-a", description: "", licenceType: "admin", abilities: ["members.invite"] }),
    );
  });

  it("shows the API's own words when a profile cannot be removed", async () => {
    fakeApi({
      "GET /api/v1/profiles": () => json({ data: [MEMBER_PROFILE, CUSTOM_PROFILE] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
      "DELETE /api/v1/profiles/33333333-3333-4333-8333-333333333333": () =>
        refusal("CONFLICT", "Members still hold this profile. Give them another profile first.", 409),
    });
    render(<ProfilesPanel />);
    const rows = await screen.findAllByTestId("profile-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("profiles-problem")).toHaveTextContent(
      "Members still hold this profile. Give them another profile first.",
    );
  });

  it("says what the API answered when the caller may not manage access", async () => {
    fakeApi({
      "GET /api/v1/profiles": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
      "GET /api/v1/abilities": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
      "GET /api/v1/licence-types": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<ProfilesPanel />);

    expect(await screen.findByTestId("profiles-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});

describe("AccessPoliciesPanel", () => {
  const POLICY = {
    id: "44444444-4444-4444-8444-444444444444",
    name: "policy-a",
    description: "",
    abilities: ["members.view"],
    requiredLicenceType: "admin",
    members: 1,
  };
  const POOLS = { data: [{ licenceType: "admin", name: "Administrator", quantity: 3, assigned: 2, available: 1 }] };

  it("lists policies with the numbers of the licence they need", async () => {
    fakeApi({
      "GET /api/v1/access-policies": () => json({ data: [POLICY] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
      "GET /api/v1/licences": () => json(POOLS),
    });
    render(<AccessPoliciesPanel />);

    const row = await screen.findByTestId("policy-row");
    expect(row).toHaveTextContent("See members");
    expect(screen.getByTestId("policy-licence")).toHaveTextContent("admin (1 of 3 free)");
  });

  it("creates a licence-bound policy and shows a refusal in the API's words", async () => {
    const calls = fakeApi({
      "GET /api/v1/access-policies": () => json({ data: [] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
      "GET /api/v1/licences": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
      "POST /api/v1/access-policies": () => refusal("VALIDATION_ERROR", "Is already used by another access policy.", 400),
    });
    render(<AccessPoliciesPanel />);
    await screen.findByText("There are no access policies yet.");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "policy-a" } });
    fireEvent.change(screen.getByLabelText("Needs a licence of type"), { target: { value: "admin" } });
    fireEvent.click(screen.getByLabelText(/See members/));
    fireEvent.click(screen.getByRole("button", { name: "Create the access policy" }));

    expect(await screen.findByTestId("policies-problem")).toHaveTextContent("Is already used by another access policy.");
    expect(calls.find((call) => call.key === "POST /api/v1/access-policies")?.body).toBe(
      JSON.stringify({ name: "policy-a", description: "", abilities: ["members.view"], requiredLicenceType: "admin" }),
    );
  });
});

describe("RolesPanel", () => {
  const TOP = { id: "55555555-5555-4555-8555-555555555555", name: "role-a", description: "", members: 0 };
  const BELOW = {
    id: "66666666-6666-4666-8666-666666666666",
    name: "role-b",
    description: "",
    parentId: TOP.id,
    members: 1,
  };

  it("shows the tree with sub-roles under their parent", async () => {
    fakeApi({ "GET /api/v1/roles": () => json({ data: [BELOW, TOP] }) });
    render(<RolesPanel />);

    const rows = await screen.findAllByTestId("role-row");
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining("role-a"),
      expect.stringContaining("role-b"),
    ]);
    expect(screen.getByText(/gives no ability/)).toBeInTheDocument();
  });

  it("repeats the API's words when a move would make a loop", async () => {
    fakeApi({
      "GET /api/v1/roles": () => json({ data: [TOP, BELOW] }),
      "PUT /api/v1/roles/55555555-5555-4555-8555-555555555555": () =>
        refusal("CONFLICT", "A role cannot be placed below itself or below one of its own sub-roles.", 409),
    });
    render(<RolesPanel />);
    const rows = await screen.findAllByTestId("role-row");

    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Change" }));
    fireEvent.change(screen.getByLabelText("Works under"), { target: { value: BELOW.id } });
    fireEvent.click(screen.getByRole("button", { name: "Save the role" }));

    await waitFor(() =>
      expect(screen.getByTestId("roles-problem")).toHaveTextContent(
        "A role cannot be placed below itself or below one of its own sub-roles.",
      ),
    );
  });
});

describe("the setup pages under React strict mode (effects run twice in development)", () => {
  it("load their lists and still work once", async () => {
    const calls = fakeApi({
      "GET /api/v1/profiles": () => json({ data: [MEMBER_PROFILE] }),
      "GET /api/v1/abilities": () => json(ABILITIES),
      "GET /api/v1/licence-types": () => json(TYPES),
      "GET /api/v1/roles": () => json({ data: [] }),
      "POST /api/v1/profiles": () => json({ data: CUSTOM_PROFILE }, 201),
    });
    render(
      <StrictMode>
        <ProfilesPanel />
      </StrictMode>,
    );
    await screen.findAllByTestId("profile-row");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "profile-a" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the profile" }));

    expect(await screen.findByTestId("profiles-notice")).toHaveTextContent("The profile was created.");
    expect(calls.filter((call) => call.key === "POST /api/v1/profiles")).toHaveLength(1);
  });

  it("the roles page also loads under strict mode", async () => {
    fakeApi({ "GET /api/v1/roles": () => json({ data: [] }) });
    render(
      <StrictMode>
        <RolesPanel />
      </StrictMode>,
    );

    expect(await screen.findByText("There are no roles yet.")).toBeInTheDocument();
  });
});
