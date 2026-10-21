import type { FieldInput, FormField } from "./types";

/**
 * Dependent picklists on a form (FA2A, ADR-0177), the presentation side only. The server tells the screen which field a
 * picklist depends on and which values go with which; this module narrows the choices a person is offered and finds
 * the values that stop being allowed when the other field changes. It decides nothing: the server judges every write
 * again, and a screen that offered too much or too little would still be corrected by it.
 */

/** A value a member added from the form (the server has stored it; the form does not reload to know). */
export interface AddedValue {
  value: string;
  label: string;
  /** The values of the controlling field it goes with, empty for a picklist that depends on nothing. */
  controllingValues: string[];
}

type Choice = NonNullable<FormField["values"]>[number];

/** The fields whose choices depend on the field with this API name. */
export function dependentsOf(fields: readonly FormField[], controllingApiName: string): FormField[] {
  return fields.filter((field) => field.dependency?.controllingField === controllingApiName);
}

/** The values of a picklist with the ones the member added, each once, in the order they were offered then added. */
function withAdded(field: FormField, added: readonly AddedValue[]): Choice[] {
  const choices: Choice[] = [...(field.values ?? [])];
  for (const one of added) {
    if (!choices.some((choice) => choice.value === one.value)) {
      choices.push({ value: one.value, label: one.label, active: true });
    }
  }
  return choices;
}

/** The pairs of a dependent picklist, with the ones the member added. */
function pairsOf(field: FormField, added: readonly AddedValue[]): Record<string, string[]> {
  const pairs: Record<string, string[]> = {};
  for (const [key, values] of Object.entries(field.dependency?.allowed ?? {})) {
    pairs[key] = [...values];
  }
  for (const one of added) {
    for (const key of one.controllingValues) {
      const list = (pairs[key] ??= []);
      if (!list.includes(one.value)) {
        list.push(one.value);
      }
    }
  }
  return pairs;
}

/** Whether a value goes with the value chosen in the controlling field. Nothing chosen allows nothing. */
export function isAllowed(
  field: FormField,
  controllingInput: FieldInput | undefined,
  value: string,
  added: readonly AddedValue[] = [],
): boolean {
  if (!field.dependency) {
    return true;
  }
  if (typeof controllingInput !== "string" || controllingInput === "") {
    return false;
  }
  return (pairsOf(field, added)[controllingInput] ?? []).includes(value);
}

/**
 * The field as the form should offer it: the values the member added, and for a dependent picklist only the values that
 * go with the chosen value of the controlling field. The value the record holds now stays in the list even if it does
 * not go with the choice, so looking at an old record loses nothing and a person sees what it holds.
 */
export function offeredField(
  field: FormField,
  controllingInput: FieldInput | undefined,
  held: string,
  added: readonly AddedValue[] = [],
): FormField {
  if (field.type !== "PICKLIST") {
    return field;
  }
  const choices = withAdded(field, added);
  if (!field.dependency) {
    return added.length === 0 ? field : { ...field, values: choices };
  }
  return {
    ...field,
    values: choices.filter(
      (choice) => choice.value === held || isAllowed(field, controllingInput, choice.value, added),
    ),
  };
}

/**
 * The dependent fields whose chosen value stops being allowed when the controlling field is given a new value: they are
 * cleared, so the person is never left holding a pair the server would refuse.
 *
 * @param inputOf what a field holds now
 */
export function staleDependents(
  fields: readonly FormField[],
  controllingApiName: string,
  newInput: FieldInput,
  inputOf: (apiName: string) => FieldInput,
  added: Readonly<Record<string, readonly AddedValue[]>> = {},
): string[] {
  const stale: string[] = [];
  for (const field of dependentsOf(fields, controllingApiName)) {
    const chosen = inputOf(field.apiName);
    if (typeof chosen === "string" && chosen !== "" && !isAllowed(field, newInput, chosen, added[field.apiName] ?? [])) {
      stale.push(field.apiName);
    }
  }
  return stale;
}
