"use client";

import { useId, useState, type ReactNode } from "react";
import { formatInstant } from "@/lib/records/fieldValue";
import {
  completeRegister,
  REGISTER_LIMITS,
  type RegisterCompletion,
  type RegisterInput,
  type RegisterView,
} from "./register";

/** A box of the register: its label as the paper form words it, then what it holds. */
function Box({ label, wide = false, children }: { label: string; wide?: boolean; children: ReactNode }) {
  return (
    <div className={wide ? "register-box register-box-wide" : "register-box"}>
      <div className="register-label">{label}</div>
      <div className="register-value">{children}</div>
    </div>
  );
}

/** The text of a box that has none yet. */
const BLANK = <span className="hint">—</span>;
const text = (value: string | null | undefined) => (value && value.trim() !== "" ? <span className="comment-text">{value}</span> : BLANK);

/** The parts the platform writes when the request is raised, as they are known now. */
function Facts({ facts }: { facts: RegisterView | undefined }) {
  return (
    <>
      <Box label="Document Name :">{text(facts?.documentName)}</Box>
      <Box label="Requested Date:-">{text(facts?.requestedOn)}</Box>
      <Box label="Document No. :">{text(facts?.documentNo)}</Box>
      <Box label="Rev. No:">{text(facts?.revNo)}</Box>
      <Box label="Requested by :" wide>
        {text(facts?.requestedBy)}
      </Box>
    </>
  );
}

/**
 * The register the person who asks fills in (Functional track FA8, ADR-0202): the boxes they type, and beside them, read
 * only, the parts the platform writes (the document, the revision in use, who asks and the department). The bottom of the
 * register is completed by the people who decide the request, so it is not asked here, except the format or document that
 * stops being used, which the person who asks may say. The server checks every box again.
 */
export function RegisterRaiseForm({
  facts,
  value,
  onChange,
}: {
  facts: RegisterView | undefined;
  value: RegisterInput;
  onChange(next: RegisterInput): void;
}) {
  const ids = useId();
  const set = <K extends keyof RegisterInput>(key: K, next: string) => onChange({ ...value, [key]: next });
  return (
    <div className="register-form" data-testid="register-form">
      <Facts facts={facts} />
      <div className="register-box">
        <div className="register-label">
          <label htmlFor={`${ids}-no`}>Request No:-</label>
        </div>
        <div className="register-value">
          <input
            id={`${ids}-no`}
            type="text"
            value={value.requestNo}
            maxLength={REGISTER_LIMITS.requestNo}
            onChange={(event) => set("requestNo", event.target.value)}
            aria-required="true"
            data-testid="register-request-no"
          />
          <p className="hint">The number you keep this request under.</p>
        </div>
      </div>
      <div className="register-box register-box-wide">
        <div className="register-label">
          <label htmlFor={`${ids}-change`}>Change Requested -</label>
        </div>
        <div className="register-value">
          <textarea
            id={`${ids}-change`}
            rows={4}
            value={value.changeRequested}
            maxLength={REGISTER_LIMITS.changeRequested}
            onChange={(event) => set("changeRequested", event.target.value)}
            aria-required="true"
            data-testid="register-change"
          />
        </div>
      </div>
      <div className="register-box register-box-wide">
        <div className="register-label">
          <label htmlFor={`${ids}-basis`}>Basis for Change :</label>
        </div>
        <div className="register-value">
          <textarea
            id={`${ids}-basis`}
            rows={3}
            value={value.basisForChange ?? ""}
            maxLength={REGISTER_LIMITS.basisForChange}
            onChange={(event) => set("basisForChange", event.target.value)}
            data-testid="register-basis"
          />
        </div>
      </div>
      <div className="register-box register-box-wide">
        <div className="register-label">
          <label htmlFor={`${ids}-horizontal`}>Horizontal Deployment (If applicable) :</label>
        </div>
        <div className="register-value">
          <textarea
            id={`${ids}-horizontal`}
            rows={2}
            value={value.horizontalDeployment ?? ""}
            maxLength={REGISTER_LIMITS.horizontalDeployment}
            onChange={(event) => set("horizontalDeployment", event.target.value)}
            data-testid="register-horizontal"
          />
        </div>
      </div>
      <div className="register-box">
        <div className="register-label">
          <label htmlFor={`${ids}-obsolete`}>Format /Document obsoleted (if applicable)</label>
        </div>
        <div className="register-value">
          <input
            id={`${ids}-obsolete`}
            type="text"
            value={value.obsoletedFormat ?? ""}
            maxLength={REGISTER_LIMITS.obsoletedFormat}
            onChange={(event) => set("obsoletedFormat", event.target.value)}
            data-testid="register-obsoleted"
          />
        </div>
      </div>
      <div className="register-box">
        <div className="register-label">
          <label htmlFor={`${ids}-obsolete-on`}>Effective Date :</label>
        </div>
        <div className="register-value">
          <input
            id={`${ids}-obsolete-on`}
            type="date"
            value={value.obsoletedEffectiveOn ?? ""}
            onChange={(event) => set("obsoletedEffectiveOn", event.target.value)}
            data-testid="register-obsoleted-on"
          />
        </div>
      </div>
      <p className="hint register-wide-note">
        The approval, the new revision, its dates and the answers about the server and the master list are filled in by the
        people who decide the request, and by the platform when the revision is published.
      </p>
    </div>
  );
}

/** What the people who decide a request type in the bottom of the register. */
interface Bottom {
  department: string;
  obsoletedFormat: string;
  obsoletedEffectiveOn: string;
  approvedBy: string;
  signatureNote: string;
  effectiveOn: string;
  serverUpdated: string;
  masterListUpdated: string;
}

const bottomOf = (view: RegisterView): Bottom => ({
  department: view.department ?? "",
  obsoletedFormat: view.obsoletedFormat ?? "",
  obsoletedEffectiveOn: view.obsoletedEffectiveOn ?? "",
  approvedBy: view.approvedBy ?? "",
  signatureNote: view.signatureNote ?? "",
  effectiveOn: view.effectiveOn ?? "",
  serverUpdated: view.serverUpdated ?? "",
  masterListUpdated: view.masterListUpdated ?? "",
});

/**
 * The register of a request that exists: the top as it was written and never changes, the bottom completed by the people
 * asked to decide the request while it is in progress. `view.canComplete` is the API's word on who they are; the save asks
 * the server again, which also refuses a register that changed since it was read. The new revision and the day it was
 * published are stamped by the publish and shown, never typed.
 */
export function RegisterViewForm({
  templateId,
  requestId,
  view,
  onSaved,
}: {
  templateId: string;
  requestId: string;
  view: RegisterView;
  onSaved(next: RegisterView): void;
}) {
  const ids = useId();
  const [form, setForm] = useState<Bottom>(() => bottomOf(view));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | undefined>();
  const [problems, setProblems] = useState<string[]>([]);
  const editable = view.canComplete;
  const set = <K extends keyof Bottom>(key: K, next: string) => setForm((now) => ({ ...now, [key]: next }));

  const save = async () => {
    setBusy(true);
    setMessage(undefined);
    setProblems([]);
    const body: RegisterCompletion = {
      version: view.version,
      department: form.department,
      obsoletedFormat: form.obsoletedFormat,
      obsoletedEffectiveOn: form.obsoletedEffectiveOn,
      approvedBy: form.approvedBy,
      signatureNote: form.signatureNote,
      effectiveOn: form.effectiveOn,
      serverUpdated: form.serverUpdated,
      masterListUpdated: form.masterListUpdated,
    };
    const outcome = await completeRegister(templateId, requestId, body);
    setBusy(false);
    if (!outcome.ok) {
      setMessage({ text: outcome.failure.message, error: true });
      setProblems(Object.values(outcome.failure.fields ?? {}).flat());
      return;
    }
    setMessage({ text: "The register was saved.", error: false });
    onSaved(outcome.data);
  };

  const input = (key: keyof Bottom, label: string, kind: "text" | "date" | "area", most?: number, wide = false) => (
    <div className={wide ? "register-box register-box-wide" : "register-box"}>
      <div className="register-label">
        <label htmlFor={`${ids}-${key}`}>{label}</label>
      </div>
      <div className="register-value">
        {kind === "area" ? (
          <textarea
            id={`${ids}-${key}`}
            rows={3}
            value={form[key]}
            maxLength={most}
            onChange={(event) => set(key, event.target.value)}
          />
        ) : (
          <input
            id={`${ids}-${key}`}
            type={kind}
            value={form[key]}
            maxLength={most}
            onChange={(event) => set(key, event.target.value)}
          />
        )}
      </div>
    </div>
  );
  const answer = (key: "serverUpdated" | "masterListUpdated", label: string) => (
    <div className="register-box register-box-wide">
      <div className="register-label">
        <label htmlFor={`${ids}-${key}`}>{label}</label>
      </div>
      <div className="register-value">
        <select id={`${ids}-${key}`} value={form[key]} onChange={(event) => set(key, event.target.value)}>
          <option value="">Not answered yet</option>
          <option value="YES">Yes</option>
          <option value="NO">No</option>
        </select>
      </div>
    </div>
  );
  const shown = (label: string, value: string | null | undefined, wide = false) => (
    <Box label={label} wide={wide}>
      {text(value)}
    </Box>
  );

  return (
    <div className="register-form" data-testid="register-view-form">
      {shown("Document Name :", view.documentName)}
      {shown("Request No:-", view.requestNo)}
      {shown("Requested Date:-", view.requestedOn)}
      {shown("Document No. :", view.documentNo)}
      {shown("Rev. No:", view.revNo)}
      {shown("Change Requested -", view.changeRequested, true)}
      {shown("Requested by :", view.requestedBy, true)}
      {shown("Basis for Change :", view.basisForChange, true)}
      {shown("Horizontal Deployment (If applicable) :", view.horizontalDeployment, true)}
      {editable ? (
        <>
          {input("approvedBy", "Approved By:", "area", REGISTER_LIMITS.approvedBy)}
          {input("signatureNote", "Signature", "area", REGISTER_LIMITS.signatureNote)}
        </>
      ) : (
        <>
          {shown("Approved By:", view.approvedBy)}
          {shown("Signature", view.signatureNote)}
        </>
      )}
      <Box label="New Rev. No:">
        {view.newRevNo ? (
          <span data-testid="register-new-rev">{view.newRevNo}</span>
        ) : (
          <span className="hint">Written when the revision is published</span>
        )}
      </Box>
      {editable ? input("effectiveOn", "Effective Date :", "date") : shown("Effective Date :", view.effectiveOn)}
      <Box label="New Revision date:">
        {view.newRevisionAt ? (
          <span data-testid="register-new-date">{formatInstant(view.newRevisionAt)}</span>
        ) : (
          <span className="hint">Stamped when the revision is published</span>
        )}
      </Box>
      {editable ? input("department", "Department:", "text", REGISTER_LIMITS.department) : shown("Department:", view.department)}
      {editable
        ? input("obsoletedFormat", "Format /Document obsoleted (if applicable)", "text", REGISTER_LIMITS.obsoletedFormat)
        : shown("Format /Document obsoleted (if applicable)", view.obsoletedFormat)}
      {editable
        ? input("obsoletedEffectiveOn", "Effective Date :", "date")
        : shown("Effective Date :", view.obsoletedEffectiveOn)}
      {editable ? (
        <>
          {answer("serverUpdated", "Is revised document updated on server or issued in hard copy?  Yes/No")}
          {answer("masterListUpdated", "Is master list updated with respect to latest revision? Yes/No")}
        </>
      ) : (
        <>
          {shown(
            "Is revised document updated on server or issued in hard copy?  Yes/No",
            view.serverUpdated === "YES" ? "Yes, updated on the common server" : view.serverUpdated === "NO" ? "No" : null,
            true,
          )}
          {shown(
            "Is master list updated with respect to latest revision? Yes/No",
            view.masterListUpdated === "YES" ? "Yes, updated" : view.masterListUpdated === "NO" ? "No" : null,
            true,
          )}
        </>
      )}
      {editable ? (
        <div className="register-wide-note stack">
          <p className="hint">
            You decide this request now, so you can complete the bottom of the register. What you leave empty is filled in by
            the platform when the revision is published.
          </p>
          <div>
            <button type="button" className="button" disabled={busy} onClick={() => void save()} data-testid="register-save">
              {busy ? "Saving…" : "Save the register"}
            </button>
          </div>
        </div>
      ) : null}
      {message ? (
        <p className={message.error ? "form-message register-wide-note" : "hint register-wide-note"} role={message.error ? "alert" : "status"}>
          {message.text}
        </p>
      ) : null}
      {problems.length > 0 ? (
        <ul className="form-message register-wide-note" role="alert">
          {problems.map((problem, index) => (
            <li key={`${problem}-${index}`}>{problem}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
