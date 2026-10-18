import { humanize } from "@/lib/builder/approvalModel";

/*
 * The words the approval screens use for what the API reports (Sprint 24, ADR-0132, ADR-0152). Presentation only: a code the
 * screen does not know is shown made readable ("some_code" becomes "Some code"), never hidden. The screens decide nothing
 * from these words; what a person may do is the API's `actions`.
 */

const REASONS: Record<string, string> = {
  no_approver: "Nobody can be asked to decide this step right now. An administrator can assign someone.",
  approver_unresolvable: "The people for this step cannot be worked out any more. An administrator can assign someone.",
  inactive: "was not an active member",
  unlicensed: "did not hold a licence",
  self_approval_excluded: "submitted the request and the process does not let them decide it",
  record_locked: "The record is locked while it is being approved.",
  rejected_by_rule: "A validation rule refused the change.",
  required_missing: "A required field would be empty.",
  unique_clash: "The value is already in use.",
  invalid_value: "A value does not fit its field.",
  field_not_writable: "A field cannot be set.",
  record_type_rejected: "The record type refused the change.",
  link_target_gone: "A linked record does not exist.",
  formula_failed: "A formula could not be worked out.",
  concurrent_modification: "The record was changed at the same moment.",
  outcome_update_failed: "The update could not be made.",
};

/** The words for a stable reason; the screens add a person's name before them where one is named. */
export function reasonText(reason: string | null | undefined): string {
  if (reason === undefined || reason === null || reason === "") {
    return "";
  }
  return REASONS[reason] ?? humanize(reason);
}

const HISTORY: Record<string, string> = {
  SUBMITTED: "Submitted for approval",
  STEP_ACTIVATED: "Step started",
  TASK_APPROVED: "Approved",
  STEP_APPROVED: "Step approved",
  APPROVED: "Request approved",
  REJECTED: "Rejected",
  RECALLED: "Withdrawn",
  CANCELLED: "Ended by an administrator",
  ASSIGNEE_DROPPED: "Left out",
  REASSIGNED: "Handed on",
  ASSIGNED: "Asked as well",
  ATTENTION_RAISED: "Nobody can decide",
  ATTENTION_CLEARED: "Someone was asked",
  OUTCOME_APPLIED: "The record was updated",
  OUTCOME_FAILED: "The record could not be updated",
  OUTCOME_REAPPLIED: "The update was made again",
  LOCK_OVERRIDDEN: "Changed while locked",
};

/** The words for one line of the history, from its kind. */
export function historyText(kind: string): string {
  return HISTORY[kind] ?? humanize(kind);
}

/** How long ago, in the largest sensible unit: "just now", "5 minutes", "3 hours", "2 days". */
export function ageText(since: string | undefined, now: Date = new Date()): string {
  if (!since) {
    return "";
  }
  const seconds = Math.max(0, Math.floor((now.getTime() - new Date(since).getTime()) / 1000));
  if (seconds < 60) {
    return "just now";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 48) {
    return `${hours} ${hours === 1 ? "hour" : "hours"}`;
  }
  const days = Math.floor(hours / 24);
  return `${days} days`;
}

/** The name of a person the API gave, or "someone who is no longer a member" when it holds none. */
export function personText(person: { userId: string; name?: string } | undefined): string {
  if (!person) {
    return "The platform";
  }
  return person.name && person.name !== "" ? person.name : "someone who is no longer a member";
}
