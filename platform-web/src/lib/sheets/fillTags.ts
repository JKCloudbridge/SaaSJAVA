import type { VersionTone } from "./tags";

/**
 * The words people read for the status of a fill-in (ADR-0192) and for what became of the version of the sheet it was
 * made on. The statuses are the API's; this only says how they are written on a screen.
 */

const LABELS: Record<string, { text: string; tone: VersionTone }> = {
  DRAFT: { text: "Draft", tone: "draft" },
  SUBMITTED: { text: "Sent for review", tone: "review" },
  RETURNED: { text: "Returned", tone: "stopped" },
  APPROVED: { text: "Approved", tone: "active" },
};

/** The words and the look of a status. A status this release does not know is shown as it is. */
export function fillStatusTag(status: string): { text: string; tone: VersionTone } {
  return LABELS[status] ?? { text: status, tone: "done" };
}

/** What a person should be told about the status, in a sentence. */
export function fillStatusWords(status: string): string {
  switch (status) {
    case "DRAFT":
      return "This fill-in is a draft. Fill it in, save it, then send it for review.";
    case "SUBMITTED":
      return "This fill-in was sent for review. It waits for the decision and cannot be changed meanwhile.";
    case "RETURNED":
      return "This fill-in was returned to you. Change what was asked, save it and send it again.";
    case "APPROVED":
      return "This fill-in was approved.";
    default:
      return "";
  }
}

/**
 * What became of the version of the sheet the fill-in was made on, or an empty text when it is still the one in use. A
 * fill-in always shows against its own version (acceptance criterion 6).
 */
export function versionWords(versionNo: number, versionStatus: string | null | undefined): string {
  switch (versionStatus) {
    case "SUPERSEDED":
      return `This fill-in was made on version ${versionNo} of the template. A newer version is in use now; this fill-in stays on version ${versionNo}.`;
    case "APPROVED":
      return `This fill-in was made on version ${versionNo}. The template is taken back from the people at the moment, and this fill-in stays on that version.`;
    case "RETIRED":
      return `The template this fill-in was made for is retired. The fill-in stays readable on version ${versionNo}, but it can no longer be changed or sent.`;
    default:
      return "";
  }
}
