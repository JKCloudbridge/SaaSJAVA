import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { ApprovalHistoryList, type ApprovalHistoryEntry } from "./ApprovalHistoryList";

/** The day as the component writes it, in the locale of the machine that runs the test. */
const day = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

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
      asksForChoice: false,
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
      currentStepAsksForChoice: false,
    },
  } as Instance;
}

function entry(key: string, at: string, title: string, steps: readonly string[], note?: string): ApprovalHistoryEntry {
  const one = instance(key, steps);
  one.summary.startedAt = at;
  return { key, at, title, note, instance: one };
}

// The tests run in UTC, so a day is the UTC day. Three approvals on 20 October, one on 1 October.
const entries: ApprovalHistoryEntry[] = [
  entry("a", "2026-10-01T08:00:00Z", "New template", ["Plant QMS", "Corporate QMS"]),
  entry("b", "2026-10-20T08:30:00Z", "Revision 01", ["Plant QMS"], "Walkway wording"),
  entry("c", "2026-10-20T14:45:00Z", "Revision 02", ["Corporate QMS"]),
  entry("d", "2026-10-20T10:00:00Z", "Change requested", ["Plant QMS"]),
];

describe("the history of approvals by day (FA6E, FA11A)", () => {
  it("makes one item for each day, newest first, and opens only the newest day", () => {
    render(<ApprovalHistoryList entries={entries} />);

    const days = screen.getAllByRole("button");
    expect(days).toHaveLength(2);
    expect(days[0]).toHaveTextContent(day("2026-10-20"));
    expect(days[0]).toHaveTextContent("3 approvals");
    expect(days[1]).toHaveTextContent(day("2026-10-01"));
    expect(days[1]).toHaveTextContent("1 approval");
    expect(days.map((one) => one.getAttribute("aria-expanded"))).toEqual(["true", "false"]);
  });

  it("puts the approvals of a day in one card, newest first, each with its title, time and steps", () => {
    render(<ApprovalHistoryList entries={entries} />);

    const region = screen.getByRole("region", { name: new RegExp(day("2026-10-20")) });
    const items = within(region).getAllByTestId("approval-history-item");
    expect(items.map((one) => one.querySelector(".approval-history-item-title")?.textContent)).toEqual([
      "Revision 02",
      "Change requested",
      "Revision 01",
    ]);
    expect(within(items[0] as HTMLElement).getByText(new Date("2026-10-20T14:45:00Z").toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }))).toBeInTheDocument();
    expect(within(items[0] as HTMLElement).getByText("Corporate QMS")).toBeInTheDocument();
    expect(within(items[2] as HTMLElement).getByText("Walkway wording")).toBeInTheDocument();
    // One card for the day: no card inside it and no repeated heading above each flow.
    expect(within(region).queryAllByRole("heading")).toHaveLength(0);
    expect(within(region).queryByRole("button", { name: "History" })).toBeNull();
  });

  it("groups by the reader's day: two moments of one day are one item, two days are two", () => {
    render(
      <ApprovalHistoryList
        entries={[
          entry("a", "2026-10-02T00:30:00Z", "First", ["Plant QMS"]),
          entry("b", "2026-10-02T23:30:00Z", "Second", ["Plant QMS"]),
          entry("c", "2026-10-03T00:30:00Z", "Third", ["Plant QMS"]),
        ]}
      />,
    );

    const days = screen.getAllByRole("button");
    expect(days.map((one) => one.textContent)).toEqual([
      expect.stringContaining(day("2026-10-03")),
      expect.stringContaining(day("2026-10-02")),
    ]);
  });

  it("opens an older day with its flow and closes it again", () => {
    render(<ApprovalHistoryList entries={entries} />);

    fireEvent.click(screen.getByRole("button", { name: new RegExp(day("2026-10-01")) }));
    const region = screen.getByRole("region", { name: new RegExp(day("2026-10-01")) });
    expect(within(region).getByText("Corporate QMS")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: new RegExp(day("2026-10-01")) }));
    expect(screen.getByRole("button", { name: new RegExp(day("2026-10-01")) })).toHaveAttribute("aria-expanded", "false");
  });

  it("controls a region it names: the button points at the day it opens", () => {
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
