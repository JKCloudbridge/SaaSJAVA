import { fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ReleasesPanel } from "./ReleasesPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/metadata/releases";
const ROLLBACK = "POST /api/v1/metadata/releases/latest/rollback";
const CHECK = "POST /api/v1/metadata/releases/latest/rollback-check";

const RELEASES = {
  data: [
    {
      number: 3, kind: "ROLLBACK", undoesRelease: 2, createdAt: "2030-01-31T09:30:00Z", latest: true,
      items: [{ action: "REMOVED", kind: "FIELD", objectApiName: "Team__c", itemApiName: "code__c" }],
    },
    {
      number: 2, kind: "CHANGE_SET", changeSetName: "Projects", rolledBackBy: 3, createdAt: "2030-01-30T09:30:00Z",
      latest: false, items: [{ action: "ADDED", kind: "FIELD", objectApiName: "Team__c", itemApiName: "code__c" }],
    },
    {
      number: 1, kind: "QUICK", createdAt: "2030-01-29T09:30:00Z", latest: false,
      items: [{ action: "ADDED", kind: "OBJECT", objectApiName: "Team__c" }],
    },
  ],
};

describe("ReleasesPanel", () => {
  it("lists the publications newest first with what each did and how they relate, under React strict mode", async () => {
    fakeApi({ [LIST]: () => json(RELEASES) });

    render(
      <StrictMode>
        <ReleasesPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("release-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Release 3");
    expect(rows[0]).toHaveTextContent("undoes release 2");
    expect(rows[0]).toHaveTextContent("Removed field Team__c.code__c");
    expect(rows[1]).toHaveTextContent("“Projects”");
    expect(rows[1]).toHaveTextContent("rolled back by release 3");
    expect(rows[2]).toHaveTextContent("A change made at once");
  });

  it("offers a rollback for the latest release only, after a second click", async () => {
    const calls = fakeApi({ [LIST]: () => json(RELEASES), [ROLLBACK]: () => json({ data: RELEASES.data[0] }) });
    render(<ReleasesPanel />);
    await screen.findAllByTestId("release-row");

    expect(screen.getAllByRole("button", { name: "Roll back this release" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Roll back this release" }));
    expect(calls.some((call) => call.key === ROLLBACK)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, roll back release 3" }));

    expect(await screen.findByTestId("releases-notice")).toHaveTextContent("The latest release was rolled back.");
    expect(calls.some((call) => call.key === ROLLBACK)).toBe(true);
  });

  it("checks a rollback and shows the problems, including records that would be lost", async () => {
    fakeApi({
      [LIST]: () => json(RELEASES),
      [CHECK]: () =>
        json({
          data: {
            valid: false,
            problems: [
              {
                kind: "RECORDS", position: 1, objectApiName: "Team__c", itemApiName: "code__c",
                message: "Records hold values in the field Team__c.code__c, so this cannot be undone.", fields: [],
              },
            ],
            items: [], objects: [],
          },
        }),
    });
    render(<ReleasesPanel />);
    await screen.findAllByTestId("release-row");

    fireEvent.click(screen.getByRole("button", { name: "Check what a rollback would do" }));

    const problems = await screen.findByTestId("rollback-report-problems");
    expect(problems).toHaveTextContent("Records would be lost");
    expect(problems).toHaveTextContent("Records hold values in the field Team__c.code__c");
  });

  it("shows the API's words when the rollback is refused", async () => {
    fakeApi({
      [LIST]: () => json(RELEASES),
      [ROLLBACK]: () =>
        json(
          {
            error: {
              code: "CONFLICT",
              message: "The latest release cannot be rolled back: Records hold values.",
              fields: { problems: ["Change 1: Records hold values."] },
            },
          },
          409,
        ),
    });
    render(<ReleasesPanel />);
    await screen.findAllByTestId("release-row");

    fireEvent.click(screen.getByRole("button", { name: "Roll back this release" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, roll back release 3" }));

    const problem = await screen.findByTestId("releases-problem");
    expect(problem).toHaveTextContent("The latest release cannot be rolled back");
    expect(problem).toHaveTextContent("Problem: Change 1: Records hold values.");
  });

  it("says so when nothing was published", async () => {
    fakeApi({ [LIST]: () => json({ data: [] }) });

    render(<ReleasesPanel />);

    expect(await screen.findByText("Nothing has been published yet.")).toBeInTheDocument();
  });

  it("shows what the API answered when the history cannot be read", async () => {
    fakeApi({ [LIST]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });

    render(<ReleasesPanel />);

    expect(await screen.findByTestId("releases-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});
