"use client";

import { useEffect, useId, useRef, useState } from "react";
import { RegisterExcelView } from "./RegisterExcelView";
import { RegisterRaiseForm, RegisterViewForm } from "./RegisterForm";
import {
  downloadRegister,
  downloadRegisterDraft,
  drawRegister,
  looksLikeWorkbook,
  readRegisterFile,
  registerFileName,
  saveBlob,
  type RegisterInput,
  type RegisterView,
} from "./register";

type Which = "form" | "excel";

/** The two ways of looking at the one register: the form and the Excel page. */
function Toggle({ which, onChange }: { which: Which; onChange(next: Which): void }) {
  const ids = useId();
  return (
    <div role="tablist" aria-label="Views of the register" className="sheet-tabs">
      {(["form", "excel"] as const).map((one) => (
        <button
          key={one}
          type="button"
          role="tab"
          id={`${ids}-${one}`}
          aria-selected={which === one}
          className={which === one ? "button" : "button button-secondary"}
          onClick={() => onChange(one)}
          data-testid={`register-tab-${one}`}
        >
          {one === "form" ? "Form" : "Excel"}
        </button>
      ))}
    </div>
  );
}

/**
 * The register a person fills in when they ask for a change (Functional track FA8, ADR-0202): the form and the Excel page
 * of the same values behind a toggle, a way to take the empty or filled register as an Excel file, and a way to fill the
 * form from a file that was filled there. The file is read by the server, which takes only the boxes a person types; what
 * the register cannot hold is refused in the server's own words. Nothing is stored until the request is sent.
 */
export function RegisterRaisePanel({
  templateId,
  value,
  onChange,
}: {
  templateId: string;
  value: RegisterInput;
  onChange(next: RegisterInput): void;
}) {
  const [which, setWhich] = useState<Which>("form");
  const [facts, setFacts] = useState<RegisterView | undefined>();
  const [message, setMessage] = useState<{ text: string; error: boolean } | undefined>();
  const chooser = useRef<HTMLInputElement>(null);
  const latest = useRef(0);

  // The server draws the register for what is typed: the platform's parts come from it, and so does the Excel page. In a
  // short while after the last key, so that typing does not ask for a drawing at every letter.
  useEffect(() => {
    const ticket = ++latest.current;
    const timer = setTimeout(() => {
      void drawRegister(templateId, value).then((outcome) => {
        if (ticket === latest.current && outcome.ok) {
          setFacts(outcome.data);
        } else if (ticket === latest.current && !outcome.ok) {
          setMessage({ text: outcome.failure.message, error: true });
        }
      });
    }, 300);
    return () => clearTimeout(timer);
  }, [templateId, value]);

  const download = async () => {
    setMessage(undefined);
    const outcome = await downloadRegisterDraft(templateId, value);
    if (!outcome.ok) {
      setMessage({ text: outcome.failure.message, error: true });
      return;
    }
    saveBlob(outcome.data, registerFileName(value.requestNo));
  };

  const choose = async (file: File | undefined) => {
    if (chooser.current) {
      chooser.current.value = "";
    }
    if (!file) {
      return;
    }
    if (!looksLikeWorkbook(file)) {
      setMessage({ text: "Choose the register saved as an Excel workbook (.xlsx).", error: true });
      return;
    }
    setMessage(undefined);
    const outcome = await readRegisterFile(templateId, file);
    if (!outcome.ok) {
      setMessage({ text: outcome.failure.fields?.file?.[0] ?? outcome.failure.message, error: true });
      return;
    }
    onChange({ ...value, ...outcome.data.values });
    setMessage({
      text: outcome.data.notes.length > 0 ? outcome.data.notes.join(" ") : "The form was filled from the file. Check it, then send the request.",
      error: false,
    });
  };

  return (
    <div className="stack" data-testid="register-panel">
      <div className="register-toolbar">
        <Toggle which={which} onChange={setWhich} />
        <button type="button" className="button button-secondary" onClick={() => void download()} data-testid="register-download">
          Download as Excel
        </button>
        <button type="button" className="button button-secondary" onClick={() => chooser.current?.click()} data-testid="register-upload">
          Fill from an Excel file
        </button>
        <input
          ref={chooser}
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          className="visually-hidden"
          aria-label="Choose the filled register"
          onChange={(event) => void choose(event.target.files?.[0])}
        />
      </div>
      {message ? (
        <p className={message.error ? "form-message" : "hint"} role={message.error ? "alert" : "status"}>
          {message.text}
        </p>
      ) : null}
      {which === "form" ? (
        <RegisterRaiseForm facts={facts} value={value} onChange={onChange} />
      ) : facts ? (
        <RegisterExcelView grid={facts.grid} label="The register as an Excel page" />
      ) : (
        <p role="status" className="hint">
          Drawing the register…
        </p>
      )}
    </div>
  );
}

/**
 * The register of a request that exists, in both views. The top never changes; the bottom is completed in the form by the
 * people asked to decide the request. The Excel page is the server's drawing of the same stored values and the file is the
 * same drawing, so a saved change shows in both.
 */
export function RegisterRequestPanel({
  templateId,
  requestId,
  view,
  onChanged,
}: {
  templateId: string;
  requestId: string;
  view: RegisterView;
  onChanged(next: RegisterView): void;
}) {
  const [which, setWhich] = useState<Which>("form");
  const [message, setMessage] = useState<{ text: string; error: boolean } | undefined>();

  const download = async () => {
    setMessage(undefined);
    const outcome = await downloadRegister(templateId, requestId);
    if (!outcome.ok) {
      setMessage({ text: outcome.failure.message, error: true });
      return;
    }
    saveBlob(outcome.data, registerFileName(view.requestNo));
  };

  return (
    <div className="stack" data-testid="register-request-panel">
      <div className="register-toolbar">
        <Toggle which={which} onChange={setWhich} />
        <button type="button" className="button button-secondary" onClick={() => void download()} data-testid="register-download">
          Download as Excel
        </button>
      </div>
      {message ? (
        <p className="form-message" role="alert">
          {message.text}
        </p>
      ) : null}
      {which === "form" ? (
        <RegisterViewForm
          key={view.version}
          templateId={templateId}
          requestId={requestId}
          view={view}
          onSaved={onChanged}
        />
      ) : (
        <RegisterExcelView grid={view.grid} label="The register as an Excel page" />
      )}
    </div>
  );
}
