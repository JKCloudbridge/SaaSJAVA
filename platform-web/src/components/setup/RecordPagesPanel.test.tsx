import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { RecordPagesPanel } from "./RecordPagesPanel";

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

function field(apiName: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type: "TEXT", required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [field("id", { kind: "SYSTEM", editable: false }), field("code__c", { required: true }), field("stage__c")],
};
const CONTACT = {
  ...DEAL,
  apiName: "Contact__c",
  label: "Contact",
  pluralLabel: "Contacts",
  fields: [field("id", { kind: "SYSTEM", editable: false }), field("surname__c")],
};
const LAYOUT = {
  apiName: "Details__c", objectApiName: "Deal__c", label: "Details", description: "", version: 0,
  definition: { sections: [{ label: "Main", fields: [{ field: "code__c" }] }] },
};
const PAGE = {
  apiName: "Overview__c", objectApiName: "Deal__c", label: "Overview", description: "", version: 5,
  definition: {
    layout: "Details__c",
    components: [
      { key: "head", type: "HEADER", titleField: "code__c", highlightFields: ["stage__c"] },
      { key: "details", type: "DETAILS", visibleWhen: { field: "stage__c", operator: "EQUALS", value: "Open" } },
      {
        key: "contacts", type: "RELATED_LIST", object: "Contact__c", field: "deal__c", columns: ["surname__c"], limit: 5,
        visibleWhen: { all: [{ ability: "members.view" }, { any: [{ ability: "members.invite" }] }] },
      },
    ],
    actions: [{ action: "EDIT" }],
  },
};

function answers(pages: unknown[]) {
  return {
    [`GET ${BASE}/record-pages`]: () => json({ data: pages }),
    [`GET ${BASE}/layouts`]: () => json({ data: [LAYOUT] }),
    "GET /api/v1/metadata/objects/Contact__c": () => json({ data: CONTACT }),
  };
}

describe("RecordPagesPanel", () => {
  it("lists the pages and previews the structure with the rules in words, under React strict mode", async () => {
    fakeApi(answers([PAGE]));

    render(
      <StrictMode>
        <RecordPagesPanel object={DEAL} onChanged={vi.fn()} />
      </StrictMode>,
    );

    const row = await screen.findByTestId("record-page-row");
    expect(row).toHaveTextContent("Overview");
    fireEvent.click(within(row).getByRole("button", { name: "Preview" }));

    const preview = await screen.findByTestId("record-page-preview");
    const parts = within(preview).getAllByTestId("preview-component");
    expect(parts.map((part) => part.getAttribute("data-type"))).toEqual(["HEADER", "DETAILS", "RELATED_LIST"]);
    expect(parts[1]).toHaveTextContent("Shown when stage__c is Open.");
    expect(parts[2]).toHaveTextContent("(the person holds the ability members.view and (the person holds the ability members.invite))");
    expect(within(preview).getByTestId("layout-preview")).toBeInTheDocument();
    expect(within(preview).getByTestId("preview-actions")).toHaveTextContent("EDIT");
  });

  it("opens the builder for a new page and for an existing one", async () => {
    fakeApi(answers([PAGE]));
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-page-row");

    expect(screen.getByRole("link", { name: "New record page" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/record-pages/new",
    );
    expect(within(row).getByRole("link", { name: "Overview" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/record-pages/Overview__c",
    );
    expect(within(row).getByRole("link", { name: "Build" })).toHaveAttribute(
      "href",
      "/setup/objects/Deal__c/record-pages/Overview__c",
    );
  });

  it("removes a page live", async () => {
    const calls = fakeApi({
      ...answers([PAGE]),
      [`DELETE ${BASE}/record-pages/Overview__c`]: () => new Response(null, { status: 204 }),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-page-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("The record page was removed.");
    expect(calls.some((call) => call.key === `DELETE ${BASE}/record-pages/Overview__c`)).toBe(true);
  });

  it("removes a page into a change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers([PAGE]),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<RecordPagesPanel object={DEAL} onChanged={vi.fn()} />);
    const row = await screen.findByTestId("record-page-row");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("record-pages-notice")).toHaveTextContent("goes live when the set is published");
    const sent = JSON.parse(calls.find((call) => call.key.endsWith("/changes"))?.body ?? "{}");
    expect(sent).toEqual({ kind: "DELETE_RECORD_PAGE", objectApiName: "Deal__c", itemApiName: "Overview__c" });
  });

  it("says that a platform object has no record pages", () => {
    fakeApi({});
    render(<RecordPagesPanel object={{ ...DEAL, managedBy: "identity" }} onChanged={vi.fn()} />);

    expect(screen.getByText(/belong to the platform, so it has no record pages/)).toBeInTheDocument();
  });
});
