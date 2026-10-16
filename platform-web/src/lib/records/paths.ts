/**
 * Where the record screens live (Sprint 19, ADR-0129). The address of a record, `/records/<object>/<id>`, is the one the
 * platform's system field `id` has always named; a list, a create form and an edit form are the three screens under it.
 * Inside an application the same screens are reached through the entry that lists the object, so the control bar keeps
 * the application open (`/apps/<application>/o-<object>`, then `/new` and `/records/<id>`). Neither address is a
 * permission: the API decides what the member may open, and a page it does not give is shown as not available.
 */

const enc = encodeURIComponent;

/** The list of an object outside any application. */
export function objectListHref(objectApiName: string): string {
  return `/records/${enc(objectApiName)}`;
}

export function objectNewHref(objectApiName: string): string {
  return `${objectListHref(objectApiName)}/new`;
}

/** A record, by object and identifier: also where a link from another record leads. */
export function recordHref(objectApiName: string, id: string): string {
  return `${objectListHref(objectApiName)}/${enc(id)}`;
}

/** The address of an entry of an application, from its parts (they are already encoded by the caller's router). */
export function entryBase(application: string, entry: string): string {
  return `/apps/${enc(application)}/${enc(entry)}`;
}

/** The object an application entry address names (`o-<object>`), or undefined for any other kind of entry. */
export function objectOfEntry(entry: string): string | undefined {
  return entry.startsWith("o-") && entry.length > 2 ? entry.slice(2) : undefined;
}

/**
 * The list of an object narrowed to the records whose link field points at one record (the "view all" of a related
 * list, Sprint 20). Only an API name and an identifier go in the address, never text a person typed.
 */
export function relatedListHref(objectApiName: string, field: string, id: string): string {
  return `${objectListHref(objectApiName)}?where=${enc(field)}:${enc(id)}`;
}

/** Where the create form of an object makes a copy of a record (Sprint 20): the create address plus the original. */
export function cloneHref(newHref: string, id: string): string {
  return `${newHref}?cloneFrom=${enc(id)}`;
}

/** What a `where` of an address names: a field and the identifier it must equal, or undefined for any other shape. */
export function whereOf(value: string | null): { field: string; value: string } | undefined {
  if (!value) {
    return undefined;
  }
  const at = value.indexOf(":");
  const field = at > 0 ? value.slice(0, at) : "";
  const id = at > 0 ? value.slice(at + 1) : "";
  return /^[A-Za-z][A-Za-z0-9_]*$/.test(field) && /^[0-9a-fA-F-]{36}$/.test(id) ? { field, value: id } : undefined;
}
