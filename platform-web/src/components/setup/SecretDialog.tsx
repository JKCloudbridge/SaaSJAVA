"use client";

import { useRef, useState } from "react";
import { Modal } from "@/components/ui/Modal";

interface SecretDialogProps {
  /** The secret, held in the memory of the component that shows this dialog and nowhere else. */
  secret: string;
  onClose: () => void;
  /** The title of the dialog. */
  title?: string;
  /** The label of the box that holds the secret. */
  label?: string;
  /** What the secret is kept as, in the words of the screen: a hash, or sealed so that it can sign. */
  keptAs?: string;
  /** Identifiers for tests; the screens that show a secret each have their own. */
  testId?: string;
  inputId?: string;
}

/**
 * The one place a secret is shown: once, from memory, never again (Sprints 31 and 32). It is used by the connected
 * applications and by the webhooks. The secret is never put in an address, storage, a shared cache or a log: it lives in the
 * component that opened this dialog and is gone when the dialog closes or the page is left.
 */
export function SecretDialog({
  secret,
  onClose,
  title = "Copy the client secret now",
  label = "Client secret",
  keptAs = "It is kept only as a hash and cannot be shown again.",
  testId = "connected-app-secret-dialog",
  inputId = "connected-app-secret-value",
}: SecretDialogProps) {
  const field = useRef<HTMLInputElement>(null);
  const [copied, setCopied] = useState<"yes" | "no" | undefined>();

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied("yes");
    } catch {
      // Without the clipboard interface the text in the box is selected and copied from there; no other copy of it is made.
      const input = field.current;
      input?.focus();
      input?.select();
      setCopied(document.execCommand("copy") ? "yes" : "no");
    }
  };

  return (
    <Modal title={title} onClose={onClose} testId={testId}>
      <p>
        This is the only time the secret is shown. {keptAs} If it is lost, make a new secret.
      </p>
      <div className="field">
        <label htmlFor={inputId}>{label}</label>
        <input
          id={inputId}
          ref={field}
          className="mono"
          value={secret}
          readOnly
          autoComplete="off"
          spellCheck={false}
          onFocus={(event) => event.currentTarget.select()}
        />
      </div>
      {copied === "yes" ? <p role="status">Copied.</p> : null}
      {copied === "no" ? (
        <p className="form-message" role="alert">
          It could not be copied. Select the text and copy it.
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-secondary" onClick={() => void copy()}>
          Copy
        </button>
        <button type="button" className="button" onClick={onClose}>
          I have copied it
        </button>
      </div>
    </Modal>
  );
}
