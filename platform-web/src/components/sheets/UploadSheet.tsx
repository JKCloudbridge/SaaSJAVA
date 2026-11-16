"use client";

import { useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { importSpreadsheet, type ImportReport, type SheetOwner, type VersionSummary } from "@/lib/sheets/api";
import {
  changedOnTheWay,
  hasWorksheetChoice,
  kindOfFile,
  leftOut,
  placesWords,
  unplacedBoxes,
  refusalBeforeSending,
  reportSummary,
  worksheetWords,
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
  /**
   * True to put the grid of the file in the draft that is held for a change request, in place (FA8): there is still only
   * one draft and it keeps its number.
   */
  replace?: boolean;
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
export function UploadSheet({
  owner,
  label,
  firstVersion = false,
  maxBytes,
  onSaved,
  disabled = false,
  replace = false,
}: UploadSheetProps) {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const latest = useRef(0);
  const [file, setFile] = useState<{ blob: File; kind: FileKind } | null>(null);
  const [firstRowLabels, setFirstRowLabels] = useState(true);
  const [worksheet, setWorksheet] = useState<number | undefined>(undefined);
  const [phase, setPhase] = useState<Phase>({ step: "reading" });

  /**
   * `labels` undefined is the first look at a file, or a new worksheet: the server says whether the first row names the
   * columns. `sheet` undefined lets the server choose the first worksheet that holds anything (SH1B).
   */
  const preview = async (blob: File, kind: FileKind, labels: boolean | undefined, sheet: number | undefined) => {
    const ticket = ++latest.current;
    setPhase({ step: "reading" });
    const outcome = await importSpreadsheet(owner, blob, {
      format: kind,
      firstRowLabels: labels,
      preview: true,
      replace,
      worksheet: sheet,
    });
    if (ticket !== latest.current) {
      return;
    }
    if (outcome.ok && labels === undefined) {
      setFirstRowLabels(outcome.data.report.firstRowLabels ?? true);
    }
    if (outcome.ok && sheet === undefined) {
      setWorksheet(outcome.data.report.worksheet ?? undefined);
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
    setWorksheet(undefined);
    if (problem || kind === null) {
      setFile({ blob, kind: kind ?? "csv" });
      setPhase({ step: "failed", message: problem ?? "Choose a spreadsheet saved as .xlsx, or a text file saved as .csv." });
      return;
    }
    setFile({ blob, kind });
    void preview(blob, kind, undefined, undefined);
  };

  /** A different worksheet is a different table: the server decides again whether its first row names the columns. */
  const chooseWorksheet = (index: number) => {
    if (!file) {
      return;
    }
    setWorksheet(index);
    void preview(file.blob, file.kind, undefined, index);
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
    const outcome = await importSpreadsheet(owner, file.blob, {
      format: file.kind,
      firstRowLabels,
      preview: false,
      replace,
      worksheet,
    });
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
        <Modal title="Make a revision from a spreadsheet" onClose={close} testId="upload-sheet">
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
                    void preview(file.blob, file.kind, event.target.checked, worksheet);
                  }
                }}
              />
              <span>The first row holds the names of the columns</span>
            </label>
            {report && hasWorksheetChoice(report) ? (
              <label className="field" data-testid="upload-worksheet">
                <span>Worksheet to make the sheet from</span>
                <select
                  value={worksheet ?? report.worksheet ?? 0}
                  disabled={busy}
                  onChange={(event) => chooseWorksheet(Number(event.target.value))}
                >
                  {(report.worksheets ?? []).map((one) => (
                    <option key={one.index} value={one.index}>
                      {worksheetWords(one)}
                    </option>
                  ))}
                </select>
                <span className="hint">A template holds one sheet. Upload each of the other worksheets into its own template.</span>
              </label>
            ) : null}
            {report?.firstRowIsTitle ? (
              <p className="hint" data-testid="upload-title-note">
                The first row looks like a title.{" "}
                {firstRowLabels
                  ? "Because the box is ticked it will name the first column."
                  : "It is kept as the first row of the sheet and does not name a column. Tick the box to use it as the names of the columns instead."}
              </p>
            ) : null}
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
                {(report.embeddedFilePlaces?.length ?? 0) > 0 ? (
                  <p data-testid="upload-embedded-places">
                    {report.formatTwo
                      ? "Embedded files stood in these cells and could not become attachment cells (the cell holds text or a formula, or is not on the sheet): "
                      : "Embedded files stood in these cells of the worksheet: "}
                    {placesWords(report.embeddedFilePlaces ?? [])}.{" "}
                    {report.formatTwo
                      ? "If one of them should take files, choose the cell and set its kind to Attachment with Input cell when you edit the sheet."
                      : "Files cannot be attached to a sheet in the earlier format."}
                  </p>
                ) : null}
                {unplacedBoxes(report).length > 0 ? (
                  <p data-testid="upload-unplaced-boxes">
                    Check boxes stood in these cells of the worksheet and could not be made part of a cell:{" "}
                    {placesWords(unplacedBoxes(report))}. Set those cells by hand with Input cell when you edit the sheet.
                  </p>
                ) : null}
                <p className="hint">Nothing is saved until you choose Save. The file itself is not kept.</p>
              </div>
            ) : null}
            <div className="split">
              <button type="button" className="button" disabled={phase.step !== "ready"} onClick={() => void save()}>
                {phase.step === "saving" ? "Saving…" : replace ? "Replace the revised copy" : "Save as a new draft"}
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
