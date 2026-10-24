"use client";

import { useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { importSpreadsheet, type ImportReport, type SheetOwner, type VersionSummary } from "@/lib/sheets/api";
import {
  changedOnTheWay,
  kindOfFile,
  leftOut,
  refusalBeforeSending,
  reportSummary,
  type FileKind,
} from "@/lib/sheets/upload";
import { sizeWords } from "./VersionList";

export interface UploadSheetProps {
  /** The record the sheet belongs to. */
  owner: SheetOwner;
  /** The words of the button that opens the file chooser. */
  label: string;
  /** True for a sheet that has no version yet: the first version names its rows, which the person is told. */
  firstVersion?: boolean;
  /** The size a file may have, as the API says (the server checks again). */
  maxBytes?: number;
  /** Called with the new draft once it is saved. */
  onSaved(version: VersionSummary): void;
  disabled?: boolean;
}

type Phase =
  | { step: "reading" }
  | { step: "ready"; report: ImportReport }
  | { step: "saving"; report: ImportReport }
  | { step: "failed"; message: string };

/**
 * Starts a new draft version from a spreadsheet file (UI3B, ADR-0195). The person chooses a file, the server reads it and
 * says what it would give (columns, rows, merged areas, what is left out) without saving anything, and only the person's
 * Save makes the draft. The file is sent as it is and kept nowhere. It draws the server's own words, in the place the
 * server names, and decides nothing: the kind of file and the size are checked here only to spare a long upload; the
 * server checks the bytes again and a refusal is shown as it is.
 */
export function UploadSheet({ owner, label, firstVersion = false, maxBytes, onSaved, disabled = false }: UploadSheetProps) {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const latest = useRef(0);
  const [file, setFile] = useState<{ blob: File; kind: FileKind } | null>(null);
  const [firstRowLabels, setFirstRowLabels] = useState(true);
  const [phase, setPhase] = useState<Phase>({ step: "reading" });

  const preview = async (blob: File, kind: FileKind, labels: boolean) => {
    const ticket = ++latest.current;
    setPhase({ step: "reading" });
    const outcome = await importSpreadsheet(owner, blob, { format: kind, firstRowLabels: labels, preview: true });
    if (ticket !== latest.current) {
      return;
    }
    setPhase(
      outcome.ok
        ? { step: "ready", report: outcome.data.report }
        : { step: "failed", message: outcome.failure.fields?.file?.[0] ?? outcome.failure.message },
    );
  };

  const choose = (blob: File | undefined) => {
    if (input.current) {
      input.current.value = "";
    }
    if (!blob) {
      return;
    }
    const problem = refusalBeforeSending(blob, maxBytes);
    const kind = kindOfFile(blob.name);
    setFirstRowLabels(true);
    if (problem || kind === null) {
      setFile({ blob, kind: kind ?? "csv" });
      setPhase({ step: "failed", message: problem ?? "Choose a spreadsheet saved as .xlsx, or a text file saved as .csv." });
      return;
    }
    setFile({ blob, kind });
    void preview(blob, kind, true);
  };

  const close = () => {
    latest.current++;
    setFile(null);
  };

  const save = async () => {
    if (!file || phase.step !== "ready") {
      return;
    }
    const ticket = ++latest.current;
    setPhase({ step: "saving", report: phase.report });
    const outcome = await importSpreadsheet(owner, file.blob, { format: file.kind, firstRowLabels, preview: false });
    if (ticket !== latest.current) {
      return;
    }
    if (outcome.ok && outcome.data.version) {
      setFile(null);
      onSaved(outcome.data.version);
      return;
    }
    setPhase({
      step: "failed",
      message: outcome.ok
        ? "The server saved nothing. Try again."
        : (outcome.failure.fields?.file?.[0] ?? outcome.failure.message),
    });
  };

  const busy = phase.step === "reading" || phase.step === "saving";
  const report = phase.step === "ready" || phase.step === "saving" ? phase.report : null;

  return (
    <>
      <button type="button" className="button button-secondary" disabled={disabled} onClick={() => input.current?.click()}>
        {label}
      </button>
      <input
        ref={input}
        id={inputId}
        type="file"
        accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
        className="visually-hidden"
        tabIndex={-1}
        aria-label="Choose a spreadsheet file"
        onChange={(event) => choose(event.target.files?.[0])}
      />
      {file ? (
        <Modal title="Make a version from a spreadsheet" onClose={close} testId="upload-sheet">
          <div className="stack">
            <p>
              <strong>{file.blob.name}</strong> ({sizeWords(file.blob.size)})
            </p>
            <label className="sheet-check">
              <input
                type="checkbox"
                checked={firstRowLabels}
                disabled={busy}
                onChange={(event) => {
                  setFirstRowLabels(event.target.checked);
                  if (phase.step !== "failed" || refusalBeforeSending(file.blob, maxBytes) === null) {
                    void preview(file.blob, file.kind, event.target.checked);
                  }
                }}
              />
              <span>The first row holds the names of the columns</span>
            </label>
            {phase.step === "reading" ? (
              <p role="status" className="hint">
                Reading the file…
              </p>
            ) : null}
            {phase.step === "failed" ? (
              <p role="alert" className="form-message">
                {phase.message}
              </p>
            ) : null}
            {report ? (
              <div className="stack" data-testid="upload-report">
                <p role="status">
                  The file gives {reportSummary(report)}.{" "}
                  {firstVersion
                    ? "This sheet will name its rows: the text in the file is the sheet's own text and the cells with no text are for the person who fills it in."
                    : "The text in the file is the sheet's own text and the cells with no text are for the person who fills it in."}
                </p>
                {leftOut(report).length > 0 ? (
                  <div>
                    <p>
                      <strong>Left out</strong>
                    </p>
                    <ul>
                      {leftOut(report).map((note) => (
                        <li key={note.code}>{note.words}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                {changedOnTheWay(report).length > 0 ? (
                  <div>
                    <p>
                      <strong>Changed on the way in</strong>
                    </p>
                    <ul>
                      {changedOnTheWay(report).map((note) => (
                        <li key={note.code}>{note.words}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                <p className="hint">Nothing is saved until you choose Save. The file itself is not kept.</p>
              </div>
            ) : null}
            <div className="split">
              <button type="button" className="button" disabled={phase.step !== "ready"} onClick={() => void save()}>
                {phase.step === "saving" ? "Saving…" : "Save as a new draft"}
              </button>
              <button type="button" className="button button-secondary" onClick={close}>
                Cancel
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </>
  );
}
