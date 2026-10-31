import { useOptionalApplications } from "@/lib/applications/ApplicationsProvider";
import { findApplication } from "@/lib/applications/entries";
import { objectRolesOf, rolesOfApplication } from "@/lib/applications/objectRoles";
import { useApplication } from "./application";

/**
 * The objects that hold the templates, the fill-ins and the review records of change requests of the application the page
 * is in (FA13B, ADR-0226). The API lists them with the application; the screens name none. Empty names (an application
 * the member does not have, or a list not loaded yet) make every call answer "not found", never a guess.
 */
export interface RecordObjects {
  template: string;
  fill: string;
  changeRequest: string;
}

/** The part names of the application files; they are the words of these screens. */
export const TEMPLATE_PART = "template";
export const FILL_PART = "fill";
export const REQUEST_PART = "changeRequest";

export function recordObjectsOf(roles: Readonly<Record<string, string>>): RecordObjects {
  return {
    template: roles[TEMPLATE_PART] ?? "",
    fill: roles[FILL_PART] ?? "",
    changeRequest: roles[REQUEST_PART] ?? "",
  };
}

/** The objects of the application the address names. */
export function useRecordObjects(): RecordObjects {
  const application = useApplication();
  const api = useOptionalApplications();
  const found = api ? findApplication(api.state.applications, application) : undefined;
  // The list the shell holds first; the copy the list was remembered in when it arrived serves a page drawn without it.
  const roles = found ? objectRolesOf(found) : rolesOfApplication(application);
  return recordObjectsOf(roles);
}
