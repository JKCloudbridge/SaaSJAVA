import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NO_ACTIONS, RECORD_ID, REQUEST_ID, summary } from "@/test/approvals";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApprovalPanel } from "./ApprovalPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const PANEL = `GET /api/v1/objects/Expense__c/records/${RECORD_ID}/approval`;
const SUBMIT = `POST /api/v1/objects/Expense__c/records/${RECORD_ID}/approval`;

function pending(actions = NO_ACTIONS, more = {}) {
  return {
    available: true,
    locked: true,
    processes: [],
    actions,
    current: {
      summary: summary(),
      steps: [
        {
          position: 1,
          name: "Manager",
          label: "Manager",
          completion: "ANY",
          status: "ACTIVE",
          assignments: [
            {
              id: "a-1",
              assignee: { userId: "u-1", name: "User B" },
              via: "DIRECT",
              status: "OPEN",
            },
          ],
        },
        { position: 2, name: "Finance", label: "Finance", completion: "ALL", status: "WAITING", assignments: [] },
      ],
      actions,
      historyVisible: true,
      history: [
        {
          id: "h-1",
          stepPosition: 1,
          kind: "SUBMITTED",
          actor: { userId: "u-9", name: "User A" },
          comment: "Please look at this",
          at: "2026-10-18T09:00:00Z",
        },
      ],
    },
    ...more,
  };
}

function open(panel: object) {
  const onState = vi.fn();
  const onChanged = vi.fn();
  fakeApi({ [PANEL]: () => json({ data: panel }) });
  render(
    <ApprovalPanel
      objectApiName="Expense__c"
      recordId={RECORD_ID}
      recordVersion={7}
      refresh={0}
      onState={onState}
      onChanged={onChanged}
    />,
  );
  return { onState, onChanged };
}

describe("ApprovalPanel", () => {
  it("draws nothing for an object that has no process and a record that has no request", async () => {
    const { onState } = open({ available: false, locked: false, processes: [], actions: NO_ACTIONS });

    await vi.waitFor(() => expect(onState).toHaveBeenCalled());
    expect(screen.queryByTestId("approval-panel")).not.toBeInTheDocument();
  });

  it("says the record is locked, lists the steps with the people asked and tells the page", async () => {
    const { onState } = open(pending());

    expect(await screen.findByTestId("approval-locked")).toHaveTextContent("locked while it is being approved");
    const steps = screen.getByTestId("approval-steps");
    expect(steps).toHaveTextContent("Manager");
    expect(steps).toHaveTextContent("User B");
    expect(steps).toHaveTextContent("Finance");
    expect(onState).toHaveBeenCalledWith({ locked: true, mayApprove: false, mayOverride: false });
  });

  it("tells a member who may override the lock so, and tells the page", async () => {
    const { onState } = open(pending({ ...NO_ACTIONS, canCancel: true }));

    expect(await screen.findByTestId("approval-locked")).toHaveTextContent("who may override the lock");
    expect(onState).toHaveBeenCalledWith({ locked: true, mayApprove: false, mayOverride: true });
    expect(screen.getByRole("button", { name: "End the request" })).toBeInTheDocument();
  });

  it("offers only the buttons the API says are open", async () => {
    open(pending({ ...NO_ACTIONS, canApprove: true, canReject: true }));

    expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Withdraw the request" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Hand my task on" })).not.toBeInTheDocument();
  });

  it("shows the history, with a comment only inside the history the API chose to send", async () => {
    open(pending());

    const history = await screen.findByTestId("approval-history");
    expect(history).toHaveTextContent("Submitted for approval");
    expect(within(history).getByTestId("approval-comment")).toHaveTextContent("Please look at this");
  });

  it("shows no history when the API says the member may not see it", async () => {
    const panel = pending();
    open({ ...panel, current: { ...panel.current, historyVisible: false, history: [] } });

    await screen.findByTestId("approval-panel");
    expect(screen.queryByTestId("approval-history")).not.toBeInTheDocument();
  });

  it("submits with a note after telling the person the record will be locked, and tells the page", async () => {
    const processes = [{ apiName: "Large__c", label: "Large expense" }];
    const calls = fakeApi({
      [PANEL]: () =>
        json({ data: { available: true, locked: false, processes, actions: { ...NO_ACTIONS, canSubmit: true } } }),
      [SUBMIT]: () => json({ data: {} }, 201),
    });
    const onChanged = vi.fn();
    render(
      <ApprovalPanel
        objectApiName="Expense__c"
        recordId={RECORD_ID}
        recordVersion={7}
        refresh={0}
        onState={vi.fn()}
        onChanged={onChanged}
      />,
    );

    fireEvent.click(await screen.findByTestId("approval-submit"));
    expect(screen.getByTestId("approval-explain")).toHaveTextContent("locked from this moment");
    fireEvent.change(screen.getByLabelText(/Note to the people asked/), { target: { value: " Urgent " } });
    fireEvent.click(screen.getByRole("button", { name: "Submit and lock the record" }));

    expect(await screen.findByTestId("approval-notice")).toHaveTextContent("submitted for approval");
    expect(JSON.parse(calls.find((call) => call.key === SUBMIT)?.body ?? "{}")).toEqual({
      process: "Large__c",
      comment: "Urgent",
    });
    expect(onChanged).toHaveBeenCalled();
  });

  it("shows the API's words when the record cannot be submitted", async () => {
    fakeApi({
      [PANEL]: () =>
        json({
          data: {
            available: true,
            locked: false,
            processes: [{ apiName: "Large__c", label: "Large expense" }],
            actions: { ...NO_ACTIONS, canSubmit: true },
          },
        }),
      [SUBMIT]: () => refusal("CONFLICT", "Nobody can be asked to decide the first step of the approval.", 409),
    });
    render(
      <ApprovalPanel
        objectApiName="Expense__c"
        recordId={RECORD_ID}
        recordVersion={7}
        refresh={0}
        onState={vi.fn()}
        onChanged={vi.fn()}
      />,
    );

    fireEvent.click(await screen.findByTestId("approval-submit"));
    fireEvent.click(screen.getByRole("button", { name: "Submit and lock the record" }));

    expect(await screen.findByTestId("approval-panel-problem")).toHaveTextContent("Nobody can be asked");
  });

  it("explains a step nobody can decide and a decision whose update was refused, in stable words", async () => {
    const panel = pending({ ...NO_ACTIONS, canAssign: true });
    open({
      ...panel,
      current: {
        ...panel.current,
        summary: summary({ attention: "no_approver", outcomeFailure: "rejected_by_rule" }),
      },
    });

    expect(await screen.findByTestId("approval-attention")).toHaveTextContent("Nobody can be asked");
    expect(screen.getByTestId("approval-outcome-failed")).toHaveTextContent("A validation rule refused the change.");
    expect(screen.getByRole("button", { name: "Ask someone as well" })).toBeInTheDocument();
  });

  it("makes the update again on the version of the record it shows, and only when the API offers it", async () => {
    const panel = pending({ ...NO_ACTIONS, canReapply: true }, { locked: false });
    const calls = fakeApi({
      [PANEL]: () =>
        json({
          data: {
            ...panel,
            current: { ...panel.current, summary: summary({ status: "APPROVED", outcomeFailure: "rejected_by_rule" }) },
          },
        }),
      [`POST /api/v1/approvals/instances/${REQUEST_ID}/outcome`]: () => json({ data: {} }),
    });
    render(
      <ApprovalPanel
        objectApiName="Expense__c"
        recordId={RECORD_ID}
        recordVersion={7}
        refresh={0}
        onState={vi.fn()}
        onChanged={vi.fn()}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: "Make the update again" }));

    expect(await screen.findByTestId("approval-notice")).toHaveTextContent("The update was made again.");
    expect(
      JSON.parse(calls.find((call) => call.key === `POST /api/v1/approvals/instances/${REQUEST_ID}/outcome`)?.body ?? "{}"),
    ).toEqual({ recordVersion: 7, version: 3 });
  });

  it("shows the API's words when the panel cannot be read", async () => {
    fakeApi({ [PANEL]: () => refusal("NOT_FOUND", "This record does not exist.", 404) });
    render(
      <ApprovalPanel
        objectApiName="Expense__c"
        recordId={RECORD_ID}
        recordVersion={7}
        refresh={0}
        onState={vi.fn()}
        onChanged={vi.fn()}
      />,
    );

    expect(await screen.findByTestId("approval-panel-failed")).toHaveTextContent("This record does not exist.");
  });
});
