import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json, refusal, type Handler } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { deadRun, run } from "@/test/workflows";
import { ExecutionsPanel } from "./ExecutionsPanel";

const search = vi.hoisted(() => ({ value: new URLSearchParams() }));
vi.mock("next/navigation", () => ({ useSearchParams: () => search.value, useRouter: () => ({ push: vi.fn() }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  search.value = new URLSearchParams();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/workflows/executions";
const SUMMARY = "GET /api/v1/workflows/executions/dead-letters/summary";
const PARKED = "GET /api/v1/workflows/parked-events";
const BULK = "POST /api/v1/workflows/executions/replay";

const PAGE = (rows: unknown[], next?: string) => ({
  data: rows,
  pagination: { limit: 25, hasMore: next !== undefined, ...(next ? { nextCursor: next } : {}) },
});

function open(abilities: string[], more: Record<string, Handler> = {}) {
  const calls = shellApi({
    abilities,
    more: {
      [LIST]: () => json(PAGE([deadRun("d1"), deadRun("d2", { workflow: "Other__c" }), run("c1"), run("q1", { status: "QUEUED", reason: "retry_scheduled" })])),
      [SUMMARY]: () =>
        json({ data: { total: 2, groups: [{ workflow: "Watch__c", objectApiName: "Order__c", reason: "unique_clash", count: 1 }, { workflow: "Other__c", objectApiName: "Order__c", reason: "rejected_by_rule", count: 1 }] } }),
      [PARKED]: () => json({ data: [] }),
      "GET /api/v1/metadata/objects": () => json({ data: [{ apiName: "Order__c", label: "Order" }] }),
      ...more,
    },
  });
  render(
    <ShellProviders>
      <ExecutionsPanel />
    </ShellProviders>,
  );
  return calls;
}

describe("ExecutionsPanel", () => {
  it("lists the runs with their state and the reason in words, a link to the record and none of its values", async () => {
    open(["workflows.view"]);

    const rows = await screen.findAllByTestId("run-row");

    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent("Watch__c");
    expect(rows[0]).toHaveTextContent("Needs a person");
    expect(rows[0]).toHaveTextContent("A value that must be unique is already in use.");
    expect(within(rows[0] as HTMLElement).getByRole("link", { name: "Open the Order" })).toHaveAttribute(
      "href",
      "/records/Order__c/0198a1b2-0000-7000-8000-0000000000aa",
    );
    expect(rows[3]).toHaveTextContent("It failed in a way that may pass, and waits for another attempt.");
  });

  it("shows the dead letters waiting for a person with their counts by workflow and reason", async () => {
    open(["workflows.view"]);

    const groups = await screen.findAllByTestId("dead-letter-group");

    expect(screen.getByTestId("dead-letters")).toHaveTextContent("2 runs need a person");
    expect(groups[0]).toHaveTextContent("Watch__c");
    expect(groups[1]).toHaveTextContent("A validation rule rejected the record.");
  });

  it("shows no replay, no selection and no cancel to a person the API does not list the abilities for", async () => {
    open(["workflows.view"]);
    await screen.findAllByTestId("run-row");

    expect(screen.queryByRole("button", { name: /Replay/ })).toBeNull();
    expect(screen.queryAllByRole("checkbox", { name: /Select/ })).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull();
  });

  it("offers a cancel only for a run that waits and only with the ability to manage", async () => {
    const calls = open(["workflows.view", "workflows.manage"], {
      "POST /api/v1/workflows/executions/q1/cancel": () => json({ data: run("q1", { status: "CANCELLED" }) }),
    });
    const rows = await screen.findAllByTestId("run-row");

    expect(within(rows[0] as HTMLElement).queryByRole("button", { name: "Cancel" })).toBeNull();
    fireEvent.click(within(rows[3] as HTMLElement).getByRole("button", { name: "Cancel" }));

    expect(await screen.findByTestId("runs-notice")).toHaveTextContent("The run was cancelled.");
    expect(calls.some((call) => call.key === "POST /api/v1/workflows/executions/q1/cancel")).toBe(true);
  });

  it("selects the runs that can be replayed, says what a replay will do, and shows the result of each", async () => {
    const calls = open(["workflows.view", "workflows.replay"], {
      [BULK]: () =>
        json({ data: { results: [{ execution: "d1", outcome: "REPLAYED", replay: "n1" }, { execution: "d2", outcome: "NOT_REPLAYABLE", reason: "replay_failed" }] } }),
    });
    await screen.findAllByTestId("run-row");

    expect(screen.getAllByRole("checkbox", { name: /Select the run of/ })).toHaveLength(2);
    fireEvent.click(screen.getByRole("checkbox", { name: "Select every run on this page that can be replayed" }));
    fireEvent.click(screen.getByRole("button", { name: "Replay the 2 selected" }));
    expect(screen.getByTestId("bulk-confirmation")).toHaveTextContent("each starting at the step that failed");
    expect(calls.some((call) => call.key === BULK)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, replay 2" }));

    const results = await screen.findAllByTestId("replay-result");
    expect(results[0]).toHaveTextContent("A new run was queued.");
    expect(results[1]).toHaveTextContent("could not be replayed");
    expect(JSON.parse(calls.find((call) => call.key === BULK)?.body ?? "{}")).toEqual({ executions: ["d1", "d2"] });
    expect(screen.getByTestId("runs-notice")).toHaveTextContent("1 run was replayed.");
  });

  it("shows the API's words when the replay is refused", async () => {
    open(["workflows.view", "workflows.replay"], { [BULK]: () => refusal("FORBIDDEN", "You may not replay runs.", 403) });
    await screen.findAllByTestId("run-row");

    fireEvent.click(screen.getAllByRole("checkbox", { name: /Select the run of/ })[0] as HTMLElement);
    fireEvent.click(screen.getByRole("button", { name: "Replay the 1 selected" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, replay 1" }));

    expect(await screen.findByTestId("runs-problem")).toHaveTextContent("You may not replay runs.");
  });

  it("filters by what the person chooses and asks the API for it, with the dead letters as their own filter", async () => {
    const calls = open(["workflows.view"]);
    await screen.findAllByTestId("run-row");

    fireEvent.change(screen.getByLabelText("Workflow"), { target: { value: "Watch__c" } });
    fireEvent.change(screen.getByLabelText("State"), { target: { value: "COMPLETED" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    await waitFor(() => expect(calls.filter((call) => call.key === LIST).length).toBeGreaterThan(1));
    fireEvent.click(screen.getByLabelText("Only runs that need a person"));
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(screen.getByLabelText("State")).toBeDisabled());
  });

  it("starts from the workflow named in the address", async () => {
    search.value = new URLSearchParams("workflow=Watch__c&dead=true");
    open(["workflows.view"]);
    await screen.findAllByTestId("run-row");

    expect(screen.getByLabelText("Workflow")).toHaveValue("Watch__c");
    expect(screen.getByLabelText("Only runs that need a person")).toBeChecked();
  });

  it("pages with the cursor the API gave", async () => {
    const calls = open(["workflows.view"], {
      [LIST]: (request) =>
        new URL(request.url).searchParams.get("cursor")
          ? json(PAGE([run("c2")]))
          : json(PAGE([run("c1")], "next-page")),
    });
    await screen.findAllByTestId("run-row");

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    await waitFor(() => expect(screen.getAllByTestId("run-row")).toHaveLength(2));
    expect(calls.some((call) => call.key === LIST)).toBe(true);
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("lists the events the platform gave up on and puts one back only with the ability to replay", async () => {
    const parked = { id: "p1", type: "metadata.published", occurredAt: "2026-10-17T08:00:00Z", attempts: 10, lastFailureType: "SomeFailure" };
    const calls = open(["workflows.view", "workflows.replay"], {
      [PARKED]: () => json({ data: [parked] }),
      "POST /api/v1/workflows/parked-events/p1/redrive": () => new Response(null, { status: 204 }),
    });

    const event = await screen.findByTestId("parked-event");
    expect(event).toHaveTextContent("metadata.published");
    expect(event).toHaveTextContent("10 attempts");
    fireEvent.click(within(event).getByRole("button", { name: "Put it back" }));

    expect(await screen.findByTestId("runs-notice")).toHaveTextContent("The event was put back.");
    expect(calls.some((call) => call.key === "POST /api/v1/workflows/parked-events/p1/redrive")).toBe(true);
  });

  it("shows no way to put an event back to a person without the ability", async () => {
    open(["workflows.view"], { [PARKED]: () => json({ data: [{ id: "p1", type: "metadata.published", occurredAt: "2026-10-17T08:00:00Z", attempts: 1 }] }) });

    await screen.findByTestId("parked-event");

    expect(screen.queryByRole("button", { name: "Put it back" })).toBeNull();
  });

  it("shows the API's words when the history cannot be read, and says so when nothing matches", async () => {
    open(["workflows.view"], { [LIST]: () => json(PAGE([])) });
    expect(await screen.findByTestId("runs-empty")).toHaveTextContent("No runs match.");
  });
});

describe("ExecutionsPanel refusal", () => {
  it("shows the API's words when the history cannot be read", async () => {
    open([], { [LIST]: () => refusal("FORBIDDEN", "You may not view workflows.", 403) });

    expect(await screen.findByTestId("runs-failed")).toHaveTextContent("You may not view workflows.");
  });
});
