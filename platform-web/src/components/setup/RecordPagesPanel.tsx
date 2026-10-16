"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { allComponents, componentDraftOf } from "@/lib/builder/recordPageDraft";
import { problemText } from "./objectText";
import { RecordPagePreview } from "./RecordPagePreview";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];
type RecordPageView = components["schemas"]["RecordPageView"];

/**
 * The record pages of one object: the list, a preview of each, and the way into the builder where a page is made and
 * changed (its tabs are arranged by drag and drop). Removing is done here, live (one step in the history) or, when a
 * change set is chosen, as a draft. The page shows what the API answers: that the layout and the fields exist, and that
 * a related list follows a relationship, is the API's to decide.
 */
export function RecordPagesPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [pages, setPages] = useState<RecordPageView[] | undefined>();
  const [layouts, setLayouts] = useState<LayoutView[]>([]);
  const [others, setOthers] = useState<Record<string, FieldView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const ensure = useCallback(async (name: string) => {
    if (!name) {
      return;
    }
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName: name } } });
      if (data) {
        setOthers((known) => ({ ...known, [name]: data.data.fields }));
      }
    } catch {
      // The related list then shows no columns; opening the preview again tries once more.
    }
  }, []);

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [list, layoutList] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", { params: { path } }),
      ]);
      if (!list.data || !layoutList.data) {
        const first = list.data ? layoutList : list;
        setFailure(await failureText(first.error, first.response));
        return;
      }
      setFailure(undefined);
      setPages(list.data.data);
      setLayouts(layoutList.data.data);
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
      <section aria-labelledby="record-pages-heading">
        <h3 id="record-pages-heading">Record pages</h3>
        <p className="hint">The records of this object belong to the platform, so it has no record pages.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="record-pages-failed">
        {failure}
      </p>
    );
  }
  if (!pages) {
    return <p aria-live="polite">Loading…</p>;
  }

  const base = `/setup/objects/${encodeURIComponent(objectApiName)}/record-pages`;
  const fieldsOf = (name: string) => (name === objectApiName ? object.fields : (others[name] ?? []));

  function remove(page: RecordPageView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_RECORD_PAGE", objectApiName, itemApiName: page.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
            params: { path: { objectApiName, pageApiName: page.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record page was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  return (
    <section aria-labelledby="record-pages-heading" data-testid="record-pages">
      <div className="list-head">
        <h3 id="record-pages-heading">Record pages</h3>
        <Link className="button" href={`${base}/new`}>
          New record page
        </Link>
      </div>
      <p className="hint">
        A record page puts the details of a layout together with a header, related lists, tabs and actions. When a part is
        shown can depend on a field or on an ability of the person.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-pages" />
      {pages.length === 0 ? (
        <p>No record pages yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Record page</th>
              <th>API name</th>
              <th>Layout</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {pages.map((page) => (
              <tr key={page.apiName} data-testid="record-page-row">
                <td>
                  <Link className="table-row-link" href={`${base}/${encodeURIComponent(page.apiName)}`}>
                    {page.label}
                  </Link>
                  {page.description ? <div className="hint">{page.description}</div> : null}
                </td>
                <td>
                  <code>{page.apiName}</code>
                </td>
                <td>
                  <code>{page.definition.layout}</code>
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    aria-expanded={previewing === page.apiName}
                    onClick={() => {
                      const open = previewing === page.apiName;
                      setPreviewing(open ? undefined : page.apiName);
                      if (!open) {
                        allComponents(page.definition.components.map((component, at) => componentDraftOf(component, at)))
                          .filter((component) => component.object)
                          .forEach((component) => void ensure(component.object));
                      }
                    }}
                  >
                    Preview
                  </button>{" "}
                  <Link className="link-button" href={`${base}/${encodeURIComponent(page.apiName)}`}>
                    Build
                  </Link>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(page)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {pages
        .filter((page) => page.apiName === previewing)
        .map((page) => (
          <RecordPagePreview
            key={page.apiName}
            page={page.definition}
            title={page.label}
            layouts={layouts}
            fields={object.fields}
            fieldsOf={fieldsOf}
          />
        ))}
    </section>
  );
}
