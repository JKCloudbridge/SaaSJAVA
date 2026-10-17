import { describe, expect, it } from "vitest";
import { deadRun, run } from "@/test/workflows";
import {
  bulkReplayConfirmation,
  failedStepOf,
  offersCancel,
  offersReplay,
  reasonText,
  REASON_TEXT,
  replayConfirmation,
  replayResultText,
  resumeStepOf,
  statusText,
  stepStatusText,
} from "./runsModel";

describe("the words of a run", () => {
  it("puts a status and a reason in words, and an unknown key in readable form instead of hiding it", () => {
    expect(statusText("DEAD_LETTERED")).toBe("Needs a person");
    expect(statusText("QUEUED")).toBe("Waiting");
    expect(statusText("SOMETHING_NEW")).toBe("Something new");
    expect(reasonText("unique_clash")).toBe("A value that must be unique is already in use.");
    expect(reasonText("a_reason_of_a_later_release")).toBe("A reason of a later release");
    expect(reasonText(undefined)).toBe("");
    expect(stepStatusText("RETRYING")).toBe("Waiting for another attempt");
  });

  it("has a sentence for every stable reason of the data, the retries, the loops and the mail, with no value in it", () => {
    for (const key of ["rejected_by_rule", "required_missing", "link_target_gone", "invalid_value", "formula_failed",
      "retry_scheduled", "retries_exhausted", "loop_detected", "depth_exceeded", "rate_limited", "recipient_missing",
      "recipient_not_member", "cancelled_by_person", "attempts_exhausted", "concurrent_modification"]) {
      expect(REASON_TEXT[key], key).toBeTruthy();
    }
    for (const sentence of Object.values(REASON_TEXT)) {
      expect(sentence).not.toMatch(/\{\{|undefined/);
    }
  });
});

describe("what can be done with a run", () => {
  it("offers a replay for a run that needs a person and was not replayed yet, and a cancel for one that waits", () => {
    expect(offersReplay(deadRun("d1"))).toBe(true);
    expect(offersReplay(deadRun("d2", { replayedBy: "r1" }))).toBe(false);
    expect(offersReplay(run("c1"))).toBe(false);
    expect(offersReplay(run("f1", { status: "FAILED" }))).toBe(true);
    expect(offersCancel(run("q1", { status: "QUEUED" }))).toBe(true);
    expect(offersCancel(run("r1", { status: "RUNNING" }))).toBe(false);
    expect(offersCancel(deadRun("d1"))).toBe(false);
  });

  it("finds the step a replay resumes at: the one that failed, else the one after those that completed", () => {
    expect(resumeStepOf(deadRun("d1"))).toBe(2);
    expect(failedStepOf(deadRun("d1"))?.name).toBe("Tell");
    expect(resumeStepOf(run("x", { steps: [] }))).toBe(1);
    expect(resumeStepOf(run("x", { steps: [{ position: 1, name: "A", type: "T", status: "COMPLETED", startedAt: "2026-01-01T00:00:00Z", attempts: 1 }] }))).toBe(2);
    expect(resumeStepOf(run("x", { steps: [{ position: 1, name: "A", type: "T", status: "RETRYING", startedAt: "2026-01-01T00:00:00Z", attempts: 1 }] }))).toBe(1);
  });

  it("says exactly what a replay will do before it is done", () => {
    const text = replayConfirmation(deadRun("d1"));

    expect(text).toContain("one new run");
    expect(text).toContain("step 2 (Tell)");
    expect(text).toContain("steps before it are not run again");
    expect(text).toContain("condition is not judged again");
    expect(text).toContain("as it is now");
    expect(text).toContain("once");
  });

  it("says what a replay of many will do and counts them", () => {
    expect(bulkReplayConfirmation(1)).toContain("1 selected run");
    expect(bulkReplayConfirmation(5)).toContain("5 selected runs");
    expect(bulkReplayConfirmation(5)).toContain("skipped and the others go on");
  });

  it("puts the result of each item of a replay in words", () => {
    expect(replayResultText({ execution: "a", outcome: "REPLAYED", replay: "b" })).toBe("A new run was queued.");
    expect(replayResultText({ execution: "a", outcome: "ALREADY_REPLAYED", replay: "b" })).toContain("replayed before");
    expect(replayResultText({ execution: "a", outcome: "NOT_FOUND" })).toBe("There is no such run.");
    expect(replayResultText({ execution: "a", outcome: "NOT_REPLAYABLE", reason: "not_dead_lettered" })).toContain(
      "Only a run that needs a person",
    );
    expect(replayResultText({ execution: "a", outcome: "NOT_REPLAYABLE", reason: "replay_failed" })).toContain(
      "could not be replayed",
    );
  });
});
