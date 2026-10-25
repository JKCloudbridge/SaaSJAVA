import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { ApprovalHistoryList, type ApprovalHistoryEntry } from "./ApprovalHistoryList";

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

function instance(id: string, steps: readonly string[]): Instance {
  return {
    actions: NONE,
    history: [],
    historyVisible: true,
    steps: steps.map((label, index) => ({
      position: index + 1,
      name: `s${index + 1}`,
      label,
      completion: "ANY",
      status: "APPROVED",
      assignments: [{ id: `${id}-${index}`, assignee: { userId: `u${index}`, name: `Person ${index}` }, via: "RULE", status: "APPROVED" }],
    })),
    summary: {
      id,
      objectApiName: "InspectionTemplate",
      processApiName: "p",
      processLabel: "Template approval",
      processVersion: 1,
      recordId: "r1",
      startedAt: "2026-10-24T08:00:00Z",
      status: "APPROVED",
      stepCount: steps.length,
      submittedBy: { userId: "u0", name: "Department Author" },
      version: 1,
    },
  } as Instance;
}

const entries: ApprovalHistoryEntry[] = [
  { key: "a", at: "2026-10-01T08:00:00Z", title: "New template", instance: instance("a", ["Plant QMS", "Corporate QMS"]) },
  { key: "b", at: "2026-10-20T08:00:00Z", title: "Change requested", note: "Walkway wording", instance: instance("b", ["Plant QMS"]) },
];

describe("the history of approvals (FA6E)", () => {
  it("lists the approvals newest first, each with a title and a date, and opens only the newest", () => {
    render(<ApprovalHistoryList entries={entries} />);

    const headers = screen.getAllByRole("button");
    expect(headers.map((one) => one.textContent)).toEqual([
      expect.stringContaining("Change requested"),
      expect.stringContaining("New template"),
    ]);
    expect(headers.map((one) => one.getAttribute("aria-expanded"))).toEqual(["true", "false"]);
    expect(screen.getAllByRole("navigation", { name: "Approval flow" })).toHaveLength(1);
    expect(screen.getByText("Walkway wording")).toBeInTheDocument();
  });

  it("opens an entry with the same flow the card draws and closes it again", () => {
    render(<ApprovalHistoryList entries={entries} />);

    fireEvent.click(screen.getByRole("button", { name: /New template/ }));
    const region = screen.getByRole("region", { name: /New template/ });
    expect(within(region).getByText("Corporate QMS")).toBeInTheDocument();
    expect(within(region).queryByRole("button", { name: "History" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /New template/ }));
    expect(screen.getByRole("button", { name: /New template/ })).toHaveAttribute("aria-expanded", "false");
  });

  it("controls a region it names: the button points at the entry it opens", () => {
    render(<ApprovalHistoryList entries={entries} />);

    const button = screen.getAllByRole("button")[0] as HTMLElement;
    const region = document.getElementById(button.getAttribute("aria-controls") ?? "");
    expect(region).not.toBeNull();
    expect(region?.hidden).toBe(false);
  });

  it("draws nothing when there is nothing to show", () => {
    const { container } = render(<ApprovalHistoryList entries={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
