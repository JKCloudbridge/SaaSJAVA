import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { deal, field } from "@/test/records";
import { SharePanel } from "./SharePanel";

const PERSON = "22222222-2222-2222-2222-222222222222";
const GROUP = "33333333-3333-3333-3333-333333333333";
const ROLE = "44444444-4444-4444-4444-444444444444";
const OTHER_ROLE = "55555555-5555-5555-5555-555555555555";
const TARGETS = "GET /api/v1/objects/Deal__c/records/r1/share-targets";
const SHARES = "GET /api/v1/objects/Deal__c/records/r1/shares";
const SHARE = "PUT /api/v1/objects/Deal__c/records/r1/shares";
const UNSHARE = `DELETE /api/v1/objects/Deal__c/records/r1/shares/${PERSON}`;
const OWNER = "POST /api/v1/objects/Deal__c/records/r1/owner";
const LABELS = "POST /api/v1/objects/Deal__c/lookups/ownerId/labels";
const OPTIONS = "POST /api/v1/objects/Deal__c/lookups/ownerId/options";
const OWNER_FIELD = field("ownerId", "LOOKUP", {
  label: "Owner",
  targetObject: "User",
  targetLabel: "User",
  kind: "SYSTEM",
});

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const share = (granteeType: string, granteeId: string, accessLevel = "READ", granteeLabel?: string) => ({
  id: `s-${granteeId}`,
  granteeType,
  granteeId,
  accessLevel,
  ...(granteeLabel ? { granteeLabel } : {}),
  sharedAt: "2030-01-02T03:04:05Z",
  sharedBy: "11111111-1111-1111-1111-111111111111",
});

function open(more: Record<string, Handler> = {}) {
  const calls: Call[] = fakeApi({
    [SHARES]: () => json({ data: [share("USER", PERSON, "READ_WRITE"), share("GROUP", GROUP, "READ", "group-a")] }),
    [TARGETS]: (request) =>
      json({
        data:
          new URL(request.url).searchParams.get("kind") === "GROUP"
            ? [{ id: GROUP, label: "group-a" }]
            : [
                { id: ROLE, label: "role-a" },
                { id: OTHER_ROLE, label: "role-b" },
              ],
      }),
    [LABELS]: () => json({ data: { labels: { [PERSON]: "User A" } } }),
    [OPTIONS]: () => json({ data: [{ id: PERSON, label: "User A" }] }),
    ...more,
  });
  const onOwnerChanged = vi.fn();
  const onClose = vi.fn();
  render(
    <SharePanel
      objectApiName="Deal__c"
      record={deal("r1")}
      ownerField={OWNER_FIELD}
      onClose={onClose}
      onOwnerChanged={onOwnerChanged}
    />,
  );
  return { calls, onOwnerChanged, onClose };
}

describe("who a record is shared with", () => {
  it("names the people it is shared with and never shows an identifier", async () => {
    open();

    expect(await screen.findByText("User A")).toBeInTheDocument();
    expect(screen.getByText("Group: group-a")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Read and change" })).toBeInTheDocument();
    expect(screen.queryByText(new RegExp(PERSON))).toBeNull();
    expect(screen.queryByText(new RegExp(GROUP))).toBeNull();
  });

  it("names each kind of share in words, and says so when a role or a group is gone", async () => {
    open({
      [SHARES]: () =>
        json({
          data: [
            share("ROLE", ROLE, "READ", "role-a"),
            share("ROLE_AND_SUBORDINATES", OTHER_ROLE, "READ_WRITE", "role-b"),
            share("ROLE", "66666666-6666-6666-6666-666666666666"),
            share("GROUP", "77777777-7777-7777-7777-777777777777"),
          ],
        }),
    });

    expect(await screen.findByText("Role: role-a")).toBeInTheDocument();
    expect(screen.getByText("Role and the roles below it: role-b")).toBeInTheDocument();
    expect(screen.getByText("A role that no longer exists")).toBeInTheDocument();
    expect(screen.getByText("A group that no longer exists")).toBeInTheDocument();
    expect(screen.queryByText(new RegExp(ROLE))).toBeNull();
  });

  it("says 'A person' when a name cannot be given", async () => {
    open({ [LABELS]: () => json({ data: { labels: {} } }) });

    expect(await screen.findByText("A person")).toBeInTheDocument();
  });

  it("says the record is not shared when it is not", async () => {
    open({ [SHARES]: () => json({ data: [] }) });

    expect(await screen.findByText("Not shared with anyone beyond the owner.")).toBeInTheDocument();
  });

  it("shares with the person picked, at the level chosen", async () => {
    const { calls } = open({ [SHARE]: () => json({ data: share("USER", PERSON) }) });
    await screen.findByText("User A");

    fireEvent.focus(screen.getByLabelText("Person"));
    fireEvent.click(await screen.findByRole("button", { name: "User A" }));
    fireEvent.change(screen.getByLabelText("Level"), { target: { value: "READ_WRITE" } });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    await waitFor(() => expect(calls.some((call) => call.key === SHARE)).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key === SHARE)?.body ?? "{}")).toEqual({
      granteeType: "USER",
      granteeId: PERSON,
      accessLevel: "READ_WRITE",
    });
  });

  it("offers a person, a group, a role and a role with the roles below it", async () => {
    open();
    await screen.findByText("User A");

    const kinds = within(screen.getByLabelText("Share with")).getAllByRole("option").map((option) => option.textContent);

    expect(kinds).toEqual(["A person", "A group", "A role", "A role and the roles below it"]);
  });

  it("shares with the role picked from the names the server offers, at the level chosen", async () => {
    const { calls } = open({ [SHARE]: () => json({ data: share("ROLE", ROLE) }) });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE" } });
    await screen.findByRole("option", { name: "role-a" });
    fireEvent.change(screen.getByLabelText("A role"), { target: { value: ROLE } });
    fireEvent.change(screen.getByLabelText("Level"), { target: { value: "READ_WRITE" } });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    await waitFor(() => expect(calls.some((call) => call.key === SHARE)).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key === SHARE)?.body ?? "{}")).toEqual({
      granteeType: "ROLE",
      granteeId: ROLE,
      accessLevel: "READ_WRITE",
    });
  });

  it("shares with a role and the roles below it from the same list of roles", async () => {
    const asked: string[] = [];
    const { calls } = open({
      [SHARE]: () => json({ data: share("ROLE_AND_SUBORDINATES", OTHER_ROLE) }),
      [TARGETS]: (request) => {
        asked.push(new URL(request.url).searchParams.get("kind") ?? "");
        return json({ data: [{ id: OTHER_ROLE, label: "role-b" }] });
      },
    });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE_AND_SUBORDINATES" } });
    await screen.findByRole("option", { name: "role-b" });
    fireEvent.change(screen.getByLabelText("A role and the roles below it", { selector: "#share-target" }), {
      target: { value: OTHER_ROLE },
    });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    await waitFor(() => expect(calls.some((call) => call.key === SHARE)).toBe(true));
    expect(asked).toEqual(["ROLE"]);
    expect(JSON.parse(calls.find((call) => call.key === SHARE)?.body ?? "{}")).toEqual({
      granteeType: "ROLE_AND_SUBORDINATES",
      granteeId: OTHER_ROLE,
      accessLevel: "READ",
    });
  });

  it("shares with the group picked from the groups the server offers", async () => {
    const { calls } = open({ [SHARE]: () => json({ data: share("GROUP", GROUP) }) });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "GROUP" } });
    await screen.findByRole("option", { name: "group-a" });
    fireEvent.change(screen.getByLabelText("A group", { selector: "#share-target" }), { target: { value: GROUP } });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    await waitFor(() => expect(calls.some((call) => call.key === SHARE)).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key === SHARE)?.body ?? "{}")).toMatchObject({
      granteeType: "GROUP",
      granteeId: GROUP,
    });
  });

  it("searches the roles by what is typed and sends nothing until a choice is made", async () => {
    const searches: (string | null)[] = [];
    const { calls } = open({
      [TARGETS]: (request) => {
        searches.push(new URL(request.url).searchParams.get("search"));
        return json({ data: [{ id: ROLE, label: "role-a" }] });
      },
    });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE" } });
    await screen.findByRole("option", { name: "role-a" });
    expect(screen.getByRole("button", { name: "Share" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Search roles"), { target: { value: "lea" } });

    await waitFor(() => expect(searches).toContain("lea"));
    expect(calls.some((call) => call.key === SHARE)).toBe(false);
  });

  it("shows the server's words when a role cannot be shared with, and never an identifier", async () => {
    open({ [SHARE]: () => refusal("VALIDATION_ERROR", "Is not a role of this organization.", 400) });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE" } });
    await screen.findByRole("option", { name: "role-a" });
    fireEvent.change(screen.getByLabelText("A role", { selector: "#share-target" }), { target: { value: ROLE } });
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Is not a role of this organization.");
    expect(screen.queryByText(new RegExp(ROLE))).toBeNull();
  });

  it("shows the server's words when the roles to choose from cannot be read", async () => {
    open({ [TARGETS]: () => refusal("FORBIDDEN", "You may not share this record.", 403) });
    await screen.findByText("User A");

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE" } });

    expect(await screen.findByRole("alert")).toHaveTextContent("You may not share this record.");
    expect(await screen.findByRole("option", { name: "None found" })).toBeInTheDocument();
  });

  it("starts again with nothing chosen when the kind changes", async () => {
    open();
    await screen.findByText("User A");
    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "ROLE" } });
    await screen.findByRole("option", { name: "role-a" });
    fireEvent.change(screen.getByLabelText("A role", { selector: "#share-target" }), { target: { value: ROLE } });
    expect(screen.getByRole("button", { name: "Share" })).toBeEnabled();

    fireEvent.change(screen.getByLabelText("Share with"), { target: { value: "GROUP" } });

    expect(screen.getByRole("button", { name: "Share" })).toBeDisabled();
  });

  it("removes a share to a role by the role's identifier", async () => {
    const taken = `DELETE /api/v1/objects/Deal__c/records/r1/shares/${ROLE}`;
    const { calls } = open({
      [SHARES]: () => json({ data: [share("ROLE", ROLE, "READ", "role-a")] }),
      [taken]: () => new Response(null, { status: 204 }),
    });
    await screen.findByText("Role: role-a");

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    await waitFor(() => expect(calls.some((call) => call.key === taken)).toBe(true));
  });

  it("removes a share", async () => {
    const { calls } = open({ [UNSHARE]: () => new Response(null, { status: 204 }) });
    await screen.findByText("User A");

    fireEvent.click(within(screen.getAllByRole("row")[1] as HTMLElement).getByRole("button", { name: "Remove" }));

    await waitFor(() => expect(calls.some((call) => call.key === UNSHARE)).toBe(true));
  });

  it("gives the record to another owner with the version it was read at, and reports the saved record", async () => {
    const saved = deal("r1", {}, { version: 4, ownerId: PERSON });
    const { calls, onOwnerChanged } = open({ [OWNER]: () => json({ data: saved }) });
    await screen.findByText("User A");

    fireEvent.focus(screen.getByLabelText("New owner"));
    fireEvent.click(await screen.findByRole("button", { name: "User A" }));
    fireEvent.click(screen.getByRole("button", { name: "Give to this owner" }));

    await waitFor(() => expect(onOwnerChanged).toHaveBeenCalledWith(saved));
    expect(JSON.parse(calls.find((call) => call.key === OWNER)?.body ?? "{}")).toEqual({
      ownerId: PERSON,
      version: 3,
    });
  });

  it("shows the server's words when sharing is refused", async () => {
    open({ [SHARE]: () => refusal("FORBIDDEN", "You may not share this record.", 403) });
    await screen.findByText("User A");

    fireEvent.focus(screen.getByLabelText("Person"));
    fireEvent.click(await screen.findByRole("button", { name: "User A" }));
    fireEvent.click(screen.getByRole("button", { name: "Share" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("You may not share this record.");
  });

  it("closes", async () => {
    const { onClose } = open();
    await screen.findByText("User A");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(onClose).toHaveBeenCalled();
  });
});
