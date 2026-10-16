import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordPageAssignmentsPanel } from "./RecordPageAssignmentsPanel";

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
const PROFILE = "0198a1b2-0000-7000-8000-000000000001";

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0, fields: [],
};
const DEFAULT = { key: "default", recordPage: "Overview__c", version: 1 };
const FOR_PROFILE = {
  key: `profile~${PROFILE}`, profileId: PROFILE, profileName: "Sales", recordPage: "Sales__c", recordType: "Open", version: 3,
};

function answers(assignments: unknown[], extra: Record<string, () => Response> = {}) {
  return {
    [`GET ${BASE}/record-page-assignments`]: () => json({ data: assignments }),
    [`GET ${BASE}/record-pages`]: () =>
      json({ data: [{ apiName: "Overview__c", label: "Overview" }, { apiName: "Sales__c", label: "Sales page" }] }),
    [`GET ${BASE}/record-types`]: () => json({ data: [{ apiName: "Open", label: "Open deal" }] }),
    "GET /api/v1/profiles": () => json({ data: [{ id: PROFILE, name: "Sales" }] }),
    ...extra,
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

describe("RecordPageAssignmentsPanel", () => {
  it("lists the choices with who they are for, under React strict mode", async () => {
    fakeApi(answers([DEFAULT, FOR_PROFILE]));

    render(
      <StrictMode>
        <RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("record-page-assignment-row");
    expect(rows[0]).toHaveTextContent("Everyone (the default)");
    expect(rows[0]).toHaveTextContent("Overview__c");
    expect(rows[1]).toHaveTextContent("Sales");
    expect(rows[1]).toHaveTextContent("Open");
    expect(rows[1]).toHaveTextContent("Sales__c");
  });

  it("adds the choice for a record type alone, which a record type cannot hold itself", async () => {
    const calls = fakeApi(answers([], { [`POST ${BASE}/record-page-assignments`]: () => json({ data: DEFAULT }, 201) }));
    render(<RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />);
    await screen.findByText(/No choices yet/);
    const add = form("Choose a record page");

    expect(add.getByRole("button", { name: "Add the choice" })).toBeDisabled();
    fireEvent.change(add.getByLabelText("Record type"), { target: { value: "Open" } });
    fireEvent.change(add.getByLabelText("Record page"), { target: { value: "Sales__c" } });
    fireEvent.click(add.getByRole("button", { name: "Add the choice" }));

    expect(await screen.findByTestId("record-page-assignments-notice")).toHaveTextContent("The choice was added.");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-page-assignments`)?.body ?? "{}");
    expect(sent).toEqual({ recordType: "Open", recordPage: "Sales__c" });
  });

  it("adds the choice for a profile with a record type", async () => {
    const calls = fakeApi(answers([], { [`POST ${BASE}/record-page-assignments`]: () => json({ data: FOR_PROFILE }, 201) }));
    render(<RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />);
    await screen.findByText(/No choices yet/);
    const add = form("Choose a record page");

    fireEvent.change(add.getByLabelText("For"), { target: { value: PROFILE } });
    fireEvent.change(add.getByLabelText("Record type"), { target: { value: "Open" } });
    fireEvent.change(add.getByLabelText("Record page"), { target: { value: "Sales__c" } });
    fireEvent.click(add.getByRole("button", { name: "Add the choice" }));

    await screen.findByTestId("record-page-assignments-notice");
    const sent = JSON.parse(calls.find((call) => call.key === `POST ${BASE}/record-page-assignments`)?.body ?? "{}");
    expect(sent).toEqual({ profileId: PROFILE, recordType: "Open", recordPage: "Sales__c" });
  });

  it("changes a choice with the version it read, and keeps who it is for fixed", async () => {
    const calls = fakeApi(
      answers([FOR_PROFILE], { [`PUT ${BASE}/record-page-assignments/profile~${PROFILE}`]: () => json({ data: FOR_PROFILE }) }),
    );
    render(<RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />);
    const row = await screen.findByTestId("record-page-assignment-row");

    fireEvent.click(within(row).getByRole("button", { name: "Change" }));
    const change = form("Change the choice");
    expect(change.queryByLabelText("For")).toBeNull();
    fireEvent.change(change.getByLabelText("Record page"), { target: { value: "Overview__c" } });
    fireEvent.click(change.getByRole("button", { name: "Save the choice" }));

    expect(await screen.findByTestId("record-page-assignments-notice")).toHaveTextContent("The choice was saved.");
    const sent = JSON.parse(
      calls.find((call) => call.key === `PUT ${BASE}/record-page-assignments/profile~${PROFILE}`)?.body ?? "{}",
    );
    expect(sent).toEqual({ recordPage: "Overview__c", version: 3 });
  });

  it("removes a choice into the chosen change set", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi(answers([DEFAULT], { "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201) }));
    render(<RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />);
    const row = await screen.findByTestId("record-page-assignment-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("record-page-assignments-notice")).toHaveTextContent("goes live when the set is published");
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_RECORD_PAGE_ASSIGNMENT", objectApiName: "Deal__c", itemApiName: "default" });
  });

  it("asks the API which page a person would get and says which rule decided", async () => {
    fakeApi(
      answers([DEFAULT], {
        [`GET ${BASE}/record-page-resolution`]: () => json({ data: { recordPage: "Sales__c", rule: "RECORD_TYPE" } }),
      }),
    );
    render(<RecordPageAssignmentsPanel object={DEAL} refreshToken={0} />);
    await screen.findByTestId("record-page-assignment-row");

    fireEvent.change(form("Which record page would a person get").getByLabelText("Record type"), { target: { value: "Open" } });
    fireEvent.click(form("Which record page would a person get").getByRole("button", { name: "Find out" }));

    expect(await screen.findByTestId("record-page-answer")).toHaveTextContent(
      "The record page is Sales__c: the choice for this record type.",
    );
  });
});
