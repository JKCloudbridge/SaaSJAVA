/**
 * How the lifecycle of a template is written on a screen (Functional track FA4). The numbers are the API's: which version
 * is in use, which is approved and waiting, which is being reviewed, which is a draft. This only says them in words.
 */

export interface LifecycleNumbers {
  state: string;
  activeVersion: number;
  approvedVersion: number;
  inReviewVersion: number;
  draftVersion: number;
  retiredVersion: number;
  highestVersion: number;
}

/** The tag of the template as a whole: the word, and the look the tag takes. */
export function stateTag(state: string): { text: string; tone: "active" | "draft" | "review" | "done" | "stopped" } {
  switch (state) {
    case "ACTIVE":
      return { text: "Active", tone: "active" };
    case "APPROVED":
      return { text: "Approved, not published", tone: "review" };
    case "IN_REVIEW":
      return { text: "In review", tone: "review" };
    case "DRAFT":
      return { text: "Draft", tone: "draft" };
    case "RETIRED":
      return { text: "Retired", tone: "done" };
    case "NONE":
      return { text: "No version yet", tone: "draft" };
    default:
      return { text: state, tone: "done" };
  }
}

/**
 * "Active v3, v4 in review": the version in use first, then what waits behind it. A retired template says so and which
 * version was the last in use. Nothing is invented: a number that is 0 is not mentioned.
 */
export function lifecycleText(lifecycle: LifecycleNumbers): string {
  if (lifecycle.state === "RETIRED") {
    return lifecycle.retiredVersion > 0 ? `Retired, v${lifecycle.retiredVersion} was the last in use` : "Retired";
  }
  const parts: string[] = [];
  if (lifecycle.activeVersion > 0) {
    parts.push(`Active v${lifecycle.activeVersion}`);
  }
  if (lifecycle.approvedVersion > 0) {
    parts.push(`v${lifecycle.approvedVersion} approved, not published`);
  }
  if (lifecycle.inReviewVersion > 0) {
    parts.push(`v${lifecycle.inReviewVersion} in review`);
  }
  if (lifecycle.draftVersion > 0) {
    parts.push(`v${lifecycle.draftVersion} draft`);
  }
  return parts.length === 0 ? "No version yet" : parts.join(", ");
}
