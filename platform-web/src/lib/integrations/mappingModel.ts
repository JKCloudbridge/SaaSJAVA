import type { components } from "@/lib/api/generated/schema";

export type MappingView = components["schemas"]["MappingView"];
export type MappingVersion = components["schemas"]["MappingVersionView"];
export type MappingSpec = components["schemas"]["MappingSpec"];
export type MappingField = components["schemas"]["MappingFieldSpec"];
export type MappingCatalogue = components["schemas"]["MappingCatalogueView"];
export type MappingChoice = components["schemas"]["MappingChoice"];
export type ObjectField = components["schemas"]["FieldView"];
export type Delivery = components["schemas"]["DeliveryView"];

/**
 * What the designer holds while a person works (Sprint 28, ADR-0217, ADR-0218). Interface state only: a draft is turned
 * into the API's shape on Save, and the server checks every part again. Nothing here is a rule about what a mapping may
 * hold: the choices come from the API's catalogue and the API's words say what is wrong.
 */
export interface DesignerDraft {
  entity: string;
  conflict: string;
  changedAtField: string;
  inboundCreate: boolean;
  inboundUpdate: boolean;
  fields: MappingField[];
  /** The version of the saved draft that was read, or 0 when there is none. */
  version: number;
}

/** A pair of a value map as the form holds it, so a half-typed pair does not vanish. */
export interface PairDraft {
  platform: string;
  external: string;
}

/** The first rule the catalogue offers, and nothing else: the person makes every other choice. */
export function newDesignerDraft(catalogue: MappingCatalogue | undefined): DesignerDraft {
  return {
    entity: "",
    conflict: catalogue?.conflicts[0]?.key ?? "",
    changedAtField: "",
    inboundCreate: false,
    inboundUpdate: false,
    fields: [],
    version: 0,
  };
}

/** The draft of a mapping as it was saved, else its live version, else a new one: the screen starts from what exists. */
export function designerDraftOf(view: MappingView | undefined, catalogue: MappingCatalogue | undefined): DesignerDraft {
  const source = view?.draft ?? view?.live;
  if (!view || !source) {
    return { ...newDesignerDraft(catalogue), entity: view?.entity ?? "" };
  }
  const spec = source.definition;
  return {
    entity: view.entity,
    conflict: spec.conflict,
    changedAtField: spec.changedAtField ?? "",
    inboundCreate: spec.inboundCreate,
    inboundUpdate: spec.inboundUpdate,
    fields: spec.fields.map((field) => ({ ...field, valueMap: [...(field.valueMap ?? [])] })),
    version: view.draft?.version ?? 0,
  };
}

/** A field map for a platform field, with the first choice of each list as the starting point. */
export function newField(field: ObjectField, catalogue: MappingCatalogue | undefined, taken: string[]): MappingField {
  return {
    external: uniqueName(field.apiName.replace(/__c$/, "").replace(/[^A-Za-z0-9_]/g, "_"), taken),
    platform: field.apiName,
    direction: catalogue?.directions[0]?.key ?? "",
    externalType: catalogue?.externalTypes[0]?.key ?? "",
    required: false,
    conversion: catalogue?.conversions[0]?.key ?? "",
    valueMap: [],
  };
}

/** A name not in the list: the wanted one, else the wanted one with a number. */
export function uniqueName(wanted: string, taken: string[]): string {
  const base = /^[A-Za-z]/.test(wanted) ? wanted : `f_${wanted}`;
  if (!taken.includes(base)) {
    return base;
  }
  for (let n = 2; ; n += 1) {
    if (!taken.includes(`${base}_${n}`)) {
      return `${base}_${n}`;
    }
  }
}

export function addField(draft: DesignerDraft, field: MappingField): DesignerDraft {
  return { ...draft, fields: [...draft.fields, field] };
}

export function changeField(draft: DesignerDraft, index: number, change: Partial<MappingField>): DesignerDraft {
  return { ...draft, fields: draft.fields.map((one, at) => (at === index ? { ...one, ...change } : one)) };
}

export function removeField(draft: DesignerDraft, index: number): DesignerDraft {
  return { ...draft, fields: draft.fields.filter((_, at) => at !== index) };
}

/** Moves a field one place; a move off either end changes nothing. The same change a drag makes. */
export function moveField(draft: DesignerDraft, index: number, by: -1 | 1): DesignerDraft {
  const to = index + by;
  if (to < 0 || to >= draft.fields.length) {
    return draft;
  }
  const fields = [...draft.fields];
  const moved = fields.splice(index, 1)[0];
  if (moved === undefined) {
    return draft;
  }
  fields.splice(to, 0, moved);
  return { ...draft, fields };
}

/** Puts the field at `from` in front of the one at `to` (a drop on a row). */
export function moveFieldTo(draft: DesignerDraft, from: number, to: number): DesignerDraft {
  if (from === to || from < 0 || to < 0 || from >= draft.fields.length || to >= draft.fields.length) {
    return draft;
  }
  const fields = [...draft.fields];
  const moved = fields.splice(from, 1)[0];
  if (moved === undefined) {
    return draft;
  }
  fields.splice(to, 0, moved);
  return { ...draft, fields };
}

/** The platform fields no map uses yet, for the palette. */
export function unusedFields(object: ObjectField[], draft: DesignerDraft): ObjectField[] {
  const used = new Set(draft.fields.map((field) => field.platform));
  return object.filter((field) => !used.has(field.apiName));
}

/** The API's shape of a draft, with empty text sent as nothing. */
export function specOf(draft: DesignerDraft): MappingSpec {
  return {
    conflict: draft.conflict,
    changedAtField: draft.changedAtField.trim() === "" ? undefined : draft.changedAtField.trim(),
    inboundCreate: draft.inboundCreate,
    inboundUpdate: draft.inboundUpdate,
    fields: draft.fields.map((field) => ({
      ...field,
      defaultValue: blankToUndefined(field.defaultValue),
      scale: blankToUndefined(field.scale),
      outExpression: blankToUndefined(field.outExpression),
      inExpression: blankToUndefined(field.inExpression),
      valueMap: field.valueMap ?? [],
    })),
  };
}

function blankToUndefined(text: string | undefined): string | undefined {
  return text === undefined || text.trim() === "" ? undefined : text;
}

/** Whether the draft differs from the saved one, for the "unsaved" note. Compared as the API's shape. */
export function unsaved(draft: DesignerDraft, view: MappingView | undefined, catalogue: MappingCatalogue | undefined): boolean {
  const saved = designerDraftOf(view, catalogue);
  return JSON.stringify(specOf(draft)) !== JSON.stringify(specOf(saved)) || draft.entity !== saved.entity;
}

/** Where a problem is, in the words of the screen: "definition.fields[2].platform" is "Field 3, field". */
export function mappingPlaceWords(place: string): string {
  const field = /^(?:definition\.)?fields\[(\d+)\]\.?(.*)$/.exec(place);
  if (field) {
    const which = `Field ${Number(field[1]) + 1}`;
    return field[2] ? `${which}, ${field[2]}` : which;
  }
  return place.replace(/^definition\./, "");
}

/** The sample as the dry run takes it: text by name, blank entries left out. */
export function sampleOf(entries: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(entries).filter(([, value]) => value.trim() !== ""));
}
