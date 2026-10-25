import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { ApprovalFlow } from "./ApprovalFlow";
import type { RecordApproval } from "./ApprovalPanel";

type Panel = components["schemas"]["RecordApprovalView"];
type Instance = components["schemas"]["ApprovalInstanceView"];

const NONE = {
  canApprove: false,
  canAssign: false,
  canCancel: false,
  canReapply: false,
  canReassign: false,
  canRecall: false,
  canReject: false,
  canSubmit: false,
};

function instance(historyVisible = true): Instance {
  return {
    actions: NONE,
    history: [],
    historyVisible,
    steps: [
      {
        position: 1,
        name: "plant",
        label: "Plant QMS",
        completion: "ANY",
        status: "APPROVED",
        asksForChoice: false,
        assignments: [
          { id: "t1", assignee: { userId: "u1", name: "Plant Person" }, via: "RULE", status: "APPROVED", decidedAt: "2026-10-24T09:00:00Z" },
        ],
      },
      {
        position: 2,
        name: "corporate",
        label: "Corporate QMS",
        completion: "ANY",
        status: "ACTIVE",
        asksForChoice: false,
        assignments: [
          { id: "t2", assignee: { userId: "u2", name: "Corporate One" }, via: "RULE", status: "OPEN" },
          { id: "t3", assignee: { userId: "u3", name: "Corporate Two" }, via: "RULE", status: "OPEN" },
        ],
      },
    ],
    summary: {
      id: "i1",
      objectApiName: "InspectionTemplate",
      processApiName: "p",
      processLabel: "Template approval",
      processVersion: 1,
      recordId: "r1",
      startedAt: "2026-10-24T08:00:00Z",
      status: "PENDING",
      stepCount: 2,
      submittedBy: { userId: "u0", name: "Department Author" },
      version: 1,
      currentStepAsksForChoice: false,
    },
  } as Instance;
}

function approval(panel: Partial<Panel> | undefined): RecordApproval {
  return { panel: panel === undefined ? undefined : { available: true, locked: false, processes: [], actions: NONE, ...panel } } as unknown as RecordApproval;
}

describe("the approval flow beside the details (FA6D)", () => {
  it("is a list with one line per step, the state in text, and the current step marked", () => {
    render(<ApprovalFlow approval={approval({ current: instance() })} />);

    const steps = within(screen.getByRole("list")).getAllByRole("listitem");
    expect(steps.map((one) => one.textContent)).toEqual([
      "SubmittedDoneDepartment Author",
      "Plant QMSApprovedPlant Person",
      "Corporate QMSPendingWaiting for 2 people",
    ]);
    expect(steps.map((one) => one.getAttribute("aria-current"))).toEqual([null, null, "step"]);
    expect(screen.getByRole("navigation", { name: "Approval flow" })).toBeInTheDocument();
  });

  it("draws nothing when the record never had a request", () => {
    const { container } = render(<ApprovalFlow approval={approval({})} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("draws nothing before the answer has arrived or when approvals are not available", () => {
    const first = render(<ApprovalFlow approval={approval(undefined)} />);
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    const second = render(<ApprovalFlow approval={approval({ available: false, current: instance() })} />);
    expect(second.container).toBeEmptyDOMElement();
  });

  it("offers History only when the API lets the member read it, and opens the history of the card", () => {
    const history = document.createElement("details");
    history.id = "approval-history";
    document.body.appendChild(history);

    render(<ApprovalFlow approval={approval({ current: instance() })} />);
    fireEvent.click(screen.getByRole("button", { name: "History" }));
    expect(history.open).toBe(true);
    history.remove();
  });

  it("leaves out History when the history is not visible to the member", () => {
    render(<ApprovalFlow approval={approval({ current: instance(false) })} />);

    expect(screen.queryByRole("button", { name: "History" })).toBeNull();
  });

  it("marks the dots with state classes, never with a literal colour", () => {
    render(<ApprovalFlow approval={approval({ current: instance() })} />);

    const classes = within(screen.getByRole("list"))
      .getAllByRole("listitem")
      .map((one) => one.className);
    expect(classes[1]).toContain("approval-flow-done");
    expect(classes[2]).toContain("approval-flow-current");
    expect(classes[2]).toContain("approval-flow-now");
    expect(screen.getByTestId("approval-flow").innerHTML).not.toMatch(/style=|#[0-9a-f]{3,6}/i);
  });
});
