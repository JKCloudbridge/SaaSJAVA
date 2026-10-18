import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { CATALOGUE, WATCH } from "@/test/workflows";
import { WorkflowsPanel } from "./WorkflowsPanel";

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  push.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const ACTIVATION = "POST /api/v1/metadata/workflows/Order__c/Watch__c/activation";
const REMOVE = "DELETE /api/v1/metadata/workflows/Order__c/Watch__c";

function answers(workflows = [WATCH]) {
  return {
    "GET /api/v1/metadata/workflows": () => json({ data: workflows }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Order__c", label: "Order" }, { apiName: "Task__c", label: "Task" }] }),
    "GET /api/v1/metadata/workflows/action-types": () => json({ data: CATALOGUE }),
  };
}

describe("WorkflowsPanel", () => {
  it("lists the workflows with their object, what starts them in the catalogue's words and their state, under React strict mode", async () => {
    fakeApi(answers([WATCH, { ...WATCH, apiName: "Idle__c", label: "Idle", active: false, trigger: { kind: "WHEN_MADE" } }]));

    render(
      <StrictMode>
        <WorkflowsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("workflow-row");
    expect(rows[0]).toHaveTextContent("Watch");
    expect(rows[0]).toHaveTextContent("Order");
    expect(rows[0]).toHaveTextContent("A chosen field changes.");
    expect(rows[0]).toHaveTextContent("On");
    expect(rows[1]).toHaveTextContent("A record is made.");
    expect(rows[1]).toHaveTextContent("Off");
  });

  it("opens the builder and the runs of a workflow, and the builder for a new one on the object chosen", async () => {
    fakeApi(answers());
    render(<WorkflowsPanel />);
    const row = await screen.findByTestId("workflow-row");

    expect(within(row).getByRole("link", { name: "Watch" })).toHaveAttribute("href", "/setup/workflows/Order__c/Watch__c");
    expect(within(row).getByRole("link", { name: "Runs" })).toHaveAttribute("href", "/workflows/executions?workflow=Watch__c");
    expect(screen.getByRole("link", { name: "Workflow runs" })).toHaveAttribute("href", "/workflows/executions");
    expect(screen.getByRole("button", { name: "New workflow" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Object"), { target: { value: "Task__c" } });
    fireEvent.click(screen.getByRole("button", { name: "New workflow" }));
    expect(push).toHaveBeenCalledWith("/setup/workflows/Task__c/new");
  });

  it("switches a workflow off with the version it read, live", async () => {
    const calls = fakeApi({ ...answers(), [ACTIVATION]: () => json({ data: { ...WATCH, active: false } }) });
    render(<WorkflowsPanel />);
    const row = await screen.findByTestId("workflow-row");

    fireEvent.click(within(row).getByRole("button", { name: "Switch off" }));

    expect(await screen.findByTestId("workflows-notice")).toHaveTextContent("The workflow is switched off.");
    expect(JSON.parse(calls.find((call) => call.key === ACTIVATION)?.body ?? "{}")).toEqual({ active: false, version: 7 });
  });

  it("shows the API's words when a workflow cannot be switched on", async () => {
    fakeApi({
      ...answers([{ ...WATCH, active: false }]),
      [ACTIVATION]: () => refusal("CONFLICT", "Workflows are not part of this organization's plan.", 409),
    });
    render(<WorkflowsPanel />);
    const row = await screen.findByTestId("workflow-row");

    fireEvent.click(within(row).getByRole("button", { name: "Switch on" }));

    expect(await screen.findByTestId("workflows-problem")).toHaveTextContent("not part of this organization's plan");
  });

  it("removes a workflow live and puts a removal into the chosen change set instead", async () => {
    const calls = fakeApi({ ...answers(), [REMOVE]: () => new Response(null, { status: 204 }) });
    render(<WorkflowsPanel />);
    fireEvent.click(within(await screen.findByTestId("workflow-row")).getByRole("button", { name: "Remove" }));
    expect(await screen.findByTestId("workflows-notice")).toHaveTextContent("The workflow was removed.");
    expect(calls.some((call) => call.key === REMOVE)).toBe(true);
  });

  it("drafts the removal in the chosen change set", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers(),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<WorkflowsPanel />);

    fireEvent.click(within(await screen.findByTestId("workflow-row")).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("workflows-notice")).toHaveTextContent("added to the change set");
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets/set-1/changes")?.body ?? "{}"))
      .toEqual({ kind: "DELETE_WORKFLOW", objectApiName: "Order__c", itemApiName: "Watch__c" });
  });

  it("says so when there is none, and shows the API's words when the list cannot be read", async () => {
    fakeApi(answers([]));
    const { unmount } = render(<WorkflowsPanel />);
    expect(await screen.findByText("No workflows yet.")).toBeInTheDocument();
    unmount();

    fakeApi({ "GET /api/v1/metadata/workflows": () => refusal("FORBIDDEN", "You may not view workflows.", 403) });
    render(<WorkflowsPanel />);
    expect(await screen.findByTestId("workflows-failed")).toHaveTextContent("You may not view workflows.");
  });
});
