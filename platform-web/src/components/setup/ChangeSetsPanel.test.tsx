import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ChangeSetsPanel } from "./ChangeSetsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const SET = "/api/v1/metadata/change-sets/set-1";
const DRAFT = {
  id: "set-1", name: "Drop department", description: "Tidy up", status: "DRAFT", changeCount: 1,
  createdAt: "2030-01-31T09:30:00Z", version: 0,
  changes: [{ id: "c-1", position: 1, kind: "DELETE_FIELD", objectApiName: "Deal__c", itemApiName: "department__c" }],
};
const PUBLISHED = { ...DRAFT, id: "set-0", name: "Earlier", status: "PUBLISHED", releaseNumber: 3, changes: [] };
const INVALID = {
  valid: false,
  problems: [
    {
      kind: "DEPENDENCY", position: null, objectApiName: "Deal__c", itemApiName: "department__c",
      dependent: "record type NewBusiness__c of Deal__c",
      message: "record type NewBusiness__c of Deal__c offers field Deal__c.department__c, which does not exist after this change.",
      fields: [],
    },
  ],
  items: [{ action: "REMOVED", kind: "FIELD", objectApiName: "Deal__c", itemApiName: "department__c" }],
  objects: [],
};

function listAnswers(extra: Record<string, () => Response> = {}) {
  return {
    "GET /api/v1/metadata/change-sets": () => json({ data: [{ ...DRAFT, changes: [] }, PUBLISHED] }),
    [`GET ${SET}`]: () => json({ data: DRAFT }),
    ...extra,
  };
}

describe("ChangeSetsPanel", () => {
  it("lists the change sets and opens the first with its changes, under React strict mode", async () => {
    fakeApi(listAnswers());

    render(
      <StrictMode>
        <ChangeSetsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("change-set-row");
    expect(rows).toHaveLength(2);
    expect(rows[1]).toHaveTextContent("release 3");
    const change = await screen.findByTestId("change-row");
    expect(change).toHaveTextContent("Remove field");
    expect(change).toHaveTextContent("department__c");
  });

  it("checks a change set and shows the problem with what depends on it", async () => {
    fakeApi(listAnswers({ [`POST ${SET}/validate`]: () => json({ data: INVALID }) }));
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Check it" }));

    const problems = await screen.findByTestId("report-problems");
    expect(problems).toHaveTextContent("Something still needs it");
    expect(problems).toHaveTextContent("record type NewBusiness__c of Deal__c offers field Deal__c.department__c");
    expect(screen.getByTestId("report-items")).toHaveTextContent("Removed field Deal__c.department__c");
  });

  it("previews a change set with the objects as they would be", async () => {
    fakeApi(
      listAnswers({
        [`POST ${SET}/preview`]: () =>
          json({
            data: {
              valid: true, problems: [], items: [],
              objects: [{ apiName: "Deal__c", label: "Deal", fields: [{ apiName: "id" }, { apiName: "code__c" }] }],
            },
          }),
      }),
    );
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Preview it" }));

    expect(await screen.findByText("Nothing stops this from going ahead.")).toBeInTheDocument();
    expect(screen.getByTestId("report-objects")).toHaveTextContent("Deal Deal__c: 2 fields");
  });

  it("shows the API's own words, every problem and the dependent, when publishing is refused", async () => {
    fakeApi(
      listAnswers({
        [`POST ${SET}/publish`]: () =>
          json(
            {
              error: {
                code: "CONFLICT",
                message: "The change set cannot be published: record type NewBusiness__c of Deal__c offers field.",
                fields: { problems: ["record type NewBusiness__c of Deal__c offers field Deal__c.department__c"] },
              },
            },
            409,
          ),
      }),
    );
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Publish it" }));

    const problem = await screen.findByTestId("change-sets-problem");
    expect(problem).toHaveTextContent("The change set cannot be published");
    expect(problem).toHaveTextContent("Problem: record type NewBusiness__c of Deal__c offers field");
  });

  it("publishes a change set and says so", async () => {
    const calls = fakeApi(listAnswers({ [`POST ${SET}/publish`]: () => json({ data: { ...DRAFT, status: "PUBLISHED" } }) }));
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Publish it" }));

    expect(await screen.findByTestId("change-sets-notice")).toHaveTextContent("The change set was published.");
    expect(calls.some((call) => call.key === `POST ${SET}/publish`)).toBe(true);
  });

  it("discards only after a second click", async () => {
    const calls = fakeApi(listAnswers({ [`DELETE ${SET}`]: () => new Response(null, { status: 204 }) }));
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Discard it" }));
    expect(calls.some((call) => call.key === `DELETE ${SET}`)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, discard it" }));

    expect(await screen.findByTestId("change-sets-notice")).toHaveTextContent("The change set was discarded.");
    expect(calls.some((call) => call.key === `DELETE ${SET}`)).toBe(true);
  });

  it("takes a change out of an open change set", async () => {
    const calls = fakeApi(listAnswers({ [`DELETE ${SET}/changes/c-1`]: () => json({ data: DRAFT }) }));
    render(<ChangeSetsPanel />);
    const change = await screen.findByTestId("change-row");

    fireEvent.click(within(change).getByRole("button", { name: "Take out" }));

    expect(await screen.findByTestId("change-sets-notice")).toHaveTextContent("The change was taken out.");
    expect(calls.some((call) => call.key === `DELETE ${SET}/changes/c-1`)).toBe(true);
  });

  it("starts a change set and shows the API's words when the name is in use", async () => {
    const calls = fakeApi(
      listAnswers({
        "POST /api/v1/metadata/change-sets": () =>
          json({ error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { name: ["An open change set with this name exists already."] } } }, 400),
      }),
    );
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Drop department" } });
    fireEvent.click(screen.getByRole("button", { name: "Start the change set" }));

    expect(await screen.findByTestId("change-sets-problem")).toHaveTextContent("An open change set with this name exists already.");
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets")?.body ?? "{}").name).toBe(
      "Drop department",
    );
  });

  it("chooses the change set to work in, and stops again", async () => {
    fakeApi(listAnswers());
    render(<ChangeSetsPanel />);
    await screen.findByTestId("change-row");

    fireEvent.click(screen.getByRole("button", { name: "Work in it" }));
    expect(window.sessionStorage.getItem("platform.workingChangeSet")).toBe("set-1");
    fireEvent.click(await screen.findByRole("button", { name: "Stop working in it" }));

    expect(window.sessionStorage.getItem("platform.workingChangeSet")).toBeNull();
  });

  it("shows what the API answered when the list cannot be read", async () => {
    fakeApi({ "GET /api/v1/metadata/change-sets": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });

    render(<ChangeSetsPanel />);

    expect(await screen.findByTestId("change-sets-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});
