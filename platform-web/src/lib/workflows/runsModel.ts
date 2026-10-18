import type { components } from "@/lib/api/generated/schema";

type Execution = components["schemas"]["WorkflowExecutionView"];
type Step = components["schemas"]["WorkflowStepView"];
type ReplayResult = components["schemas"]["WorkflowReplayResultView"];

/*
 * Words for the runs of workflows (Sprint 23, ADR-0141 to ADR-0143). Pure: no React, no network. The API sends stable keys
 * (a status, a reason); this turns them into sentences a person reads, and says what a button would do before it is
 * pressed. It decides nothing: whether a run can be replayed or cancelled is the API's answer when the button is used, and a
 * key it does not know is shown as it is, made readable.
 */

/** The statuses of a run, in the order a person thinks of them. */
export const STATUS_TEXT: Record<string, string> = {
  QUEUED: "Waiting",
  RUNNING: "Running",
  COMPLETED: "Completed",
  SKIPPED: "Skipped",
  CANCELLED: "Cancelled",
  DEAD_LETTERED: "Needs a person",
  FAILED: "Failed",
};

export const STEP_STATUS_TEXT: Record<string, string> = {
  RUNNING: "Running",
  RETRYING: "Waiting for another attempt",
  COMPLETED: "Done",
  FAILED: "Failed",
};

/** What a stable reason key means, in words. */
export const REASON_TEXT: Record<string, string> = {
  condition_false: "The condition was not true for the record.",
  condition_not_evaluable: "The condition could not be worked out for the record.",
  record_gone: "The record is gone.",
  record_restored: "The record was brought back from the recycle bin.",
  record_in_bin: "The record is in the recycle bin, where it cannot be changed.",
  workflow_inactive: "The workflow was switched off.",
  workflow_removed: "The workflow was removed.",
  version_missing: "The version of the workflow it was made under is gone.",
  feature_off: "The plan of the organization has no workflows.",
  object_missing: "The object is gone.",
  object_unavailable: "The records of this object are not kept by the organization.",
  retry_scheduled: "It failed in a way that may pass, and waits for another attempt.",
  retries_exhausted: "It failed again and again in a way that may pass, and the attempts are spent.",
  transient_failure: "A temporary problem with the database or its connection.",
  concurrent_modification: "Someone changed the record at the same moment.",
  loop_detected: "Stopped: this workflow was already part of the chain of changes that led here.",
  depth_exceeded: "Stopped: too many workflows in a row started one another.",
  rate_limited: "Delayed: the object started more runs in a minute than the platform allows.",
  mail_rate_limited: "Delayed: the organization reached the number of mails its workflows may queue in an hour.",
  cancelled_by_person: "Cancelled by a person.",
  attempts_exhausted: "The platform tried to run it several times and each time the worker stopped.",
  decision_failed: "The workflow or the record could not be read.",
  action_failed: "The action failed for a reason the platform could not name.",
  unknown_action_type: "This release does not carry out this kind of action.",
  invalid_value: "A value does not fit its field.",
  field_not_writable: "A field cannot be set: it is worked out by the platform, retired, or not a field of the object.",
  record_type_rejected: "The record type of the record does not allow it.",
  required_missing: "A required field would have no value.",
  link_target_gone: "A linked record does not exist.",
  unique_clash: "A value that must be unique is already in use.",
  rejected_by_rule: "A validation rule rejected the record.",
  formula_failed: "A formula could not be worked out.",
  recipient_missing: "The record names nobody to send the mail to.",
  recipient_not_member: "The person the record names is not an active member of the organization.",
  recipient_invalid: "The address the record names is not an address.",
  recipient_field_gone: "The field that names the recipient is gone.",
  replay_failed: "It could not be replayed now.",
};

/** A key in words: the sentence we know, or the key made readable. */
export function reasonText(reason: string | undefined | null): string {
  if (!reason) {
    return "";
  }
  return REASON_TEXT[reason] ?? readable(reason);
}

export function statusText(status: string): string {
  return STATUS_TEXT[status] ?? readable(status);
}

export function stepStatusText(status: string): string {
  return STEP_STATUS_TEXT[status] ?? readable(status);
}

function readable(code: string): string {
  const words = code.toLowerCase().replace(/_/g, " ").trim();
  return words === "" ? code : words.charAt(0).toUpperCase() + words.slice(1);
}

// ---- what can be done with a run ----

/** The step a replay would resume at: the one that failed or waits, else the one after those that completed. */
export function resumeStepOf(execution: Execution): number {
  const steps = execution.steps ?? [];
  const failed = steps.find((step) => step.status === "FAILED" || step.status === "RETRYING" || step.status === "RUNNING");
  if (failed) {
    return failed.position;
  }
  return steps.filter((step) => step.status === "COMPLETED").length + 1;
}

export function failedStepOf(execution: Execution): Step | undefined {
  return (execution.steps ?? []).find((step) => step.status === "FAILED");
}

/**
 * Whether the page offers Replay for the run: it needs a person, and it was not replayed already. A hint; the API decides
 * when the button is used and says why when it cannot.
 */
export function offersReplay(execution: Execution): boolean {
  return (execution.status === "DEAD_LETTERED" || execution.status === "FAILED") && !execution.replayedBy;
}

/** Whether the page offers Cancel: only a run that has not started, which includes one waiting for another attempt. */
export function offersCancel(execution: Execution): boolean {
  return execution.status === "QUEUED";
}

/** Exactly what a replay will do, said before it is done. */
export function replayConfirmation(execution: Execution): string {
  const step = resumeStepOf(execution);
  const named = (execution.steps ?? []).find((one) => one.position === step);
  const where = named ? `step ${step} (${named.name})` : `step ${step}`;
  return (
    `This makes one new run that starts at ${where}. The steps before it are not run again, the condition is not ` +
    "judged again, and it works on the record as it is now. A run can be replayed once."
  );
}

export function bulkReplayConfirmation(count: number): string {
  return (
    `This makes a new run for each of the ${count} selected ${count === 1 ? "run" : "runs"}, each starting at the step ` +
    "that failed. Steps that finished are not run again and conditions are not judged again. A run that cannot be " +
    "replayed is skipped and the others go on."
  );
}

/** What happened to one run of a replay, in words. */
export function replayResultText(result: ReplayResult): string {
  switch (result.outcome) {
    case "REPLAYED":
      return "A new run was queued.";
    case "ALREADY_REPLAYED":
      return "It was replayed before; the run that replays it already exists.";
    case "NOT_FOUND":
      return "There is no such run.";
    default:
      return result.reason === "not_dead_lettered"
        ? "Only a run that needs a person can be replayed."
        : `It could not be replayed. ${reasonText(result.reason)}`.trim();
  }
}
