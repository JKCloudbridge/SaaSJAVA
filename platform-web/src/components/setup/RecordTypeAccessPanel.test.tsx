import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordTypeAccessPanel } from "./RecordTypeAccessPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const BASE = "/api/v1/metadata/objects/Deal__c";
const SALES = "0198a1b2-0000-7000-8000-000000000001";
const SUPPORT = "0198a1b2-0000-7000-8000-000000000002";
const GONE = "0198a1b2-0000-7000-8000-000000000003";

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0, fields: [],
};
const OPEN = { apiName: "Open", label: "Open deal" };
const WON = { apiName: "Won", label: "Won deal" };
const SALES_OPEN = { key: `${SALES}~Open`, profileId: SALES, profileName: "Sales", recordType: "Open", defaultForProfile: false, version: 2 };
const SALES_WON = { key: `${SALES}~Won`, profileId: SALES, profileName: "Sales", recordType: "Won", defaultForProfile: true, version: 1 };
const ORPHAN = { key: `${GONE}~Won`, profileId: GONE, recordType: "Won", defaultForProfile: false, version: 1 };

function answers(assignments: unknown[], extra: Record<string, () => Response> = {}) {
  return {
    [`GET ${BASE}/record-type-assignments`]: () => json({ data: assignments }),
    "GET /api/v1/profiles": () => json({ data: [{ id: SALES, name: "Sales" }, { id: SUPPORT, name: "Support" }] }),
    ...extra,
  };
}

function panel(recordType: unknown) {
  return <RecordTypeAccessPanel object={DEAL as never} recordType={recordType as never} refreshToken={0} />;
}

describe("RecordTypeAccessPanel (a section of the record type's page)", () => {
  it("shows a row per profile for this record type only, with the default, under React strict mode", async () => {
    fakeApi(answers([SALES_OPEN, SALES_WON]));

    render(<StrictMode>{panel(WON)}</StrictMode>);

    const rows = await screen.findAllByTestId("record-type-access-row");
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByLabelText("Sales may use Won deal")).toBeChecked();
    expect(within(rows[0] as HTMLElement).getByText("Default")).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByLabelText("Support may use Won deal")).not.toBeChecked();
    // The other record type's choices are not on this page.
    expect(screen.queryByLabelText("Sales may use Open deal")).toBeNull();
    expect(screen.getByText(/Open to the ticked profiles only/)).toBeInTheDocument();
  });

  it("says a record type nobody is ticked for is open to every profile", async () => {
    fakeApi(answers([SALES_OPEN]));
    render(panel(WON));

    await screen.findAllByTestId("record-type-access-row");

    expect(screen.getByText(/every profile may use this record type/)).toBeInTheDocument();
  });

  it("lets a profile use the record type, and stop using it", async () => {
    const calls = fakeApi(
      answers([SALES_OPEN], {
        [`POST ${BASE}/record-types/Open/profiles`]: () => json({ data: SALES_OPEN }, 201),
        [`DELETE ${BASE}/record-types/Open/profiles/${SALES}`]: () => new Response(null, { status: 204 }),
      }),
    );
    render(panel(OPEN));
    const rows = await screen.findAllByTestId("record-type-access-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByLabelText("Support may use Open deal"));
    expect(await screen.findByTestId("record-type-access-notice")).toHaveTextContent("Profile Support may use Open deal.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-types/Open/profiles`)?.body ?? "{}");
    expect(sent).toEqual({ profileId: SUPPORT, recordType: "Open" });

    fireEvent.click(
      within((await screen.findAllByTestId("record-type-access-row"))[0] as HTMLElement).getByLabelText("Sales may use Open deal"),
    );
    expect(await screen.findByText("Sales may no longer use Open deal.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === `DELETE ${BASE}/record-types/Open/profiles/${SALES}`)).toBe(true);
  });

  it("makes the record type the default of a profile with the version it read", async () => {
    const calls = fakeApi(
      answers([SALES_OPEN, SALES_WON], {
        [`PUT ${BASE}/record-types/Open/profiles/${SALES}`]: () => json({ data: SALES_OPEN }),
      }),
    );
    render(panel(OPEN));
    await screen.findAllByTestId("record-type-access-row");

    fireEvent.click(screen.getByRole("button", { name: "Make Open deal the default of Sales" }));

    expect(await screen.findByTestId("record-type-access-notice")).toHaveTextContent("Open deal is now the default of Sales.");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT ${BASE}/record-types/Open/profiles/${SALES}`)?.body ?? "{}");
    expect(sent).toEqual({ defaultForProfile: true, version: 2 });
  });

  it("lists the assignment of a deleted profile with a way to remove it", async () => {
    const calls = fakeApi(
      answers([ORPHAN], { [`DELETE ${BASE}/record-types/Won/profiles/${GONE}`]: () => new Response(null, { status: 204 }) }),
    );
    render(panel(WON));

    const orphan = await screen.findByTestId("record-type-access-orphan");
    expect(orphan).toHaveTextContent("still counts as a restriction");
    fireEvent.click(within(orphan).getByRole("button", { name: "Remove it" }));

    await screen.findByTestId("record-type-access-notice");
    expect(calls.some((call) => call.key === `DELETE ${BASE}/record-types/Won/profiles/${GONE}`)).toBe(true);
  });

  it("records the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers([], { "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(panel(OPEN));
    const rows = await screen.findAllByTestId("record-type-access-row");

    fireEvent.click(within(rows[1] as HTMLElement).getByLabelText("Support may use Open deal"));

    expect(await screen.findByTestId("record-type-access-notice")).toHaveTextContent("goes live when the set is published");
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({
      kind: "CREATE_RECORD_TYPE_ASSIGNMENT",
      objectApiName: "Deal__c",
      createRecordTypeAssignment: { profileId: SUPPORT, recordType: "Open" },
    });
  });

  it("shows the API's words when the profiles cannot be read", async () => {
    fakeApi({
      [`GET ${BASE}/record-type-assignments`]: () => json({ data: [] }),
      "GET /api/v1/profiles": () =>
        json({ error: { code: "FORBIDDEN", message: "You are not allowed to perform this action." } }, 403),
    });
    render(panel(OPEN));

    expect(await screen.findByTestId("record-type-access-failed")).toHaveTextContent("You are not allowed");
  });
});
