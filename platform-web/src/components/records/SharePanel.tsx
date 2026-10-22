"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import type { FormField, RecordShare, RecordView } from "@/lib/records/types";
import { LookupPicker } from "./LookupPicker";

type Kind = "USER" | "GROUP" | "ROLE" | "ROLE_AND_SUBORDINATES";

/** What the person chooses to share with. The kinds are the API's; the words are for people. */
const KINDS: { kind: Kind; words: string }[] = [
  { kind: "USER", words: "A person" },
  { kind: "GROUP", words: "A group" },
  { kind: "ROLE", words: "A role" },
  { kind: "ROLE_AND_SUBORDINATES", words: "A role and the roles below it" },
];

/** How a share to a group or a role reads in the list; a name that is gone says so. */
const LISTED: Record<Exclude<Kind, "USER">, { prefix: string; gone: string }> = {
  GROUP: { prefix: "Group", gone: "A group that no longer exists" },
  ROLE: { prefix: "Role", gone: "A role that no longer exists" },
  ROLE_AND_SUBORDINATES: { prefix: "Role and the roles below it", gone: "A role that no longer exists" },
};

type Target = { id: string; label: string };

/**
 * Who a record is shared with, and its owner (Sprint 17/17A, surfaced on the record page in Sprint 20, ADR-0131;
 * roles and groups since Functional track FA2B, ADR-0185): reads and writes nothing of its own, only the record's
 * existing endpoints (`/records/{id}/shares`, `/records/{id}/share-targets`, `/records/{id}/owner`). The picker for a
 * person is the same {@link LookupPicker} a lookup field uses, aimed at the `ownerId` system field every object already
 * has, so searching for a person here is the one search the platform has, not a second one. A group or a role is picked
 * from the names the server offers to whoever may share the record (a short, searchable list: names only, nothing about
 * the people in it).
 *
 * <p>A person in the list is named by the same labels endpoint a link uses (a name that cannot be given reads "A
 * person"); a group or a role is named by the server ("Group: ...", "Role: ...", "Role and the roles below it: ..."),
 * and one that no longer exists says so. An identifier is never shown, and the panel holds no rule of who sees what:
 * the server decides, and its words are shown for every refusal.
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
  const [kind, setKind] = useState<Kind>("USER");
  const [search, setSearch] = useState("");
  const [targets, setTargets] = useState<Target[] | undefined>();
  const [targetId, setTargetId] = useState("");
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

  // The roles or groups to choose from, for the kind chosen; both role kinds choose from the same list.
  const listKind = kind === "GROUP" ? "GROUP" : "ROLE";
  useEffect(() => {
    if (kind === "USER") {
      return;
    }
    let live = true;
    const timer = setTimeout(
      () => {
        void call(() =>
          api.GET("/api/v1/objects/{objectApiName}/records/{id}/share-targets", {
            params: { path: { objectApiName, id: record.id }, query: { kind: listKind, search: search || undefined } },
          }),
        ).then((outcome) => {
          if (!live) {
            return;
          }
          setTargets(outcome.ok ? outcome.data.data : []);
          if (!outcome.ok) {
            setMessage(outcome.failure.message);
          }
        });
      },
      search ? 250 : 0,
    );
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [kind, listKind, search, objectApiName, record.id]);

  function chooseKind(next: Kind) {
    setKind(next);
    setTargetId("");
    setTargets(undefined);
    setSearch("");
    setShareWith({ id: "", label: "" });
  }

  const chosen = kind === "USER" ? shareWith.id : targetId;

  async function share() {
    if (!chosen) {
      return;
    }
    setBusy(true);
    const outcome = await call(() =>
      api.PUT("/api/v1/objects/{objectApiName}/records/{id}/shares", {
        params: { path: { objectApiName, id: record.id } },
        body: { granteeType: kind, granteeId: chosen, accessLevel: level },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      setShareWith({ id: "", label: "" });
      setTargetId("");
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
                <td>{whoIs(share, names)}</td>
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

      <h3>Share with</h3>
      <div className="field">
        <label htmlFor="share-kind">Share with</label>
        <select id="share-kind" value={kind} onChange={(event) => chooseKind(event.target.value as Kind)} disabled={busy}>
          {KINDS.map((entry) => (
            <option key={entry.kind} value={entry.kind}>
              {entry.words}
            </option>
          ))}
        </select>
      </div>
      {kind === "USER" ? (
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
      ) : (
        <>
          <div className="field">
            <label htmlFor="share-target-search">Search {listKind === "GROUP" ? "groups" : "roles"}</label>
            <input
              id="share-target-search"
              type="search"
              value={search}
              maxLength={80}
              onChange={(event) => setSearch(event.target.value)}
              disabled={busy}
            />
          </div>
          <div className="field">
            <label htmlFor="share-target">{KINDS.find((entry) => entry.kind === kind)?.words}</label>
            <select id="share-target" value={targetId} onChange={(event) => setTargetId(event.target.value)} disabled={busy}>
              <option value="">
                {targets === undefined ? "Loading…" : targets.length === 0 ? "None found" : "Choose…"}
              </option>
              {(targets ?? []).map((target) => (
                <option key={target.id} value={target.id}>
                  {target.label}
                </option>
              ))}
            </select>
          </div>
        </>
      )}
      <div className="field">
        <label htmlFor="share-level">Level</label>
        <select id="share-level" value={level} onChange={(event) => setLevel(event.target.value as "READ" | "READ_WRITE")}>
          <option value="READ">Read</option>
          <option value="READ_WRITE">Read and change</option>
        </select>
      </div>
      <button type="button" className="button" onClick={() => void share()} disabled={busy || !chosen}>
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

/** How one share reads in the list: a person by the name asked for, a group or a role by the server's name. */
function whoIs(share: RecordShare, names: Record<string, string>): string {
  if (share.granteeType === "USER") {
    return names[share.granteeId] ?? "A person";
  }
  const words = LISTED[share.granteeType as Exclude<Kind, "USER">];
  if (!words) {
    return "Someone";
  }
  return share.granteeLabel ? `${words.prefix}: ${share.granteeLabel}` : words.gone;
}
