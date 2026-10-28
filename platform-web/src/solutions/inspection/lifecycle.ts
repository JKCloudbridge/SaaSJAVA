import { revisionNumber } from "@/lib/sheets/revision";
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
      return { text: "No revision yet", tone: "draft" };
    default:
      return { text: state, tone: "done" };
  }
}

/**
 * "Active Rev 02, in review": the revision in use first, then what waits behind it. A retired template says so and which
 * revision was the last in use. Nothing is invented: a number that is 0 is not mentioned. A version that is not in use yet
 * has no revision number (a revised copy is not a revision until it is published), so it is called by what it is. The
 * revision in use is the platform's saved one when the template carries it (FA8), else the stored number minus one.
 */
export function lifecycleText(lifecycle: LifecycleNumbers, revision?: string | null): string {
  if (lifecycle.state === "RETIRED") {
    if (revision) {
      return `Retired, Rev ${revision} was the last in use`;
    }
    return lifecycle.retiredVersion > 0 ? `Retired, Rev ${revisionNumber(lifecycle.retiredVersion)} was the last in use` : "Retired";
  }
  const parts: string[] = [];
  if (lifecycle.activeVersion > 0) {
    parts.push(`Active Rev ${revision ?? revisionNumber(lifecycle.activeVersion)}`);
  }
  if (lifecycle.approvedVersion > 0) {
    parts.push("Approved, not published");
  }
  if (lifecycle.inReviewVersion > 0) {
    parts.push("In review");
  }
  if (lifecycle.draftVersion > 0) {
    parts.push("Draft");
  }
  return parts.length === 0 ? "No revision yet" : parts.join(", ");
}

const STATE_PARAMS = ["ACTIVE", "APPROVED", "IN_REVIEW", "DRAFT", "NONE", "RETIRED"] as const;

/**
 * The state an address asks the list of templates to show (FA9: the tiles of the home page open it so): one of the
 * states the server knows, or nothing. The server filters; this only refuses a word that is not a state.
 */
export function stateParam(value: string | null | undefined): string {
  return STATE_PARAMS.find((state) => state === value) ?? "";
}

/** "active templates": what the note over a narrowed list calls the slice. */
export function stateParamWords(state: string): string {
  return `${stateTag(state).text.toLowerCase()} templates`;
}
