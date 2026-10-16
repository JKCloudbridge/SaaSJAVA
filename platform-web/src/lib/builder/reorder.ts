/**
 * Pure list operations of the builders. They never change their argument, and an index that is out of range leaves the
 * list as it was, so a stray drop or a double click on a disabled control cannot corrupt a draft.
 */

/** Moves the item at `from` so that it sits at `to`. */
export function move<T>(list: readonly T[], from: number, to: number): T[] {
  if (from < 0 || from >= list.length || to < 0 || to >= list.length || from === to) {
    return [...list];
  }
  const next = [...list];
  const [taken] = next.splice(from, 1);
  next.splice(to, 0, taken as T);
  return next;
}

/** Puts `item` at `index`; an index past the end (or none) appends. */
export function insertAt<T>(list: readonly T[], item: T, index?: number): T[] {
  const next = [...list];
  const at = index === undefined || index < 0 || index > next.length ? next.length : index;
  next.splice(at, 0, item);
  return next;
}

/** The list without the item at `index`. */
export function removeAt<T>(list: readonly T[], index: number): T[] {
  return list.filter((_, at) => at !== index);
}

/** The list with the item at `index` replaced by `change(item)`. */
export function updateAt<T>(list: readonly T[], index: number, change: (item: T) => T): T[] {
  return list.map((item, at) => (at === index ? change(item) : item));
}
