"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { LayoutPreview } from "./LayoutPreview";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];

/** The fields a layout can show: the object's own, standard and custom ones; the system fields are the platform's. */
function showable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

/**
 * The page layouts of one object: the list, a preview of each, and the way into the builder where a layout is made and
 * changed (drag fields into sections). Removing is done here, live (one step in the history) or, when a change set is
 * chosen, as a draft. The page shows what the API answers: what depends on a layout is the API's to decide.
 */
export function LayoutsPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [layouts, setLayouts] = useState<LayoutView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setLayouts(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(reload);
    }
  }, [reload, managed]);

  const done = useCallback(async () => {
    await reload();
    onChanged();
  }, [reload, onChanged]);
  const { busy, notice, problem, act } = useAction(done);

  if (object.managedBy) {
    return (
      <section aria-labelledby="layouts-heading">
        <h3 id="layouts-heading">Page layouts</h3>
        <p className="hint">The records of this object belong to the platform, so it has no page layouts.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="layouts-failed">
        {failure}
      </p>
    );
  }
  if (!layouts) {
    return <p aria-live="polite">Loading…</p>;
  }

  const fields = showable(object);
  const base = `/setup/objects/${encodeURIComponent(objectApiName)}/layouts`;

  function remove(layout: LayoutView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_LAYOUT", objectApiName, itemApiName: layout.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
            params: { path: { objectApiName, layoutApiName: layout.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The page layout was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  return (
    <section aria-labelledby="layouts-heading" data-testid="layouts">
      <div className="list-head">
        <h3 id="layouts-heading">Page layouts</h3>
        <Link className="button" href={`${base}/new`}>
          New page layout
        </Link>
      </div>
      <p className="hint">
        A page layout is the structure of a record form: sections with fields in them. It must show every required field of
        the object.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="layouts" />
      {layouts.length === 0 ? (
        <p>No page layouts yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Page layout</th>
              <th>API name</th>
              <th>Sections</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {layouts.map((layout) => (
              <tr key={layout.apiName} data-testid="layout-row">
                <td>
                  <Link className="table-row-link" href={`${base}/${encodeURIComponent(layout.apiName)}`}>
                    {layout.label}
                  </Link>
                  {layout.description ? <div className="hint">{layout.description}</div> : null}
                </td>
                <td>
                  <code>{layout.apiName}</code>
                </td>
                <td>{layout.definition.sections.length}</td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => setPreviewing(previewing === layout.apiName ? undefined : layout.apiName)}
                    aria-expanded={previewing === layout.apiName}
                  >
                    Preview
                  </button>{" "}
                  <Link className="link-button" href={`${base}/${encodeURIComponent(layout.apiName)}`}>
                    Build
                  </Link>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(layout)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {layouts
        .filter((layout) => layout.apiName === previewing)
        .map((layout) => (
          <LayoutPreview key={layout.apiName} layout={layout.definition} fields={fields} title={layout.label} />
        ))}
    </section>
  );
}
