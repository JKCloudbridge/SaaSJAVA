"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import type { FormField, RecordShare, RecordView } from "@/lib/records/types";
import { LookupPicker } from "./LookupPicker";

/**
 * Who a record is shared with, and its owner (Sprint 17/17A, surfaced on the record page in Sprint 20, ADR-0131):
 * reads and writes nothing of its own, only the record's existing endpoints (`/records/{id}/shares`,
 * `/records/{id}/owner`). The picker for a person is the same {@link LookupPicker} a lookup field uses, aimed at the
 * `ownerId` system field every object already has, so searching for a person here is the one search the platform has,
 * not a second one. Sharing with a group (Sprint 17 also built this on the server) has no picker here yet: the
 * administrator names a group's identifier directly until a group picker exists (not part of this sprint's scope).
 *
 * <p>A person in the list is named by the same labels endpoint a link uses (a name that cannot be given reads "A
 * person"); a group has no name to ask for yet, so it reads "A group". An identifier is never shown.
 */
export function SharePanel({
  objectApiName,
  record,
  ownerField,
  onClose,
  onOwnerChanged,
}: {
  objectApiName: string;
  record: RecordView;
  /** The `ownerId` field of the object's own description, reused to pick a person. */
  ownerField: FormField;
  onClose: () => void;
  onOwnerChanged: (record: RecordView) => void;
}) {
  const [shares, setShares] = useState<RecordShare[] | undefined>();
  const [message, setMessage] = useState<string | undefined>();
  const [newOwner, setNewOwner] = useState({ id: "", label: "" });
  const [shareWith, setShareWith] = useState({ id: "", label: "" });
  const [level, setLevel] = useState<"READ" | "READ_WRITE">("READ");
  const [busy, setBusy] = useState(false);
  const [names, setNames] = useState<Record<string, string>>({});
  const asked = useRef<Set<string>>(new Set());

  const [attempt, setAttempt] = useState(0);
  const load = () => setAttempt((value) => value + 1);

  useEffect(() => {
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}/shares", {
        params: { path: { objectApiName, id: record.id } },
      }),
    ).then((outcome) => {
      if (!live) {
        return;
      }
      setShares(outcome.ok ? outcome.data.data : []);
      if (!outcome.ok) {
        setMessage(outcome.failure.message);
      }
    });
    return () => {
      live = false;
    };
  }, [objectApiName, record.id, attempt]);

  // The names of the people the record is shared with, each asked for once.
  useEffect(() => {
    const ids = (shares ?? [])
      .filter((entry) => entry.granteeType === "USER")
      .map((entry) => entry.granteeId)
      .filter((id) => !asked.current.has(id));
    if (ids.length === 0) {
      return;
    }
    ids.forEach((id) => asked.current.add(id));
    void call(() =>
      api.POST("/api/v1/objects/{objectApiName}/lookups/{fieldApiName}/labels", {
        params: { path: { objectApiName, fieldApiName: ownerField.apiName } },
        body: { ids },
      }),
    ).then((outcome) => {
      if (outcome.ok) {
        setNames((current) => ({ ...current, ...outcome.data.data.labels }));
      }
    });
  }, [shares, objectApiName, ownerField.apiName]);

  async function share() {
    if (!shareWith.id) {
      return;
    }
    setBusy(true);
    const outcome = await call(() =>
      api.PUT("/api/v1/objects/{objectApiName}/records/{id}/shares", {
        params: { path: { objectApiName, id: record.id } },
        body: { granteeType: "USER", granteeId: shareWith.id, accessLevel: level },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      setShareWith({ id: "", label: "" });
      load();
    } else {
      setMessage(outcome.failure.message);
    }
  }

  async function unshare(granteeId: string) {
    setBusy(true);
    const outcome = await call(() =>
      api.DELETE("/api/v1/objects/{objectApiName}/records/{id}/shares/{granteeId}", {
        params: { path: { objectApiName, id: record.id, granteeId } },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      load();
    } else {
      setMessage(outcome.failure.message);
    }
  }

  async function changeOwner() {
    if (!newOwner.id) {
      return;
    }
    setBusy(true);
    const outcome = await call(() =>
      api.POST("/api/v1/objects/{objectApiName}/records/{id}/owner", {
        params: { path: { objectApiName, id: record.id } },
        body: { ownerId: newOwner.id, version: record.version },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      onOwnerChanged(outcome.data.data);
      setNewOwner({ id: "", label: "" });
    } else {
      setMessage(outcome.failure.message);
    }
  }

  return (
    <div className="card" role="dialog" aria-label="Sharing">
      <h2>Sharing</h2>
      {message ? (
        <p className="form-message" role="alert">
          {message}
        </p>
      ) : null}

      <h3>Owner</h3>
      <div className="field">
        <label htmlFor="share-owner">New owner</label>
        <LookupPicker
          objectApiName={objectApiName}
          field={ownerField}
          id="share-owner"
          value={newOwner.id}
          label={newOwner.label}
          onChange={(id, label) => setNewOwner({ id, label })}
          disabled={busy}
        />
        <button type="button" className="button button-secondary" onClick={() => void changeOwner()} disabled={busy || !newOwner.id}>
          Give to this owner
        </button>
      </div>

      <h3>Shared with</h3>
      {!shares ? (
        <p className="hint">Loading…</p>
      ) : shares.length === 0 ? (
        <p className="hint">Not shared with anyone beyond the owner.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Who</th>
              <th>Level</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {shares.map((share) => (
              <tr key={share.id}>
                <td>
                  {share.granteeType === "USER" ? (names[share.granteeId] ?? "A person") : "A group"}
                </td>
                <td>{share.accessLevel === "READ_WRITE" ? "Read and change" : "Read"}</td>
                <td>
                  <button type="button" className="link-button" onClick={() => void unshare(share.granteeId)} disabled={busy}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>Share with a person</h3>
      <div className="field">
        <label htmlFor="share-with">Person</label>
        <LookupPicker
          objectApiName={objectApiName}
          field={ownerField}
          id="share-with"
          value={shareWith.id}
          label={shareWith.label}
          onChange={(id, label) => setShareWith({ id, label })}
          disabled={busy}
        />
      </div>
      <div className="field">
        <label htmlFor="share-level">Level</label>
        <select id="share-level" value={level} onChange={(event) => setLevel(event.target.value as "READ" | "READ_WRITE")}>
          <option value="READ">Read</option>
          <option value="READ_WRITE">Read and change</option>
        </select>
      </div>
      <button type="button" className="button" onClick={() => void share()} disabled={busy || !shareWith.id}>
        Share
      </button>

      <p>
        <button type="button" className="link-button" onClick={onClose}>
          Close
        </button>
      </p>
    </div>
  );
}
