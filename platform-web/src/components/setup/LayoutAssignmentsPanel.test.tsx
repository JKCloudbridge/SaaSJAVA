import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { LayoutAssignmentsPanel } from "./LayoutAssignmentsPanel";

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
const OBJECT = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0, fields: [],
};
const PROFILE_ID = "0198a1b2-0000-7000-8000-000000000001";
const GONE_ID = "0198a1b2-0000-7000-8000-000000000002";

function answers(assignments: unknown[]) {
  return {
    [`GET ${BASE}/layout-assignments`]: () => json({ data: assignments }),
    [`GET ${BASE}/layouts`]: () =>
      json({ data: [{ apiName: "Standard__c", label: "Standard" }, { apiName: "Special__c", label: "Special" }] }),
    [`GET ${BASE}/record-types`]: () => json({ data: [{ apiName: "Renewal__c", label: "Renewal" }] }),
    "GET /api/v1/profiles": () => json({ data: [{ id: PROFILE_ID, name: "Sales" }] }),
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("LayoutAssignmentsPanel", () => {
  it("lists the choices and shows a deleted profile as ignored, under React strict mode", async () => {
    fakeApi(
      answers([
        { key: "default", layout: "Standard__c", version: 0 },
        { key: PROFILE_ID, profileId: PROFILE_ID, profileName: "Sales", layout: "Special__c", version: 1 },
        { key: `${GONE_ID}~Renewal__c`, profileId: GONE_ID, recordType: "Renewal__c", layout: "Special__c", version: 0 },
      ]),
    );

    render(
      <StrictMode>
        <LayoutAssignmentsPanel object={OBJECT} refreshToken={0} />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("layout-assignment-row");
    expect(rows[0]).toHaveTextContent("Everyone (the default)");
    expect(rows[1]).toHaveTextContent("Sales");
    expect(rows[2]).toHaveTextContent("A profile that was deleted (ignored)");
    expect(rows[2]).toHaveTextContent("Renewal__c");
  });

  it("adds the choice for a profile and a record type", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`POST ${BASE}/layout-assignments`]: () => json({ data: { key: "k", layout: "Special__c", version: 0 } }, 201),
    });
    render(<LayoutAssignmentsPanel object={OBJECT} refreshToken={0} />);
    await screen.findByText("No choices yet.");

    const add = form("Choose a layout");
    fireEvent.change(add.getByLabelText("For"), { target: { value: PROFILE_ID } });
    fireEvent.change(add.getByLabelText("Record type"), { target: { value: "Renewal__c" } });
    fireEvent.change(add.getByLabelText("Page layout"), { target: { value: "Special__c" } });
    fireEvent.click(add.getByRole("button", { name: "Add the choice" }));

    expect(await screen.findByTestId("layout-assignments-notice")).toHaveTextContent("The choice was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/layout-assignments`)?.body ?? "{}");
    expect(sent).toEqual({ profileId: PROFILE_ID, recordType: "Renewal__c", layout: "Special__c" });
  });

  it("adds the default layout when no profile is chosen and never sends a record type with it", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`POST ${BASE}/layout-assignments`]: () => json({ data: { key: "default", layout: "Standard__c", version: 0 } }, 201),
    });
    render(<LayoutAssignmentsPanel object={OBJECT} refreshToken={0} />);
    await screen.findByText("No choices yet.");

    fireEvent.change(form("Choose a layout").getByLabelText("Page layout"), { target: { value: "Standard__c" } });
    fireEvent.click(form("Choose a layout").getByRole("button", { name: "Add the choice" }));

    await screen.findByTestId("layout-assignments-notice");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/layout-assignments`)?.body ?? "{}");
    expect(sent).toEqual({ layout: "Standard__c" });
  });

  it("asks the API which layout a profile gets and says which rule decided", async () => {
    const calls = fakeApi({
      ...answers([]),
      [`GET ${BASE}/layout-resolution`]: () => json({ data: { layout: "Special__c", rule: "PROFILE" } }),
    });
    render(<LayoutAssignmentsPanel object={OBJECT} refreshToken={0} />);
    await screen.findByText("No choices yet.");

    fireEvent.change(form("Which layout would a person get").getByLabelText("Profile"), { target: { value: PROFILE_ID } });
    fireEvent.click(form("Which layout would a person get").getByRole("button", { name: "Find out" }));

    expect(await screen.findByTestId("layout-answer")).toHaveTextContent("The layout is Special__c: the choice for this profile.");
    expect(calls.some((call) => call.key === `GET ${BASE}/layout-resolution`)).toBe(true);
  });

  it("removes a choice and shows the words of the API when it refuses", async () => {
    fakeApi({
      ...answers([{ key: "default", layout: "Standard__c", version: 0 }]),
      [`DELETE ${BASE}/layout-assignments/default`]: () =>
        json({ error: { code: "CONFLICT", message: "Something still uses it." } }, 409),
    });
    render(<LayoutAssignmentsPanel object={OBJECT} refreshToken={0} />);
    const row = await screen.findByTestId("layout-assignment-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("layout-assignments-problem")).toHaveTextContent("Something still uses it.");
  });
});
