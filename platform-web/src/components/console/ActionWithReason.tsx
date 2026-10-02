"use client";

import { useId, useState, type FormEvent } from "react";

interface Props {
  /** The button that opens the small form, and the button that sends it. */
  label: string;
  busy: boolean;
  /** When set, the person must type this text again to confirm (a final step such as closing an organization). */
  confirmWith?: string;
  onSubmit: (reason: string, confirm: string) => void;
}

/**
 * A button that asks for a reason before it acts. The reason is free text kept in the audit trail only: the page asks
 * for it without personal data and bounds it; the API checks it again and decides what is allowed.
 */
export function ActionWithReason({ label, busy, confirmWith, onSubmit }: Props) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [confirm, setConfirm] = useState("");
  const id = useId();

  if (!open) {
    return (
      <button type="button" className="button" disabled={busy} onClick={() => setOpen(true)}>
        {label}
      </button>
    );
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit(reason, confirm);
    setOpen(false);
    setReason("");
    setConfirm("");
  }

  return (
    <form className="form" onSubmit={submit} noValidate aria-label={label}>
      <div className="field">
        <label htmlFor={`${id}-reason`}>Reason (kept in the audit trail, no personal data)</label>
        <input
          id={`${id}-reason`}
          type="text"
          maxLength={200}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          required
        />
      </div>
      {confirmWith ? (
        <div className="field">
          <label htmlFor={`${id}-confirm`}>Type {confirmWith} to confirm</label>
          <input
            id={`${id}-confirm`}
            type="text"
            autoComplete="off"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            required
          />
        </div>
      ) : null}
      <div>
        <button type="submit" className="button" disabled={busy}>
          {label}
        </button>{" "}
        <button type="button" className="link-button" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </div>
    </form>
  );
}
