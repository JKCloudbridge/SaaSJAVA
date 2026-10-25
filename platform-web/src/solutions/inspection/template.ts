/** The object that holds the templates of the Audit maintenance application (the header record; its sheet is kept in versions). */
export const TEMPLATE_OBJECT = "InspectionTemplate";

/** A field value as text for a list or a heading: a string as it is, anything else as nothing. */
export function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/** The record the approval engine reviews for a change request (the shipped object of the application, FA6E). */
export const REQUEST_OBJECT = "InspectionChangeRequest";
