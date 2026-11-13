/**
 * How a version of a sheet is spoken of to people (FA7, ADR-0201): as a revision, with two digits, starting at 00. The
 * stored number and the API's field names are not changed and not renumbered: the first version a sheet has is stored as
 * 1 and read as Revision 00, the next as Revision 01. The server writes the same rule for its own sentences and for the
 * file (`Revisions`, `TemplateSheetTexts`) and the same cases are tested on both sides, so the screen and the file agree.
 */

/** The revision as people read it: the stored number minus one, at least two digits (1 gives 00, 2 gives 01). */
export function revisionNumber(stored: number): string {
  return String(Math.max(0, stored - 1)).padStart(2, "0");
}

/** `Revision 00`: the words that name a version in a sentence. */
export function revisionWords(stored: number): string {
  return `Revision ${revisionNumber(stored)}`;
}

/**
 * The revision number of a version as people read it (FA8, ADR-0202). The server gives a version its revision number when
 * it first becomes the one in use, so a copy that was rejected never uses one and the numbers have no gaps; a version that
 * was in use before numbers were stored has none and is read as its stored number minus one.
 */
export function revisionOf(summary: { versionNo: number; revisionNo?: number | null }): string {
  return summary.revisionNo === undefined || summary.revisionNo === null
    ? revisionNumber(summary.versionNo)
    : String(Math.max(0, summary.revisionNo)).padStart(2, "0");
}

/** `Revision 01` for a version that has a revision number (or was in use); see {@link revisionOf}. */
export function revisionWordsOf(summary: { versionNo: number; revisionNo?: number | null }): string {
  return `Revision ${revisionOf(summary)}`;
}

/**
 * What a version is called in a list: its revision once it was in use; before that, what it is, since a revised copy is not
 * a revision until it is published (a copy that is rejected never becomes one).
 */
export function versionTitle(summary: { versionNo: number; status: string; revisionNo?: number | null }): string {
  if (summary.revisionNo !== undefined && summary.revisionNo !== null) {
    return revisionWordsOf(summary);
  }
  switch (summary.status) {
    case "ACTIVE":
    case "SUPERSEDED":
    case "RETIRED":
      return revisionWordsOf(summary);
    case "DRAFT":
      return "Draft";
    case "IN_REVIEW":
      return "In review";
    case "APPROVED":
      return "Approved, not published";
    case "REJECTED":
      return "Rejected copy";
    case "WITHDRAWN":
      return "Withdrawn copy";
    default:
      return revisionWordsOf(summary);
  }
}

/**
 * The name a downloaded sheet is saved under: the template number and the revision, a slash of the number becoming a
 * hyphen (`F-MR-16_Rev00.xlsx`). The server offers the same name; this one is used when the browser saves the file.
 */
export function downloadName(number: string | null | undefined, stored: number): string {
  return downloadNameFor(number, revisionNumber(stored));
}

/** As {@link downloadName}, for a revision that is already written with its digits (FA8). */
export function downloadNameFor(number: string | null | undefined, revision: string): string {
  const base = number === null || number === undefined || number.trim() === "" ? "Template" : number.trim().replace(/[^A-Za-z0-9._-]/g, "-");
  return `${base}_Rev${revision}.xlsx`;
}

/**
 * The text at the right of the footer row of a sheet: the template number, two spaces, `Rev No.` and the revision in
 * single quotes, for example `F/MR/16  Rev No.'01'`. Blank until the template has a number. The footer is made when the
 * sheet is shown, printed and downloaded; it is never part of the stored grid.
 */
export function footerText(number: string | null | undefined, stored: number): string {
  return footerTextFor(number, revisionNumber(stored));
}

/** As {@link footerText}, for a revision that is already written with its digits (FA8). */
export function footerTextFor(number: string | null | undefined, revision: string): string {
  if (number === null || number === undefined || number.trim() === "") {
    return "";
  }
  return `${number.trim()}  Rev No.'${revision}'`;
}

/**
 * The day a Revision was published as the footer writes it: two digits, the English month in three letters and the year
 * (`13-Nov-2026`), in the reader's own time zone unless one is given. The server writes the same words for the file with
 * its own zone setting; both read the cases of `footer-cases.txt` (MC1, ADR-0257).
 */
export function footerDay(instant: string, timeZone?: string): string | undefined {
  const at = new Date(instant);
  if (Number.isNaN(at.getTime())) {
    return undefined;
  }
  const parts = new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone })
    .formatToParts(at);
  const pick = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
  return `${pick("day")}-${pick("month")}-${pick("year")}`;
}

/**
 * The footer of an application whose footer carries the day (MC1): the line of {@link footerTextFor}, then below it
 * `Date: 13-Nov-2026`. Without a day, or before the template has a number, it is the first line alone (or nothing).
 */
export function footerTextWithDay(
  number: string | null | undefined,
  revision: string,
  publishedAt: string | null | undefined,
  timeZone?: string,
): string {
  const first = footerTextFor(number, revision);
  if (first === "" || !publishedAt) {
    return first;
  }
  const day = footerDay(publishedAt, timeZone);
  return day === undefined ? first : `${first}\nDate: ${day}`;
}
