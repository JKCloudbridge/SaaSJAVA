"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { FORGERY_HEADER, readCookie, FORGERY_COOKIE } from "@/lib/api/forgery";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import type { components } from "@/lib/api/generated/schema";

type RecordFile = components["schemas"]["RecordFileView"];

/** Bytes, read for people: 925 → "925 B", 1 234 567 → "1.2 MB". */
function readableSize(bytes?: number): string {
  if (bytes === undefined) {
    return "";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/**
 * The files of a record (Sprint 17, surfaced on the record page in Sprint 20, ADR-0131): a file inherits the
 * record's own permission (a caller who may read the record may read its files; changing them needs the right to
 * change the record, which the server decides again on every call). The upload is the bytes of the file, not a
 * form (the API's own choice, so no boundary is invented here): the component only reflects the size, count and
 * type limits the server already enforces, for feedback, never as a second check.
 */
export function RecordFiles({ objectApiName, recordId, mayChange }: {
  objectApiName: string;
  recordId: string;
  mayChange: boolean;
}) {
  const [files, setFiles] = useState<RecordFile[] | undefined>();
  const [message, setMessage] = useState<string | undefined>();
  const [uploading, setUploading] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const [attempt, setAttempt] = useState(0);
  const load = () => setAttempt((value) => value + 1);

  useEffect(() => {
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}/files", { params: { path: { objectApiName, id: recordId } } }),
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
  }, [objectApiName, recordId, attempt]);

  async function upload(file: File) {
    setUploading(true);
    setMessage(undefined);
    await ensureForgeryCookie();
    const token = readCookie(FORGERY_COOKIE);
    const response = await fetch(`/api/v1/objects/${encodeURIComponent(objectApiName)}/records/${recordId}/files`, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": file.type || "application/octet-stream",
        "X-File-Name": encodeURIComponent(file.name),
        ...(token ? { [FORGERY_HEADER]: token } : {}),
      },
      body: file,
    });
    setUploading(false);
    if (response.ok) {
      load();
    } else {
      const body = await response.json().catch(() => undefined);
      setMessage(body?.error?.message ?? "The file could not be stored.");
    }
  }

  async function remove(fileId: string) {
    const outcome = await call(() =>
      api.DELETE("/api/v1/objects/{objectApiName}/records/{id}/files/{fileId}", {
        params: { path: { objectApiName, id: recordId, fileId } },
      }),
    );
    if (outcome.ok) {
      load();
    } else {
      setMessage(outcome.failure.message);
    }
  }

  return (
    <div className="stack" data-testid="record-files">
      {message ? (
        <p className="form-message" role="alert">
          {message}
        </p>
      ) : null}
      {!files ? (
        <p className="hint">Loading…</p>
      ) : files.length === 0 ? (
        <p className="hint">No files.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Size</th>
              {mayChange ? <th /> : null}
            </tr>
          </thead>
          <tbody>
            {files.map((file) => (
              <tr key={file.id}>
                <td>
                  <a href={`/api/v1/objects/${encodeURIComponent(objectApiName)}/records/${recordId}/files/${file.id}/content`}>
                    {file.name}
                  </a>
                </td>
                <td>{readableSize(file.size)}</td>
                {mayChange ? (
                  <td>
                    <button type="button" className="link-button" onClick={() => void remove(file.id)}>
                      Remove
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {mayChange ? (
        <div>
          <input
            ref={input}
            type="file"
            aria-label="Add a file"
            disabled={uploading}
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (file) {
                void upload(file);
              }
            }}
          />
        </div>
      ) : null}
    </div>
  );
}
