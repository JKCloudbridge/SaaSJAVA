import type { components } from "@/lib/api/generated/schema";

/**
 * The grid of a dependent picklist in the object manager (FA2A, ADR-0177): controlling values down the side, the values
 * of the picklist across the top, a box where a value goes with a controlling value. Plain functions over the draft so
 * they can be tested without a browser. They decide no rule: the API checks the pairs and answers in its own words.
 */

type Dependency = components["schemas"]["PicklistDependency"];

/** Which values go with which controlling value, by stored value. */
export type Pairs = Record<string, string[]>;

/** The pairs of a rule the API sent, copied; none when the picklist depends on nothing. */
export function pairsOf(dependency: Dependency | undefined): Pairs {
  const pairs: Pairs = {};
  for (const [key, values] of Object.entries(dependency?.allowed ?? {})) {
    pairs[key] = [...values];
  }
  return pairs;
}

/** Whether the box for a controlling value and a value is checked. */
export function isChecked(pairs: Pairs, controlling: string, value: string): boolean {
  return (pairs[controlling] ?? []).includes(value);
}

/** The pairs after a box is checked or unchecked. */
export function toggled(pairs: Pairs, controlling: string, value: string, checked: boolean): Pairs {
  const row = pairs[controlling] ?? [];
  const next = checked ? (row.includes(value) ? row : [...row, value]) : row.filter((one) => one !== value);
  return { ...pairs, [controlling]: next };
}

/** The pairs after every value is allowed, or none, for one controlling value. */
export function setRow(pairs: Pairs, controlling: string, values: readonly string[], all: boolean): Pairs {
  return { ...pairs, [controlling]: all ? [...values] : [] };
}

/** How many pairs the grid lists. */
export function countPairs(pairs: Pairs): number {
  return Object.values(pairs).reduce((total, row) => total + row.length, 0);
}

/**
 * The controlling values to show: all of them, or only those the filter matches (on the stored value or the label) once
 * the grid is long enough to need a filter.
 */
export function visibleRows<T extends { value: string; label: string }>(
  rows: readonly T[],
  filter: string,
): T[] {
  const needle = filter.trim().toLowerCase();
  if (needle === "") {
    return [...rows];
  }
  return rows.filter((row) => row.value.toLowerCase().includes(needle) || row.label.toLowerCase().includes(needle));
}

/** From this many controlling values the grid offers a filter. */
export const FILTER_FROM = 12;

/**
 * The rule to send: the pairs of the values that exist, with an empty row for a controlling value nothing goes with
 * (so the rule says so). Values that are no longer in the picklist are left out.
 */
export function ruleOf(
  controllingField: string,
  controllingValues: readonly string[],
  ownValues: readonly string[],
  pairs: Pairs,
): Dependency | undefined {
  if (controllingField === "") {
    return undefined;
  }
  const allowed: Pairs = {};
  for (const key of controllingValues) {
    allowed[key] = (pairs[key] ?? []).filter((value) => ownValues.includes(value));
  }
  return { controllingField, allowed };
}
