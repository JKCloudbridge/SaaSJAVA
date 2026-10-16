import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { deal, field } from "@/test/records";
import { SharePanel } from "./SharePanel";

const PERSON = "22222222-2222-2222-2222-222222222222";
const GROUP = "33333333-3333-3333-3333-333333333333";
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

const share = (granteeType: string, granteeId: string, accessLevel = "READ") => ({
  id: `s-${granteeId}`,
  granteeType,
  granteeId,
  accessLevel,
  sharedAt: "2030-01-02T03:04:05Z",
  sharedBy: "11111111-1111-1111-1111-111111111111",
});

function open(more: Record<string, Handler> = {}) {
  const calls: Call[] = fakeApi({
    [SHARES]: () => json({ data: [share("USER", PERSON, "READ_WRITE"), share("GROUP", GROUP)] }),
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
    expect(screen.getByText("A group")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Read and change" })).toBeInTheDocument();
    expect(screen.queryByText(new RegExp(PERSON))).toBeNull();
    expect(screen.queryByText(new RegExp(GROUP))).toBeNull();
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
