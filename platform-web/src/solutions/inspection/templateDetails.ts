import type { ArrangedSection } from "@/lib/records/columns";
import { formatInstant } from "@/lib/records/fieldValue";
import type { ObjectForm, RecordView } from "@/lib/records/types";
import { revisionNumber } from "@/lib/sheets/revision";
import type { TemplateLifecycleView } from "./api";
import { text } from "./template";

/**
 * The arrangement of the Details tab of a template (Functional track FA11A, ADR-0205). The cluster owns it: the generic
 * record screens draw whatever sections they are given, and this module gives them fixed rows of two (FA11B). It only orders and
 * words what the API sent; the form still holds only the fields the member may read, and a field that is not there is not
 * drawn. Pure, so it is tested without a browser.
 */

/**
 * The rows of the Details tab, the same in reading and in editing (FA11B, ADR-0206): the name; the level beside the kind of
 * document; the identification beside the Document number (which the edit form draws with its serial box); Part A beside
 * Part B; the Revision number beside Obsolete. A row whose fields are not drawn leaves no gap, and Part A and Part B are
 * drawn only when the identification asks for them (the page hides the others).
 */
const ROWS = [["name"], ["level", "documentType"], ["category", "number"], ["partA", "partB"], ["revision", "retired"]] as const;
/** Drawn by the number field of the form, never as a field of their own. */
const IN_NUMBER = ["serial"] as const;

/**
 * The Revision in use as the Sheet tab writes it (two digits, 00 first). The platform saves it when a Revision is published;
 * a template published before that was saved has none, and then it is read from the sheet's Revision in use by the same
 * rule as everywhere else. Nothing before the first Revision is in use.
 */
export function revisionNumberOf(view: Pick<TemplateLifecycleView, "revision" | "lifecycle"> | undefined): string | undefined {
  if (view === undefined) {
    return undefined;
  }
  if (view.revision) {
    return view.revision;
  }
  return view.lifecycle.activeVersion > 0 ? revisionNumber(view.lifecycle.activeVersion) : undefined;
}

/**
 * The words to show for the fields the page works out: the Revision number and the date of the Revision in use, which is
 * stored as text and read here in the reader's own time zone. A field with nothing to show is not in the answer.
 */
export function detailDisplay(
  view: Pick<TemplateLifecycleView, "revision" | "lifecycle" | "lastRevisedAt"> | undefined,
  record: RecordView,
): Record<string, string> {
  const display: Record<string, string> = {};
  const revision = revisionNumberOf(view);
  if (revision !== undefined) {
    display.revision = revision;
  }
  const revised = text(record.fields.lastRevisedAt) || view?.lastRevisedAt || "";
  if (revised !== "") {
    display.lastRevisedAt = formatInstant(revised);
  }
  return display;
}

/**
 * The form with the sections of the Details tab. Reading leaves out the Revision number before a first Revision is in use;
 * editing offers every field the member can see. Which of Part A and Part B are drawn is told by the page (the server's
 * numbering-info answer), not decided here. The second section holds the date of the Revision in use and the system fields
 * of the original layout.
 */
export function templateSections(
  form: ObjectForm,
  view: Pick<TemplateLifecycleView, "revision" | "lifecycle"> | undefined,
  editing: boolean,
): ObjectForm {
  const known = new Set(form.fields.map((field) => field.apiName));
  const system = form.fields.filter((field) => field.kind === "SYSTEM").map((field) => field.apiName);
  const rowNames = ROWS.flat() as string[];
  const named = new Set<string>([...rowNames, ...IN_NUMBER, "lastRevisedAt", ...system]);
  const other = form.fields.map((field) => field.apiName).filter((name) => !named.has(name));

  const wanted = (name: string) => {
    if (!known.has(name)) {
      return false;
    }
    return editing || name !== "revision" || revisionNumberOf(view) !== undefined;
  };

  const fields = [...rowNames, ...IN_NUMBER, ...other].filter(wanted);
  const sections: ArrangedSection[] = [{ label: "Details", columns: 2, fields, rows: ROWS }];
  const bottom = ["lastRevisedAt", ...system].filter((name) => known.has(name));
  if (bottom.length > 0) {
    sections.push({ label: "System information", columns: 2, fields: bottom });
  }
  return { ...form, layout: { ...form.layout, sections } };
}
