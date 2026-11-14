"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { FORGERY_COOKIE, FORGERY_HEADER, readCookie } from "@/lib/api/forgery";
import type { components } from "@/lib/api/generated/schema";
import { attachmentWords } from "@/lib/sheets/fillModel";
import { call } from "@/lib/records/call";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

type RecordFile = components["schemas"]["RecordFileView"];

export interface FillAttachmentsProps {
  /** The header record of the fill-in: its files are the fill-in's files (ADR-0254). */
  object: string;
  recordId: string;
  /** The identifiers the cell names now. */
  value: readonly string[];
  /** The most files this cell holds (the sheet states it; the server checks again). */
  maxFiles: number;
  /** True when the files may be looked at but not changed. */
  disabled: boolean;
  /** Names the files in the cell; answers with the editor's refusal in words, or null. */
  onChange(files: string[]): string | null;
  /**
   * Called before a file is taken off: the cells are saved first, because taking a file off changes the stored cells and
   * their version on the server. Answers whether it may go on.
   */
  beforeRemove(): Promise<boolean>;
  /** Called after a file was taken off: the page reads the fill-in again, because the stored cells changed. */
  afterRemove(): Promise<void>;
  /** Called after a file was stored, so that the cell naming it can be saved by the next save. */
  afterAdd?(): void;
}

/**
 * The attach control of an attachment cell (SH5, ADR-0254): choose a file, see how many are attached and their names,
 * take one off. The bytes go to the header record's files, the same calls the record page makes; the cell names a file only
 * by the identifier the file service gave it, never by its name or an address. It decides nothing: the file service and the
 * fill-in's own rules answer, and their words are shown as they are.
 */
export function FillAttachments({
  object,
  recordId,
  value,
  maxFiles,
  disabled,
  onChange,
  beforeRemove,
  afterRemove,
  afterAdd,
}: FillAttachmentsProps) {
  const [files, setFiles] = useState<RecordFile[] | undefined>();
  const [message, setMessage] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}/files", {
        params: { path: { objectApiName: object, id: recordId } },
      }),
    ).then((outcome) => {
      if (!live) {
        return;
      }
      if (outcome.ok) {
        setFiles(outcome.data.data);
      } else {
        setMessage(outcome.failure.message);
      }
    });
    return () => {
      live = false;
    };
  }, [object, recordId, attempt]);

  async function add(file: File) {
    if (value.length >= maxFiles) {
      setMessage(`This cell holds at most ${maxFiles} files.`);
      return;
    }
    setBusy(true);
    setMessage(undefined);
    await ensureForgeryCookie();
    const token = readCookie(FORGERY_COOKIE);
    const response = await fetch(`/api/v1/objects/${encodeURIComponent(object)}/records/${recordId}/files`, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": file.type || "application/octet-stream",
        "X-File-Name": encodeURIComponent(file.name),
        ...(token ? { [FORGERY_HEADER]: token } : {}),
      },
      body: file,
    }).catch(() => null);
    setBusy(false);
    if (!response) {
      setMessage("The file could not be stored. Try again.");
      return;
    }
    const body = (await response.json().catch(() => undefined)) as
      | { data?: { id?: string }; error?: { message?: string } }
      | undefined;
    const id = body?.data?.id;
    if (!response.ok || typeof id !== "string") {
      setMessage(body?.error?.message ?? "The file could not be stored.");
      return;
    }
    // Named in the cell by the same save that sends the cells (the editor's change is the next thing saved).
    const refused = onChange([...value, id]);
    if (refused) {
      setMessage(refused);
      return;
    }
    setAttempt((n) => n + 1);
    afterAdd?.();
  }

  async function remove(fileId: string) {
    setMessage(undefined);
    if (!(await beforeRemove())) {
      setMessage("Save your changes first, then take the file off.");
      return;
    }
    setBusy(true);
    const outcome = await call(() =>
      api.DELETE("/api/v1/objects/{objectApiName}/records/{id}/files/{fileId}", {
        params: { path: { objectApiName: object, id: recordId, fileId } },
      }),
    );
    setBusy(false);
    if (!outcome.ok) {
      setMessage(outcome.failure.message);
      return;
    }
    // The server took the identifier out of the stored cells in the same transaction; read the fill-in again.
    await afterRemove();
    setAttempt((n) => n + 1);
  }

  const named = (files ?? []).filter((file) => value.includes(file.id));
  return (
    <div className="stack" data-testid="fill-attachments">
      <p className="hint">{attachmentWords(value.length)}</p>
      {message ? (
        <p className="field-error" role="alert">
          {message}
        </p>
      ) : null}
      {named.length > 0 ? (
        <ul>
          {named.map((file) => (
            <li key={file.id}>
              <a href={`/api/v1/objects/${encodeURIComponent(object)}/records/${recordId}/files/${file.id}/content`}>
                {file.name}
              </a>{" "}
              {disabled ? null : (
                <button type="button" className="link-button" disabled={busy} onClick={() => void remove(file.id)}>
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : null}
      {disabled || value.length >= maxFiles ? null : (
        <div>
          <input
            ref={input}
            type="file"
            aria-label="Attach a file"
            disabled={busy}
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (file) {
                void add(file);
              }
            }}
          />
        </div>
      )}
    </div>
  );
}
