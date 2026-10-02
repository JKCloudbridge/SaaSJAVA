"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Catalogue = components["schemas"]["DataCatalogue"];
type Entry = components["schemas"]["PermissionEntry"];

/** What a permission matrix belongs to: a profile, an access policy, or what is granted to one member directly. */
export type DataAccessTarget = { kind: "profile" | "policy" | "member"; id: string };

/** The ticked actions per key (an object key, or `<object key>.<field key>`). */
type Ticks = Record<string, string[]>;

interface Loaded {
  catalogue: Catalogue;
  everything: boolean;
  objects: Ticks;
  fields: Ticks;
}

function toTicks(entries: Entry[]): Ticks {
  const result: Ticks = {};
  for (const entry of entries) {
    result[entry.key] = [...entry.actions];
  }
  return result;
}

function toEntries(ticks: Ticks): Entry[] {
  return Object.entries(ticks)
    .filter(([, actions]) => actions.length > 0)
    .map(([key, actions]) => ({ key, actions }));
}

async function read(target: DataAccessTarget) {
  switch (target.kind) {
    case "profile":
      return api.GET("/api/v1/profiles/{profileId}/data-access", { params: { path: { profileId: target.id } } });
    case "policy":
      return api.GET("/api/v1/access-policies/{policyId}/data-access", { params: { path: { policyId: target.id } } });
    case "member":
      return api.GET("/api/v1/members/{membershipId}/data-access", { params: { path: { membershipId: target.id } } });
  }
}

async function write(target: DataAccessTarget, body: { objects: Entry[]; fields: Entry[] }) {
  switch (target.kind) {
    case "profile":
      return api.PUT("/api/v1/profiles/{profileId}/data-access", { params: { path: { profileId: target.id } }, body });
    case "policy":
      return api.PUT("/api/v1/access-policies/{policyId}/data-access", {
        params: { path: { policyId: target.id } },
        body,
      });
    case "member":
      return api.PUT("/api/v1/members/{membershipId}/data-access", {
        params: { path: { membershipId: target.id } },
        body,
      });
  }
}

/**
 * The permission matrix of one profile, access policy or member: which objects it may read, create, update and delete,
 * and which fields of them it may read and edit. The page lists what the catalogue says exists and what the API says is
 * allowed; it never decides anything: the actions an action implies are shown as ticked only to explain the answer, and
 * every request is decided again by the server, in its own words when it refuses. Objects arrive with the object manager;
 * until then the catalogue is empty and the page says so.
 */
export function DataAccessEditor({ target, title }: { target: DataAccessTarget; title: string }) {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [objects, setObjects] = useState<Ticks>({});
  const [fields, setFields] = useState<Ticks>({});
  // The parent builds a new target object on every render; only its two values decide what is loaded.
  const { kind, id } = target;

  const reload = useCallback(async () => {
    try {
      const [catalogue, matrix] = await Promise.all([api.GET("/api/v1/data-catalogue"), read({ kind, id })]);
      if (!catalogue.data) {
        setFailure(await failureText(catalogue.error, catalogue.response));
        return;
      }
      if (!matrix.data) {
        setFailure(await failureText(matrix.error, matrix.response));
        return;
      }
      setFailure(undefined);
      const view = matrix.data.data;
      setLoaded({
        catalogue: catalogue.data.data,
        everything: view.everything ?? false,
        objects: toTicks(view.objects),
        fields: toTicks(view.fields),
      });
      setObjects(toTicks(view.objects));
      setFields(toTicks(view.fields));
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [kind, id]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="data-access-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (loaded.everything) {
    return (
      <section aria-label={title} data-testid="data-access-everything">
        <h3>{title}</h3>
        <p>This profile always has every permission on every object and field. It cannot be changed.</p>
      </section>
    );
  }
  const catalogue = loaded.catalogue;
  if (catalogue.objects.length === 0) {
    return (
      <section aria-label={title} data-testid="data-access-empty">
        <h3>{title}</h3>
        <p>There are no objects yet. Objects and their fields arrive with the object manager (Sprint 10).</p>
      </section>
    );
  }

  const impliedBy = (actions: { key: string; implies: string[] }[], ticked: string[], key: string) =>
    ticked.some((other) => other !== key && actions.find((action) => action.key === other)?.implies.includes(key));

  const toggle = (
    setter: (update: (previous: Ticks) => Ticks) => void,
    key: string,
    action: string,
    on: boolean,
  ) =>
    setter((previous) => {
      const current = previous[key] ?? [];
      const next = on ? [...new Set([...current, action])] : current.filter((existing) => existing !== action);
      return { ...previous, [key]: next };
    });

  const save = () =>
    act(async () => {
      const result = await write({ kind, id }, { objects: toEntries(objects), fields: toEntries(fields) });
      return { error: result.error, response: result.response, text: "The permissions were saved." };
    });

  return (
    <section aria-label={title} data-testid="data-access-editor">
      <h3>{title}</h3>
      <p className="hint">
        What is not ticked is not allowed. Update, create, delete and view-all include read; modify-all includes all the
        others; editing a field includes reading it. A field also needs the permission on its object. View-all and
        modify-all only start to matter when record-level security arrives.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="data-access" />
      {catalogue.objects.map((object) => {
        const ticked = objects[object.key] ?? [];
        return (
          <fieldset key={object.key} data-testid="data-access-object">
            <legend>{object.label}</legend>
            <div>
              {catalogue.objectActions.map((action) => {
                const implied = impliedBy(catalogue.objectActions, ticked, action.key);
                return (
                  <label key={action.key}>
                    <input
                      type="checkbox"
                      aria-label={`${object.label}: ${action.title}`}
                      checked={ticked.includes(action.key) || implied}
                      disabled={busy || implied}
                      onChange={(event) => toggle(setObjects, object.key, action.key, event.target.checked)}
                    />{" "}
                    {action.title}{" "}
                  </label>
                );
              })}
            </div>
            {object.fields.length === 0 ? null : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Field</th>
                    {catalogue.fieldActions.map((action) => (
                      <th key={action.key}>{action.title}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {object.fields.map((field) => {
                    const fieldKey = `${object.key}.${field.key}`;
                    const fieldTicked = fields[fieldKey] ?? [];
                    return (
                      <tr key={fieldKey}>
                        <td>{field.label}</td>
                        {catalogue.fieldActions.map((action) => {
                          const implied = impliedBy(catalogue.fieldActions, fieldTicked, action.key);
                          return (
                            <td key={action.key}>
                              <input
                                type="checkbox"
                                aria-label={`${object.label} / ${field.label}: ${action.title}`}
                                checked={fieldTicked.includes(action.key) || implied}
                                disabled={busy || implied}
                                onChange={(event) => toggle(setFields, fieldKey, action.key, event.target.checked)}
                              />
                            </td>
                          );
                        })}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </fieldset>
        );
      })}
      <button type="button" className="button" disabled={busy} onClick={() => void save()}>
        Save the permissions
      </button>
    </section>
  );
}
