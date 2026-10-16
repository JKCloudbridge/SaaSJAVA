import type { components } from "@/lib/api/generated/schema";
import { uid } from "./ids";
import { insertAt, move, removeAt } from "./reorder";

type PageLayoutDefinition = components["schemas"]["PageLayoutDefinition"];

export type Behaviour = NonNullable<components["schemas"]["LayoutField"]["behaviour"]>;

export const BEHAVIOURS: Behaviour[] = ["EDITABLE", "READ_ONLY", "REQUIRED", "HIDDEN"];

export interface LayoutItem {
  field: string;
  behaviour: Behaviour;
}

export interface LayoutSection {
  /** Identifies the section inside the browser while it is dragged and renamed; never sent. */
  id: string;
  label: string;
  columns: 1 | 2;
  items: LayoutItem[];
}

/** The layout as the builder holds it while a person drags: the same shape as the saved one, with a key per section. */
export interface LayoutDraft {
  sections: LayoutSection[];
}

export function newSection(label = "", id: string = uid()): LayoutSection {
  return { id, label, columns: 1, items: [] };
}

export function emptyLayout(): LayoutDraft {
  return { sections: [newSection("Details")] };
}

export function fromDefinition(definition: PageLayoutDefinition): LayoutDraft {
  return {
    sections: definition.sections.map((section) => ({
      id: uid(),
      label: section.label,
      columns: section.columns === 2 ? 2 : 1,
      items: section.fields.map((entry) => ({ field: entry.field, behaviour: entry.behaviour ?? "EDITABLE" })),
    })),
  };
}

/** What is saved. Section keys stay behind; this is the one place a draft becomes the API's shape. */
export function toDefinition(draft: LayoutDraft): PageLayoutDefinition {
  return {
    sections: draft.sections.map((section) => ({
      label: section.label,
      columns: section.columns,
      fields: section.items.map((item) => ({ field: item.field, behaviour: item.behaviour })),
    })),
  };
}

/** The fields that are on the layout already. A field can be on a layout once. */
export function placedFields(draft: LayoutDraft): Set<string> {
  return new Set(draft.sections.flatMap((section) => section.items.map((item) => item.field)));
}

function sectionIndex(draft: LayoutDraft, sectionId: string): number {
  return draft.sections.findIndex((section) => section.id === sectionId);
}

function withSection(draft: LayoutDraft, sectionId: string, change: (section: LayoutSection) => LayoutSection): LayoutDraft {
  return { sections: draft.sections.map((section) => (section.id === sectionId ? change(section) : section)) };
}

/**
 * Puts a field into a section, at `at` (or at the end). A field that is already on the layout is moved, keeping the
 * behaviour it had, so placing the same chip twice can never make a duplicate.
 */
export function place(draft: LayoutDraft, sectionId: string, field: string, at?: number): LayoutDraft {
  if (sectionIndex(draft, sectionId) < 0) {
    return draft;
  }
  let behaviour: Behaviour = "EDITABLE";
  const without: LayoutDraft = {
    sections: draft.sections.map((section) => {
      const found = section.items.find((item) => item.field === field);
      if (found) {
        behaviour = found.behaviour;
      }
      return { ...section, items: section.items.filter((item) => item.field !== field) };
    }),
  };
  // A position counted before the field was taken out of its own section is one too far when it lay after the field.
  const before = draft.sections.find((section) => section.id === sectionId)?.items.findIndex((item) => item.field === field);
  const position = at !== undefined && before !== undefined && before >= 0 && before < at ? at - 1 : at;
  return withSection(without, sectionId, (section) => ({
    ...section,
    items: insertAt(section.items, { field, behaviour }, position),
  }));
}

export function removeField(draft: LayoutDraft, field: string): LayoutDraft {
  return {
    sections: draft.sections.map((section) => ({
      ...section,
      items: section.items.filter((item) => item.field !== field),
    })),
  };
}

/** Moves a field within its section by `delta` places (the keyboard way to order fields). */
export function nudgeField(draft: LayoutDraft, sectionId: string, field: string, delta: number): LayoutDraft {
  return withSection(draft, sectionId, (section) => {
    const from = section.items.findIndex((item) => item.field === field);
    return from < 0 ? section : { ...section, items: move(section.items, from, from + delta) };
  });
}

/** Moves a field to the end of another section (the keyboard way to change section). */
export function sendField(draft: LayoutDraft, field: string, toSectionId: string): LayoutDraft {
  return place(draft, toSectionId, field);
}

export function setBehaviour(draft: LayoutDraft, field: string, behaviour: Behaviour): LayoutDraft {
  return {
    sections: draft.sections.map((section) => ({
      ...section,
      items: section.items.map((item) => (item.field === field ? { ...item, behaviour } : item)),
    })),
  };
}

export function setColumns(draft: LayoutDraft, sectionId: string, columns: 1 | 2): LayoutDraft {
  return withSection(draft, sectionId, (section) => ({ ...section, columns }));
}

export function renameSection(draft: LayoutDraft, sectionId: string, label: string): LayoutDraft {
  return withSection(draft, sectionId, (section) => ({ ...section, label }));
}

export function addSection(draft: LayoutDraft, label = ""): LayoutDraft {
  return { sections: [...draft.sections, newSection(label)] };
}

/** Takes a section out; its fields go back to the palette (they are no longer on the layout). */
export function removeSection(draft: LayoutDraft, sectionId: string): LayoutDraft {
  const index = sectionIndex(draft, sectionId);
  return index < 0 ? draft : { sections: removeAt(draft.sections, index) };
}

export function nudgeSection(draft: LayoutDraft, sectionId: string, delta: number): LayoutDraft {
  const from = sectionIndex(draft, sectionId);
  return from < 0 ? draft : { sections: move(draft.sections, from, from + delta) };
}

/** Moves a section to the place of another (dragging a section card onto a card). */
export function moveSectionTo(draft: LayoutDraft, from: number, to: number): LayoutDraft {
  return { sections: move(draft.sections, from, to) };
}

/**
 * The required fields of the object that the layout does not show. A hint for the person while building: the API checks
 * the saved layout again and its refusal names what is missing.
 */
export function missingRequired(draft: LayoutDraft, required: readonly string[]): string[] {
  const placed = placedFields(draft);
  return required.filter((field) => !placed.has(field));
}
