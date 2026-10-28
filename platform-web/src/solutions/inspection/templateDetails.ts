import type { ArrangedSection } from "@/lib/records/columns";
import { formatInstant } from "@/lib/records/fieldValue";
import type { ObjectForm, RecordView } from "@/lib/records/types";
import { revisionNumber } from "@/lib/sheets/revision";
import type { TemplateLifecycleView } from "./api";
import { text } from "./template";

/**
 * The arrangement of the Details tab of a template (Functional track FA11A, ADR-0205). The cluster owns it: the generic
 * record screens draw whatever sections they are given, and this module gives them two fixed columns. It only orders and
 * words what the API sent; the form still holds only the fields the member may read, and a field that is not there is not
 * drawn. Pure, so it is tested without a browser.
 */

/** The left column, top to bottom. Part code and Plan year follow the others when they are drawn at all. */
const LEFT = ["name", "level", "documentType", "category", "subCategory", "partCode", "planYear"] as const;
/** The right column, top to bottom; Obsolete is last. */
const RIGHT = ["number", "revision", "retired"] as const;

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
 * The form with the sections of the Details tab. Reading leaves out Part code and Plan year when the template has no value
 * for them and the Revision number before a first Revision is in use; editing offers every field the member can see, since
 * a value may still be needed (the form shows them only when the category needs them from FA11B on). The second section
 * holds the date of the Revision in use and the system fields of the original layout.
 */
export function templateSections(
  form: ObjectForm,
  record: RecordView,
  view: Pick<TemplateLifecycleView, "revision" | "lifecycle"> | undefined,
  editing: boolean,
): ObjectForm {
  const known = new Set(form.fields.map((field) => field.apiName));
  const system = form.fields.filter((field) => field.kind === "SYSTEM").map((field) => field.apiName);
  const named = new Set<string>([...LEFT, ...RIGHT, "lastRevisedAt", ...system]);
  const other = form.fields.map((field) => field.apiName).filter((name) => !named.has(name));

  const has = (name: string) => text(record.fields[name]) !== "";
  const wanted = (name: string) => {
    if (!known.has(name)) {
      return false;
    }
    if (editing) {
      return true;
    }
    if (name === "partCode" || name === "planYear") {
      return has(name);
    }
    return name !== "revision" || revisionNumberOf(view) !== undefined;
  };

  const left = [...LEFT, ...other].filter(wanted);
  const right = RIGHT.filter(wanted);
  const sections: ArrangedSection[] = [{ label: "Details", columns: 2, fields: [...left, ...right], left }];
  const bottom = ["lastRevisedAt", ...system].filter((name) => known.has(name));
  if (bottom.length > 0) {
    sections.push({ label: "System information", columns: 2, fields: bottom });
  }
  return { ...form, layout: { ...form.layout, sections } };
}
