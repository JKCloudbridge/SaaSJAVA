/** A field value as text for a list or a heading: a string as it is, anything else as nothing. */
export function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}
