import type { ImportNote, ImportPlace, ImportReport, ImportWorksheet } from "./api";

/**
 * What the upload of a spreadsheet file needs that is not a request (UI3B, ADR-0195): the kind of file from its name, the
 * size check that spares the person a long upload, and the words for what a file would give. The server decides all of
 * it again; nothing here is a rule of the sheet.
 */

export type FileKind = "xlsx" | "csv";

/** The kind of file by its extension, or null for a file this screen does not offer to read. */
export function kindOfFile(name: string): FileKind | null {
  const lower = name.trim().toLowerCase();
  if (lower.endsWith(".xlsx")) {
    return "xlsx";
  }
  if (lower.endsWith(".csv")) {
    return "csv";
  }
  return null;
}

/** "5 MB" for a limit in bytes, in the words a person uses. */
export function limitWords(bytes: number): string {
  if (bytes >= 1_000_000) {
    const mb = bytes / 1_000_000;
    return `${Number.isInteger(mb) ? mb : mb.toFixed(1)} MB`;
  }
  return bytes >= 1000 ? `${Math.round(bytes / 1000)} KB` : `${bytes} bytes`;
}

/** Why a file cannot be sent at all, or null when it can: the name says the wrong kind, it is empty, or it is too large. */
export function refusalBeforeSending(file: { name: string; size: number }, maxBytes: number | undefined): string | null {
  if (kindOfFile(file.name) === null) {
    return "Choose a spreadsheet saved as .xlsx, or a text file saved as .csv.";
  }
  if (file.size === 0) {
    return "The file is empty.";
  }
  if (maxBytes !== undefined && file.size > maxBytes) {
    return `The file is larger than the allowed ${limitWords(maxBytes)}.`;
  }
  return null;
}

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** "12 columns, 80 rows, 3 merged areas": what the file gives, for the preview. */
export function reportSummary(report: ImportReport): string {
  const rows = report.rows === 0 ? "no named rows" : count(report.rows, "row", "rows");
  const parts = [count(report.columns, "column", "columns"), rows];
  if (report.mergedAreas > 0) {
    parts.push(count(report.mergedAreas, "merged area", "merged areas"));
  }
  return parts.join(", ");
}

/** True when the workbook has more than one visible worksheet, so the person has a choice to make (SH1B). */
export function hasWorksheetChoice(report: ImportReport): boolean {
  return (report.worksheets?.length ?? 0) > 1;
}

/** "Plant activity (about 60 rows, 22 columns)": a worksheet by its title and the size the file says it has. */
export function worksheetWords(worksheet: ImportWorksheet): string {
  const name = worksheet.title ?? `Worksheet ${worksheet.index + 1}`;
  const size =
    worksheet.rows > 0 && worksheet.columns > 0
      ? ` (about ${count(worksheet.rows, "row", "rows")}, ${count(worksheet.columns, "column", "columns")})`
      : "";
  return name + size;
}

/**
 * "B12, C14": cells of the file by their row number and column letters, as a person reads them in the program. When rows
 * above the table did not become rows of the sheet, the sheet's own address differs from the file's (SH5.1, ADR-0263): the
 * place is then given both ways: "B12 in the file (B10 on the sheet)". A place that is no row of the sheet (above the
 * table, or a sheet whose filler adds the rows) has only the file's address. The column letter is always the same in both.
 */
export function placesWords(places: ImportPlace[]): string {
  return places
    .map((place) => {
      const file = `${place.column}${place.row}`;
      const sheetRow = place.sheetRow ?? null;
      return sheetRow === null || sheetRow === place.row ? file : `${file} in the file (${place.column}${sheetRow} on the sheet)`;
    })
    .join(", ");
}

/** What the file held that did not come in, in the server's own words. */
/**
 * The cells where a check box stood that could not be made part of a cell (SH5.1), for the author to set by hand; an
 * older server does not send the list.
 */
export function unplacedBoxes(report: ImportReport): ImportPlace[] {
  return report.unplacedBoxPlaces ?? [];
}

export function leftOut(report: ImportReport): ImportNote[] {
  return report.notes.filter((note) => note.leftOut);
}

/** What came in in another form (a formula as its last value, a date written year-month-day). */
export function changedOnTheWay(report: ImportReport): ImportNote[] {
  return report.notes.filter((note) => !note.leftOut);
}
