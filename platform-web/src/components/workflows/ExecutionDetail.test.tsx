import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json, refusal, type Handler } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { deadRun, run } from "@/test/workflows";
import { ExecutionDetail } from "./ExecutionDetail";

const route = vi.hoisted(() => ({ id: "d1" }));
vi.mock("next/navigation", () => ({ useParams: () => ({ executionId: route.id }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  route.id = "d1";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const REPLAY = "POST /api/v1/workflows/executions/d1/replay";

function open(abilities: string[], execution = deadRun("d1"), more: Record<string, Handler> = {}) {
  const calls = shellApi({
    abilities,
    more: {
      [`GET /api/v1/workflows/executions/${execution.id}`]: () => json({ data: execution }),
      "GET /api/v1/objects/Order__c/form": () =>
        json({ data: { objectApiName: "Order__c", label: "Order", pluralLabel: "Orders", titleField: "status__c", fields: [], recordTypes: [], searchEnabled: false, actions: {}, layout: { sections: [] } } }),
      "GET /api/v1/objects/Order__c/records/0198a1b2-0000-7000-8000-0000000000aa": () =>
        json({ data: { id: "0198a1b2-0000-7000-8000-0000000000aa", fields: { status__c: "Open order" } } }),
      ...more,
    },
  });
  render(
    <ShellProviders>
      <ExecutionDetail />
    </ShellProviders>,
  );
  return calls;
}

describe("ExecutionDetail", () => {
  it("shows the run with the reason in words, the type of the failure and each step with its attempts", async () => {
    open(["workflows.view"]);

    expect(await screen.findByTestId("run-reason")).toHaveTextContent("A value that must be unique is already in use.");
    expect(screen.getByTestId("run-reason")).toHaveTextContent("BackgroundWriteException");
    const steps = screen.getAllByTestId("step-row");
    expect(steps).toHaveLength(2);
    expect(steps[0]).toHaveTextContent("1. Mark");
    expect(steps[0]).toHaveTextContent("Done");
    expect(steps[1]).toHaveTextContent("2. Tell");
    expect(steps[1]).toHaveTextContent("Failed");
    expect(steps[1]).toHaveTextContent("A value that must be unique is already in use.");
  });

  it("names the record by its title read with the person's own permissions, and links to it", async () => {
    open(["workflows.view"]);

    const link = await screen.findByRole("link", { name: "Order: Open order" });

    expect(link).toHaveAttribute("href", "/records/Order__c/0198a1b2-0000-7000-8000-0000000000aa");
  });

  it("still links to the record when the person may not open it, with no title", async () => {
    open(["workflows.view"], deadRun("d1"), {
      "GET /api/v1/objects/Order__c/records/0198a1b2-0000-7000-8000-0000000000aa": () => refusal("NOT_FOUND", "This record does not exist.", 404),
    });

    expect(await screen.findByRole("link", { name: "Open the Order" })).toBeInTheDocument();
    expect(screen.queryByText(/Open order/)).toBeNull();
  });

  it("shows the next attempt of a step that waits", async () => {
    open(["workflows.view"], run("q1", {
      id: "d1",
      status: "QUEUED",
      reason: "retry_scheduled",
      steps: [{ position: 1, name: "Mark", type: "SET_FIELDS", status: "RETRYING", startedAt: "2026-10-17T09:00:01Z", attempts: 2, nextAttemptAt: "2026-10-17T09:05:00Z", reason: "transient_failure", failureType: "CannotAcquireLockException" }],
    }));

    const step = await screen.findByTestId("step-row");

    expect(step).toHaveTextContent("Waiting for another attempt");
    expect(step).toHaveTextContent("Next attempt");
    expect(step).toHaveTextContent("A temporary problem with the database");
    expect(step).toHaveTextContent("CannotAcquireLockException");
  });

  it("offers a replay only with the ability, and says exactly what it will do before it does it", async () => {
    const calls = open(["workflows.view", "workflows.replay"], deadRun("d1"), {
      [REPLAY]: () => json({ data: { execution: "d1", outcome: "REPLAYED", replay: "n1" } }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Replay" }));

    expect(screen.getByTestId("replay-confirmation")).toHaveTextContent("starts at step 2 (Tell)");
    expect(calls.some((call) => call.key === REPLAY)).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, replay it" }));

    expect(await screen.findByTestId("run-notice")).toHaveTextContent("A new run was queued.");
    expect(screen.getByRole("link", { name: "See the new run" })).toHaveAttribute("href", "/workflows/executions/n1");
  });

  it("shows no replay to a person without the ability and none for a run that was replayed already", async () => {
    open(["workflows.view"]);
    await screen.findByTestId("run-actions");
    expect(screen.queryByRole("button", { name: "Replay" })).toBeNull();
  });

  it("shows where a replayed run comes from and where a replayed dead letter went", async () => {
    open(["workflows.view", "workflows.replay"], deadRun("d1", { replayedBy: "n1", replayOf: "o0", resumeStep: 2 }));

    expect(await screen.findByRole("link", { name: "A newer run" })).toHaveAttribute("href", "/workflows/executions/n1");
    expect(screen.getByRole("link", { name: "The earlier run" })).toHaveAttribute("href", "/workflows/executions/o0");
    expect(screen.queryByRole("button", { name: "Replay" })).toBeNull();
  });

  it("offers a cancel for a run that waits, only with the ability to manage", async () => {
    const waiting = run("d1", { status: "QUEUED", reason: "rate_limited" });
    const calls = open(["workflows.view", "workflows.manage"], waiting, {
      "POST /api/v1/workflows/executions/d1/cancel": () => json({ data: { ...waiting, status: "CANCELLED" } }),
    });

    fireEvent.click(await screen.findByRole("button", { name: "Cancel the run" }));

    expect(await screen.findByTestId("run-notice")).toHaveTextContent("The run was cancelled.");
    expect(calls.some((call) => call.key === "POST /api/v1/workflows/executions/d1/cancel")).toBe(true);
  });

  it("shows the API's words when the replay is refused", async () => {
    open(["workflows.view", "workflows.replay"], deadRun("d1"), {
      [REPLAY]: () => refusal("FORBIDDEN", "You may not replay runs.", 403),
    });

    fireEvent.click(await screen.findByRole("button", { name: "Replay" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, replay it" }));

    expect(await screen.findByTestId("run-problem")).toHaveTextContent("You may not replay runs.");
  });

  it("shows the API's words when the run cannot be read", async () => {
    open([], deadRun("d1"), { "GET /api/v1/workflows/executions/d1": () => refusal("NOT_FOUND", "This execution does not exist.", 404) });

    expect(await screen.findByTestId("run-failed")).toHaveTextContent("This execution does not exist.");
  });
});
