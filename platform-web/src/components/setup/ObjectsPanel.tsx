"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { KIND_TEXT, problemText } from "./objectText";

type ObjectSummary = components["schemas"]["ObjectSummaryView"];

const EMPTY = { name: "", label: "", pluralLabel: "", description: "" };

/**
 * The object manager's list: the standard objects of the platform and the organization's own, and the form that makes a
 * new one. The page shows what the API answers and its own words for every refusal; it never decides who may do
 * what, which names are allowed or which objects exist.
 */
export function ObjectsPanel() {
  const [objects, setObjects] = useState<ObjectSummary[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState(EMPTY);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects");
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setObjects(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/metadata/objects", { body: draft });
      if (response.ok) {
        setDraft(EMPTY);
        return { error, response, text: "The object was created." };
      }
      return { error, response, problem: problemText(error, response) };
    });
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="objects-failed">
        {failure}
      </p>
    );
  }
  if (!objects) {
    return <p aria-live="polite">Loading…</p>;
  }

  return (
    <div className="stack">
      <p>
        Objects are the kinds of things the organization keeps records of. The standard objects are defined by the platform and
        cannot be changed; the organization can add its own fields to some of them and make objects of its own.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="objects" />

      <section aria-labelledby="object-form-heading">
        <h2 id="object-form-heading">Create an object</h2>
        <form className="form" onSubmit={create} noValidate>
          <div className="field">
            <label htmlFor="object-name">Name</label>
            <input
              id="object-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="object-name-hint"
            />
            <p className="hint" id="object-name-hint">
              The permanent name used by code and integrations, for example Employee. The platform adds the ending __c.
            </p>
          </div>
          <div className="field">
            <label htmlFor="object-label">Label</label>
            <input
              id="object-label"
              value={draft.label}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, label: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="object-plural">Plural label</label>
            <input
              id="object-plural"
              value={draft.pluralLabel}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, pluralLabel: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="object-description">Description</label>
            <input
              id="object-description"
              value={draft.description}
              maxLength={500}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>
          <button type="submit" className="button" disabled={busy}>
            Create the object
          </button>
        </form>
      </section>

      <section aria-labelledby="objects-heading">
        <h2 id="objects-heading">Objects</h2>
        <table className="table" data-testid="objects">
          <thead>
            <tr>
              <th>Object</th>
              <th>API name</th>
              <th>Kind</th>
              <th>Fields</th>
            </tr>
          </thead>
          <tbody>
            {objects.map((object) => (
              <tr key={object.apiName} data-testid="object-row">
                <td>
                  <Link href={`/setup/objects/${encodeURIComponent(object.apiName)}`}>{object.label}</Link>
                </td>
                <td>
                  <code>{object.apiName}</code>
                </td>
                <td>
                  {KIND_TEXT[object.kind] ?? object.kind}
                  {object.managedBy ? <div className="hint">Records managed by {object.managedBy}</div> : null}
                </td>
                <td>
                  {object.fieldCount}
                  {object.customFieldCount > 0 ? <div className="hint">{object.customFieldCount} added by the organization</div> : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
