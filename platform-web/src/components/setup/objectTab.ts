import type { Crumb } from "@/components/ui/lists/Breadcrumb";
import type { components } from "@/lib/api/generated/schema";
import { OBJECT_SETTINGS_HREF, objectSectionHref } from "@/lib/shell/areas";
import type { Authorship } from "./authorship";
import type { FieldTypeView } from "./objectText";

type ObjectView = components["schemas"]["ObjectView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

/** What the page of an object hands to the tab it shows (UA3, ADR-0170). A tab asks the API for the rest itself. */
export interface ObjectTabProps {
  object: ObjectView;
  types: FieldTypeView[];
  objects: ObjectSummary[];
  /** Reads the object again, after a change to it or to anything it holds. */
  reload: () => Promise<void>;
  /** Bumped every time the object was read again, so a tab that holds its own copy of something knows to ask again. */
  refreshToken: number;
  authorship: Authorship;
}

/** The trail of a tab's list: “Object settings / Account / Fields”. */
export function listCrumbs(object: ObjectView, tab: string): Crumb[] {
  return [
    { label: "Object settings", href: OBJECT_SETTINGS_HREF },
    { label: object.label, href: objectSectionHref(object.apiName, "fields") },
    { label: tab },
  ];
}

/** The steps before the list on a detail page of a tab: “Object settings / Account” (the list and the row follow). */
export function detailParents(object: ObjectView): Crumb[] {
  return [
    { label: "Object settings", href: OBJECT_SETTINGS_HREF },
    { label: object.label, href: objectSectionHref(object.apiName, "fields") },
  ];
}

/** The title of a tab's list: “Account — Fields”. */
export const tabTitle = (object: ObjectView, tab: string) => `${object.label} — ${tab}`;
