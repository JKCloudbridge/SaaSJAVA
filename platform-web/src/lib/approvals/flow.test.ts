import { describe, expect, it } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { approvalFlow } from "./flow";

type Instance = components["schemas"]["ApprovalInstanceView"];
type Step = Instance["steps"][number];
type Task = Step["assignments"][number];

const person = (name: string | undefined, id = name ?? "gone") => ({ userId: id, name });

function task(name: string | undefined, status: string, at?: string): Task {
  return { id: `t-${name}-${status}`, assignee: person(name), via: "RULE", status, decidedAt: at };
}

function step(position: number, label: string, status: string, assignments: Task[] = []): Step {
  return { position, name: label.toLowerCase(), label, completion: "ANY", status, assignments, asksForChoice: false };
}

function request(status: string, steps: Step[], submittedBy = person("Author One")): Instance {
  return {
    actions: {
      canApprove: false,
      canAssign: false,
      canCancel: false,
      canReapply: false,
      canReassign: false,
      canRecall: false,
      canReject: false,
      canSubmit: false,
    },
    history: [],
    historyVisible: true,
    steps,
    summary: {
      id: "i-1",
      objectApiName: "InspectionTemplate",
      processApiName: "process",
      processLabel: "Process",
      processVersion: 1,
      recordId: "r-1",
      startedAt: "2026-10-24T08:00:00Z",
      status,
      stepCount: steps.length,
      submittedBy,
      version: 1,
      currentStepAsksForChoice: false,
    },
  } as Instance;
}

const line = (instance: Instance, at: number) => approvalFlow(instance)[at];

describe("the flow of an approval request (FA6D)", () => {
  it("starts with who submitted, then one line per step in the process's order", () => {
    const flow = approvalFlow(
      request("PENDING", [step(2, "Corporate QMS", "WAITING"), step(1, "Plant QMS", "ACTIVE", [task("Plant Person", "OPEN")])]),
    );

    expect(flow.map((one) => one.label)).toEqual(["Submitted", "Plant QMS", "Corporate QMS"]);
    expect(flow[0]).toMatchObject({ state: "Done", person: "Author One", tone: "done" });
  });

  it("puts the dot on the first step that is not finished, and names the one person it waits for", () => {
    const flow = approvalFlow(
      request("PENDING", [
        step(1, "Plant QMS", "APPROVED", [task("Plant Person", "APPROVED", "2026-10-24T09:00:00Z")]),
        step(2, "Corporate QMS", "ACTIVE", [task("Corporate Person", "OPEN")]),
      ]),
    );

    expect(flow.map((one) => one.current)).toEqual([false, false, true]);
    expect(flow[1]).toMatchObject({ state: "Approved", person: "Plant Person", tone: "done" });
    expect(flow[2]).toMatchObject({ state: "Pending", person: "Waiting for Corporate Person", tone: "current" });
  });

  it("says how many people a step waits for when there are several", () => {
    const flow = approvalFlow(
      request("PENDING", [step(1, "Plant QMS", "ACTIVE", [task("One", "OPEN"), task("Two", "OPEN"), task("Three", "OPEN")])]),
    );

    expect(flow[1]?.person).toBe("Waiting for 3 people");
  });

  it("shows the person who approved a step that needed any one, not everybody it asked", () => {
    const flow = approvalFlow(
      request("PENDING", [
        step(1, "Plant QMS", "APPROVED", [
          task("Asked First", "CLOSED"),
          task("Decided It", "APPROVED", "2026-10-24T09:00:00Z"),
          task("Asked Last", "CLOSED"),
        ]),
        step(2, "Corporate QMS", "ACTIVE", []),
      ]),
    );

    expect(flow[1]?.person).toBe("Decided It");
    expect(flow[2]?.person).toBe("Nobody to ask yet");
  });

  it("draws a step that was not reached muted and names nobody", () => {
    const flow = approvalFlow(
      request("PENDING", [step(1, "Plant QMS", "ACTIVE", [task("A", "OPEN")]), step(2, "Corporate QMS", "WAITING")]),
    );

    expect(flow[2]).toMatchObject({ state: "Not reached", tone: "muted", person: "", current: false });
  });

  it("draws a skipped step muted with the tag Skipped, and the dot goes on to the next one", () => {
    const flow = approvalFlow(
      request("PENDING", [step(1, "Plant QMS", "SKIPPED"), step(2, "Corporate QMS", "ACTIVE", [task("C", "OPEN")])]),
    );

    expect(flow[1]).toMatchObject({ state: "Skipped", tone: "muted", person: "", current: false });
    expect(flow[2]?.current).toBe(true);
  });

  it("puts the dot on the last step of an approved request, in the colour of approval", () => {
    const flow = approvalFlow(
      request("APPROVED", [
        step(1, "Plant QMS", "APPROVED", [task("P", "APPROVED", "2026-10-24T09:00:00Z")]),
        step(2, "Corporate QMS", "APPROVED", [task("C", "APPROVED", "2026-10-24T10:00:00Z")]),
      ]),
    );

    expect(flow.map((one) => one.current)).toEqual([false, false, true]);
    expect(flow[2]).toMatchObject({ state: "Approved", tone: "done", person: "C" });
  });

  it("puts the dot on the rejected step, in red, and leaves the steps after it unreached", () => {
    const flow = approvalFlow(
      request("REJECTED", [
        step(1, "Plant QMS", "REJECTED", [task("Plant Person", "REJECTED", "2026-10-24T09:00:00Z")]),
        step(2, "Corporate QMS", "WAITING"),
      ]),
    );

    expect(flow[1]).toMatchObject({ state: "Rejected", tone: "rejected", person: "Plant Person", current: true });
    expect(flow[2]).toMatchObject({ state: "Not reached", tone: "muted", current: false });
  });

  it("says a withdrawn or ended request was withdrawn or ended, not pending", () => {
    const recalled = approvalFlow(request("RECALLED", [step(1, "Plant QMS", "ACTIVE", [task("P", "CLOSED")])]));
    const cancelled = approvalFlow(request("CANCELLED", [step(1, "Plant QMS", "ACTIVE", [task("P", "CLOSED")])]));

    expect(recalled[1]).toMatchObject({ state: "Withdrawn", tone: "muted", person: "", current: true });
    expect(cancelled[1]).toMatchObject({ state: "Ended", current: true });
  });

  it("names a person who left as the screens do, and never an identifier", () => {
    const flow = approvalFlow(
      request(
        "PENDING",
        [step(1, "Plant QMS", "APPROVED", [task(undefined, "APPROVED", "2026-10-24T09:00:00Z")]), step(2, "Corporate QMS", "WAITING")],
        person(undefined, "0198f5c0-1111"),
      ),
    );

    expect(flow[0]?.person).toBe("someone who is no longer a member");
    expect(flow[1]?.person).toBe("someone who is no longer a member");
    expect(JSON.stringify(flow)).not.toContain("0198f5c0");
  });

  it("is what the API sent and nothing more: a request with no steps is only its first line", () => {
    const flow = approvalFlow(request("PENDING", []));

    expect(flow).toHaveLength(1);
    expect(line(request("PENDING", []), 0)?.current).toBe(true);
  });
});
