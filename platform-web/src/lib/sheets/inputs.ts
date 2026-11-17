import { cellKey, splitKey, zoneOfRow } from "./geometry";
import { setCellKind } from "./gridModel";
import type { GridDoc, OpResult } from "./types";
import { TEXT_UNSAFE, unsafeText } from "./values";

/**
 * What an author states about an input cell of a sheet in format version 2 (SH5.1, ADR-0263, a small part of SH6): its
 * kind, whether it is required, who fills it, the values of a list and the most files of an attachment cell, and the
 * prompt of a Yes/No cell with a reason. Pure functions over the document: they write the sheet's stored `inputs`, the
 * server's validator judges it again on save, and nothing here is a permission.
 */

/** The kinds an input cell may state. The order is the order of the choice on the screen. */
export const INPUT_KINDS = ["text", "number", "date", "boolean", "boolean_reason", "picklist", "attachment"] as const;
export type InputKind = (typeof INPUT_KINDS)[number];

/** The words for each kind, as the author reads them. */
export const KIND_WORDS: Readonly<Record<InputKind, string>> = {
  text: "Text",
  number: "Number",
  date: "Date",
  boolean: "Yes/No",
  boolean_reason: "Yes/No with a reason",
  picklist: "List",
  attachment: "Attachment",
};

/** The most files an attachment cell may hold, and what it holds when it says nothing (the server's own numbers). */
export const MOST_FILES = 5;
/** The longest prompt: a prompt is fixed text of the author (the server's limit of a fixed text). */
export const MOST_PROMPT_CHARS = 5000;

/** What one input cell states. `kind` null means "as its column says". */
export interface InputSettings {
  kind: InputKind | null;
  required: boolean;
  cls: "filler" | "reviewer";
  /** The values of a list cell, in order. */
  values: string[];
  /** The most files of an attachment cell, 1 to 5. */
  maxFiles: number;
  /**
   * The prompt of a Yes/No cell with a reason, used when one cell is set; null keeps the text the cell holds, or else the
   * prompt it already states.
   */
  prompt: string | null;
}

interface Statement {
  type?: unknown;
  required?: unknown;
  class?: unknown;
  picklist?: { values?: unknown } | null;
  maxFiles?: unknown;
  prompt?: unknown;
}

const isKind = (value: unknown): value is InputKind => typeof value === "string" && (INPUT_KINDS as readonly string[]).includes(value);

/** Whether the kind of a cell can be set at all here: a sheet in the new format that names its rows and is filled in. */
export function canStateInputs(doc: GridDoc): boolean {
  return doc.formatVersion === 2 && doc.dimension === 2 && doc.sheet?.fillable !== false;
}

/** What the cell states now; a cell that states nothing is as its column says. */
export function readInput(doc: GridDoc, rowId: string, columnId: string): InputSettings {
  const own = (doc.sheet?.inputs?.[cellKey(rowId, columnId)] ?? {}) as Statement;
  const values = own.picklist && Array.isArray(own.picklist.values)
    ? own.picklist.values.filter((value): value is string => typeof value === "string")
    : [];
  return {
    kind: isKind(own.type) ? own.type : null,
    required: own.required === true,
    cls: own.class === "reviewer" ? "reviewer" : "filler",
    values,
    maxFiles: typeof own.maxFiles === "number" ? own.maxFiles : MOST_FILES,
    prompt: typeof own.prompt === "string" ? own.prompt : null,
  };
}

/** The kind of the cell in words, for the toolbar: fixed text, or the kind of the input cell. */
export function kindWords(doc: GridDoc, rowId: string, columnId: string): string {
  const zone = zoneOfRow(doc, rowId);
  const cell = doc.cells[cellKey(rowId, columnId)];
  if (zone !== "body" || cell?.kind === "fixed" || (cell?.kind === undefined && cell?.value != null)
    || doc.sheet?.cells[cellKey(rowId, columnId)]?.formula) {
    return "Fixed text";
  }
  const stated = readInput(doc, rowId, columnId).kind;
  const column = doc.columns.find((one) => one.id === columnId);
  return `Input cell: ${KIND_WORDS[stated ?? column?.type ?? "text"]}`;
}

/**
 * Whether a kind takes a prompt, the author's fixed text shown with the cell: a Yes/No cell with a reason and an
 * attachment cell (the server's `SheetDocument.takesPrompt`; the one place the browser says it).
 */
export function takesPrompt(kind: InputKind | null): boolean {
  return kind === "boolean_reason" || kind === "attachment";
}

/** The statement as it is stored: only what differs from what its absence says (the server's one form). */
function statementOf(settings: InputSettings, prompt: string | null): Record<string, unknown> | null {
  const out: Record<string, unknown> = {};
  if (settings.kind) out.type = settings.kind;
  if (settings.required) out.required = true;
  if (settings.cls === "reviewer") out.class = "reviewer";
  if (settings.kind === "picklist") out.picklist = { values: [...settings.values] };
  if (settings.kind === "attachment" && settings.maxFiles !== MOST_FILES) out.maxFiles = settings.maxFiles;
  if (takesPrompt(settings.kind) && prompt) out.prompt = prompt;
  return Object.keys(out).length > 0 ? out : null;
}

/** Why the settings would be refused, in the server's words, or null. The server decides again on save. */
export function settingsProblem(settings: InputSettings, maxPicklistValues: number): string | null {
  if (settings.kind === "picklist") {
    const values = settings.values;
    if (values.length === 0 || values.length > maxPicklistValues || new Set(values).size !== values.length
      || values.some((value) => value.trim() === "")) {
      return `A list cell needs its values (at most ${maxPicklistValues}), each different.`;
    }
    if (values.some((value) => value.length > 100)) return "A value of a list is at most 100 characters long.";
    if (values.some(unsafeText)) return TEXT_UNSAFE;
  }
  if (settings.kind === "attachment" && (!Number.isInteger(settings.maxFiles) || settings.maxFiles < 1 || settings.maxFiles > MOST_FILES)) {
    return `An attachment cell holds 1 to ${MOST_FILES} files.`;
  }
  if (takesPrompt(settings.kind) && settings.prompt !== null) {
    if (settings.prompt.length > MOST_PROMPT_CHARS) return `A prompt is longer than the allowed ${MOST_PROMPT_CHARS} characters.`;
    if (unsafeText(settings.prompt)) return TEXT_UNSAFE;
  }
  return null;
}

/**
 * Makes each of the cells an input cell with these settings. A cell that holds text and becomes a Yes/No cell with a
 * reason or an attachment cell keeps its text as the prompt (unless a prompt is given); its bold, italic and underline go, because an input
 * cell has no style of its own in the format, while its look (font, size, colours, borders) stays. A header cell, a cell
 * under a merged area and a cell that holds a formula are left as they are and counted in the note; when none is left
 * the change is refused.
 */
export function setInputs(doc: GridDoc, keys: readonly string[], settings: InputSettings, maxPicklistValues: number): OpResult {
  if (!canStateInputs(doc)) {
    return { ok: false, error: "The kind of a cell can be set in a sheet in the new format that names its rows and is filled in." };
  }
  const problem = settingsProblem(settings, maxPicklistValues);
  if (problem) return { ok: false, error: problem };
  let next = doc;
  let changed = 0;
  let skipped = 0;
  const inputs: Record<string, unknown> = { ...(doc.sheet?.inputs ?? {}) };
  for (const key of keys) {
    const [rowId, columnId] = splitKey(key);
    if (zoneOfRow(next, rowId) !== "body" || next.sheet?.cells[key]?.formula) {
      skipped++;
      continue;
    }
    const held = next.cells[key]?.value;
    const stated = readInput(next, rowId, columnId).prompt;
    const made = setCellKind(next, rowId, columnId, "input");
    if (!made.ok) {
      skipped++;
      continue;
    }
    next = made.doc;
    const prompt = !takesPrompt(settings.kind) ? null
      : settings.prompt !== null && keys.length === 1 ? settings.prompt
        : typeof held === "string" && held.trim() !== "" ? held : stated;
    const statement = statementOf(settings, prompt);
    if (statement) inputs[key] = statement;
    else delete inputs[key];
    changed++;
  }
  if (changed === 0) {
    return { ok: false, error: "None of the selected cells can be an input cell: a header cell, a cell under a merged area and a cell with a formula stay as they are." };
  }
  const sheet = next.sheet ?? doc.sheet;
  const result: GridDoc = sheet ? { ...next, sheet: { ...sheet, inputs } } : next;
  const words = `${changed} cell${changed === 1 ? " is" : "s are"} now ${settings.kind ? KIND_WORDS[settings.kind] : "as the column says"}.`;
  return { ok: true, doc: result, note: skipped > 0 ? `${words} ${skipped} could not change.` : words };
}
