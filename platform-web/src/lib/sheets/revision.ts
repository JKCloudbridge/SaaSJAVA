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
 * The name a downloaded sheet is saved under: the template number and the revision, a slash of the number becoming a
 * hyphen (`F-MR-16_Rev00.xlsx`). The server offers the same name; this one is used when the browser saves the file.
 */
export function downloadName(number: string | null | undefined, stored: number): string {
  const base = number === null || number === undefined || number.trim() === "" ? "Template" : number.trim().replace(/[^A-Za-z0-9._-]/g, "-");
  return `${base}_Rev${revisionNumber(stored)}.xlsx`;
}

/**
 * The text at the right of the footer row of a sheet: the template number, two spaces, `Rev No.` and the revision in
 * single quotes, for example `F/MR/16  Rev No.'01'`. Blank until the template has a number. The footer is made when the
 * sheet is shown, printed and downloaded; it is never part of the stored grid.
 */
export function footerText(number: string | null | undefined, stored: number): string {
  if (number === null || number === undefined || number.trim() === "") {
    return "";
  }
  return `${number.trim()}  Rev No.'${revisionNumber(stored)}'`;
}
