/**
 * The sections of an object's setup page. One subject per page: the long stack of panels is a row of links, and the
 * section is part of the address (?section=layouts), so a section can be linked to and the back button works.
 */
export const OBJECT_SECTIONS = [
  { id: "fields", label: "Fields" },
  { id: "relationships", label: "Relationships" },
  { id: "record-types", label: "Record types" },
  { id: "layouts", label: "Page layouts" },
  { id: "record-pages", label: "Record pages" },
  { id: "rules", label: "Validation rules" },
  { id: "sharing", label: "Sharing" },
  { id: "details", label: "Details" },
] as const;

export type ObjectSection = (typeof OBJECT_SECTIONS)[number]["id"];

/** The section an address names; anything unknown is the fields. */
export function sectionOf(value: string | null | undefined): ObjectSection {
  return OBJECT_SECTIONS.find((one) => one.id === value)?.id ?? "fields";
}
