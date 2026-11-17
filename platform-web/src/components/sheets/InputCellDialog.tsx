"use client";

import { useId, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { INPUT_KINDS, KIND_WORDS, MOST_FILES, takesPrompt, type InputKind, type InputSettings } from "@/lib/sheets/inputs";

export interface InputCellDialogProps {
  /** What the cell where the focus is states now: the form starts from it. */
  initial: InputSettings;
  /** How many cells the settings will go to (the selection). */
  cells: number;
  /** The column's own kind, in words, for the choice "as the column says". */
  columnKind: string;
  /** Applies the settings; answers with the reason in words when they are refused, otherwise null. */
  onApply(settings: InputSettings): string | null;
  onClose(): void;
}

/**
 * The settings of an input cell (SH5.1, ADR-0263, a small part of SH6): its kind, a list's values, the most files of an
 * attachment cell, the prompt of a Yes/No cell with a reason, whether it is required and who fills it. It draws a form
 * over the shared popup and decides nothing: the editor writes the sheet's stored `inputs`, and the server judges them
 * again on save.
 */
export function InputCellDialog({ initial, cells, columnKind, onApply, onClose }: InputCellDialogProps) {
  const id = useId();
  const [kind, setKind] = useState<InputKind | "">(initial.kind ?? "");
  const [required, setRequired] = useState(initial.required);
  const [cls, setCls] = useState(initial.cls);
  const [values, setValues] = useState(initial.values.join("\n"));
  const [maxFiles, setMaxFiles] = useState(String(initial.maxFiles));
  const [prompt, setPrompt] = useState(initial.prompt ?? "");
  const [refused, setRefused] = useState<string | null>(null);
  const one = cells === 1;

  const apply = () => {
    const settings: InputSettings = {
      kind: kind === "" ? null : kind,
      required,
      cls,
      values: values.split("\n").map((value) => value.trim()).filter((value) => value !== ""),
      maxFiles: Number(maxFiles),
      prompt: one && takesPrompt(kind === "" ? null : kind) && prompt.trim() !== "" ? prompt : null,
    };
    const problem = onApply(settings);
    if (problem) {
      setRefused(problem);
      return;
    }
    onClose();
  };

  return (
    <Modal title={one ? "Input cell" : `Input cells (${cells})`} onClose={onClose} testId="input-cell-dialog">
      <form
        className="form stack"
        onSubmit={(event) => {
          event.preventDefault();
          apply();
        }}
      >
        <div className="field">
          <label htmlFor={`${id}-kind`}>Kind</label>
          <select id={`${id}-kind`} value={kind} onChange={(event) => setKind(event.target.value as InputKind | "")}>
            <option value="">As the column says ({columnKind})</option>
            {INPUT_KINDS.map((choice) => (
              <option key={choice} value={choice}>
                {KIND_WORDS[choice]}
              </option>
            ))}
          </select>
        </div>
        {kind === "picklist" ? (
          <div className="field">
            <label htmlFor={`${id}-values`}>Values of the list, one on each line</label>
            <textarea id={`${id}-values`} rows={4} value={values} onChange={(event) => setValues(event.target.value)} />
          </div>
        ) : null}
        {kind === "attachment" ? (
          <div className="field">
            <label htmlFor={`${id}-files`}>Most files (1 to {MOST_FILES})</label>
            <input
              id={`${id}-files`}
              type="number"
              min={1}
              max={MOST_FILES}
              step={1}
              value={maxFiles}
              onChange={(event) => setMaxFiles(event.target.value)}
            />
          </div>
        ) : null}
        {takesPrompt(kind === "" ? null : kind) ? (
          one ? (
            <div className="field">
              <label htmlFor={`${id}-prompt`}>
                {kind === "attachment" ? "Prompt (shown with the paperclip)" : "Prompt (shown above Yes and No)"}
              </label>
              <textarea id={`${id}-prompt`} rows={3} value={prompt} onChange={(event) => setPrompt(event.target.value)} />
              <p className="hint">
                Left empty, the text the cell holds becomes the prompt.
                {kind === "boolean_reason" ? " The reason is never compulsory." : ""}
              </p>
            </div>
          ) : (
            <p className="hint">
              Each cell keeps its own text as its prompt.{kind === "boolean_reason" ? " The reason is never compulsory." : ""}
            </p>
          )
        ) : null}
        <div className="field">
          <label>
            <input type="checkbox" checked={required} onChange={(event) => setRequired(event.target.checked)} />{" "}
            {kind === "boolean_reason" ? "Required: the answer must be given before sending" : "Required"}
          </label>
        </div>
        <fieldset className="field">
          <legend>Filled by</legend>
          <label>
            <input type="radio" name={`${id}-class`} checked={cls === "filler"} onChange={() => setCls("filler")} /> The
            person filling it in
          </label>
          <label>
            <input type="radio" name={`${id}-class`} checked={cls === "reviewer"} onChange={() => setCls("reviewer")} /> The
            reviewer
          </label>
        </fieldset>
        {refused ? (
          <p className="form-message" role="alert">
            {refused}
          </p>
        ) : null}
        <div className="split">
          <button type="submit" className="button">
            Apply
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Modal>
  );
}
