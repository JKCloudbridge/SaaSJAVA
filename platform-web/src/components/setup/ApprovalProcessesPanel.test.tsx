import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PROCESS } from "@/test/approvals";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApprovalProcessesPanel } from "./ApprovalProcessesPanel";

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

const ACTIVATION = "POST /api/v1/metadata/approvals/Expense__c/Large__c/activation";
const REMOVE = "DELETE /api/v1/metadata/approvals/Expense__c/Large__c";

function answers(processes = [PROCESS]) {
  return {
    "GET /api/v1/metadata/approvals": () => json({ data: processes }),
    "GET /api/v1/metadata/objects": () =>
      json({ data: [{ apiName: "Expense__c", label: "Expense" }, { apiName: "Trip__c", label: "Trip" }] }),
  };
}

describe("ApprovalProcessesPanel", () => {
  it("lists the processes with their object, their steps in order and their state", async () => {
    fakeApi(answers([PROCESS, { ...PROCESS, apiName: "Idle__c", label: "Idle", active: false, entryCriteria: undefined }]));

    render(<ApprovalProcessesPanel />);

    const rows = await screen.findAllByTestId("approval-process-row");
    expect(rows[0]).toHaveTextContent("Large expense");
    expect(rows[0]).toHaveTextContent("Expense");
    expect(rows[0]).toHaveTextContent("Manager → Finance");
    expect(rows[0]).toHaveTextContent("Those that meet the criteria");
    expect(rows[0]).toHaveTextContent("On");
    expect(rows[1]).toHaveTextContent("Any record");
    expect(rows[1]).toHaveTextContent("Off");
  });

  it("links to the builder and to the requests of a process, and starts a new one on the chosen object", async () => {
    fakeApi(answers());
    render(<ApprovalProcessesPanel />);
    const row = await screen.findByTestId("approval-process-row");

    expect(within(row).getByRole("link", { name: "Large expense" })).toHaveAttribute(
      "href",
      "/setup/approvals/Expense__c/Large__c",
    );
    expect(within(row).getByRole("link", { name: "Requests" })).toHaveAttribute(
      "href",
      "/approvals?object=Expense__c&process=Large__c",
    );
    expect(screen.getByRole("button", { name: "New approval process" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Object"), { target: { value: "Trip__c" } });
    fireEvent.click(screen.getByRole("button", { name: "New approval process" }));
    expect(push).toHaveBeenCalledWith("/setup/approvals/Trip__c/new");
  });

  it("filters the loaded processes with the search box", async () => {
    fakeApi(answers([PROCESS, { ...PROCESS, apiName: "Idle__c", label: "Idle", active: false }]));
    render(<ApprovalProcessesPanel />);
    await screen.findAllByTestId("approval-process-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search approval processes" }), { target: { value: "idle" } });
    expect(screen.getAllByTestId("approval-process-row")).toHaveLength(1);
  });

  it("switches a process off with the version it read, live", async () => {
    const calls = fakeApi({ ...answers(), [ACTIVATION]: () => json({ data: { ...PROCESS, active: false } }) });
    render(<ApprovalProcessesPanel />);

    fireEvent.click(within(await screen.findByTestId("approval-process-row")).getByRole("button", { name: "Switch off" }));

    expect(await screen.findByTestId("approval-processes-notice")).toHaveTextContent("switched off");
    expect(JSON.parse(calls.find((call) => call.key === ACTIVATION)?.body ?? "{}")).toEqual({ active: false, version: 5 });
  });

  it("shows the API's words when a process cannot be switched on or removed", async () => {
    fakeApi({
      ...answers([{ ...PROCESS, active: false }]),
      [ACTIVATION]: () => refusal("CONFLICT", "Approvals are not part of this organization's plan.", 409),
      [REMOVE]: () => refusal("CONFLICT", "A request is waiting for a decision.", 409),
    });
    render(<ApprovalProcessesPanel />);
    const row = await screen.findByTestId("approval-process-row");

    fireEvent.click(within(row).getByRole("button", { name: "Switch on" }));
    expect(await screen.findByTestId("approval-processes-problem")).toHaveTextContent("not part of this organization's plan");

    fireEvent.click(within(row).getByRole("button", { name: "Remove" }));
    expect(await screen.findByText(/A request is waiting for a decision/)).toBeInTheDocument();
  });

  it("drafts a removal in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      ...answers(),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    render(<ApprovalProcessesPanel />);

    fireEvent.click(within(await screen.findByTestId("approval-process-row")).getByRole("button", { name: "Remove" }));

    expect(await screen.findByTestId("approval-processes-notice")).toHaveTextContent("added to the change set");
    expect(calls.some((call) => call.key === REMOVE)).toBe(false);
    expect(JSON.parse(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets/set-1/changes")?.body ?? "{}"))
      .toEqual({ kind: "DELETE_APPROVAL_PROCESS", objectApiName: "Expense__c", itemApiName: "Large__c" });
  });

  it("says so when there are none, and shows the API's words when the list cannot be read", async () => {
    fakeApi(answers([]));
    const { unmount } = render(<ApprovalProcessesPanel />);
    expect(await screen.findByText("No approval processes yet.")).toBeInTheDocument();
    unmount();

    fakeApi({ "GET /api/v1/metadata/approvals": () => refusal("FORBIDDEN", "You may not view approvals.", 403) });
    render(<ApprovalProcessesPanel />);
    expect(await screen.findByTestId("approvals-failed")).toHaveTextContent("You may not view approvals.");
  });
});
