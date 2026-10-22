/**
 * The words people read for the status of a version (ADR-0174 section 1, ADR-0180). The status itself is the API's; this
 * only says how it is written on a screen. "Active" is the tag of the version in use.
 */

export type VersionTone = "active" | "draft" | "review" | "done" | "stopped";

const LABELS: Record<string, { text: string; tone: VersionTone }> = {
  DRAFT: { text: "Draft", tone: "draft" },
  IN_REVIEW: { text: "In review", tone: "review" },
  APPROVED: { text: "Approved", tone: "review" },
  ACTIVE: { text: "Active", tone: "active" },
  SUPERSEDED: { text: "Replaced", tone: "done" },
  REJECTED: { text: "Rejected", tone: "stopped" },
  WITHDRAWN: { text: "Withdrawn", tone: "stopped" },
  RETIRED: { text: "Retired", tone: "done" },
};

/** The words and the look of a status. A status this release does not know is shown as it is. */
export function statusTag(status: string): { text: string; tone: VersionTone } {
  return LABELS[status] ?? { text: status, tone: "done" };
}

/** Whether the grid of a version with this status can still be written. */
export function isDraft(status: string): boolean {
  return status === "DRAFT";
}
