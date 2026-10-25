import type { components } from "@/lib/api/generated/schema";
import { personText } from "./words";

type Instance = components["schemas"]["ApprovalInstanceView"];

/**
 * The thin flow of one approval request (FA6D), made from what the API answered and nothing else. It is a reading aid:
 * every word is the API's (a step's label, a person's name, a state) put in a fixed order, and no decision is taken here.
 * Pure, so it is tested without a browser.
 */

/** How a step is marked: the colour of its dot and its tag. */
export type FlowTone = "current" | "done" | "rejected" | "muted";

export interface FlowStep {
  key: string;
  /** What the step is called: the process step's own label, or "Submitted" for the first line. */
  label: string;
  /** The state, in words (also the only thing a screen reader needs). */
  state: string;
  tone: FlowTone;
  /** One short line: who submitted, who decided, or who the step waits for. Empty when there is nobody to name. */
  person: string;
  /** The step where the request is now, or where it ended. */
  current: boolean;
}

const WORDS: Record<string, { state: string; tone: FlowTone }> = {
  ACTIVE: { state: "Pending", tone: "current" },
  APPROVED: { state: "Approved", tone: "done" },
  REJECTED: { state: "Rejected", tone: "rejected" },
  SKIPPED: { state: "Skipped", tone: "muted" },
  WAITING: { state: "Not reached", tone: "muted" },
};

/** What a step that was open is called once the request has ended without a decision on it. */
const ENDED: Record<string, string> = { RECALLED: "Withdrawn", CANCELLED: "Ended" };

/** The flow of a request, first line (the person who submitted) then one line per approval step in the process's order. */
export function approvalFlow(instance: Instance): FlowStep[] {
  const status = instance.summary.status;
  const pending = status === "PENDING";
  const steps = [...instance.steps].sort((a, b) => a.position - b.position);

  const lines: FlowStep[] = [
    {
      key: "submitted",
      label: "Submitted",
      state: "Done",
      tone: "done",
      person: personText(instance.summary.submittedBy),
      current: false,
    },
  ];

  for (const step of steps) {
    const open = step.assignments.filter((task) => task.status === "OPEN");
    const decided = (kind: string) =>
      step.assignments
        .filter((task) => task.status === kind)
        .sort((a, b) => (a.decidedAt ?? "").localeCompare(b.decidedAt ?? ""))[0];

    let { state, tone } = WORDS[step.status] ?? { state: step.status, tone: "muted" as FlowTone };
    if (!pending && step.status === "ACTIVE") {
      state = ENDED[status] ?? "Ended";
      tone = "muted";
    }

    let person = "";
    if (step.status === "APPROVED" || step.status === "REJECTED") {
      // A step that needs any one shows the person who decided it, not everybody the step could have asked.
      const who = decided(step.status);
      person = who ? personText(who.assignee) : "";
    } else if (step.status === "ACTIVE" && pending) {
      person =
        open.length > 1
          ? `Waiting for ${open.length} people`
          : open.length === 1
            ? `Waiting for ${personText(open[0]?.assignee)}`
            : "Nobody to ask yet";
    }

    lines.push({ key: `step-${step.position}`, label: step.label, state, tone, person, current: false });
  }

  return markCurrent(lines, status, steps.map((step) => step.status));
}

/**
 * Where the request is now. While it is pending: the first step that is not finished. Approved: the last line. Any other
 * ending: the last line that was reached (a step that was never reached is not where it ended).
 */
function markCurrent(lines: FlowStep[], status: string, stepStatuses: string[]): FlowStep[] {
  let at = lines.length - 1;
  if (status === "PENDING") {
    const open = stepStatuses.findIndex((one) => one === "ACTIVE" || one === "WAITING");
    at = open === -1 ? lines.length - 1 : open + 1;
  } else if (status !== "APPROVED") {
    const reached = stepStatuses.map((one) => one !== "WAITING");
    at = 0;
    reached.forEach((yes, index) => {
      if (yes) {
        at = index + 1;
      }
    });
  }
  return lines.map((line, index) => {
    if (index !== at) {
      return line;
    }
    // The dot is green where the request is now (a rejected step stays red; a request that ended is not pending any more).
    const tone: FlowTone = line.tone === "muted" && status === "PENDING" ? "current" : line.tone;
    return { ...line, tone, current: true };
  });
}
