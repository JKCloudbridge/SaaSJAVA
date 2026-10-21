"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ActionMessages, useAction } from "@/components/console/useAction";
import { Breadcrumb } from "@/components/ui/lists/Breadcrumb";
import { DetailGrid, DetailItem, DetailSection } from "@/components/ui/lists/DetailPage";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import { useOffers } from "@/lib/session/SessionProvider";
import { OBJECT_SETTINGS_HREF } from "@/lib/shell/areas";
import { Author } from "./authorship";
import { listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { KIND_TEXT, problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

/**
 * The Details tab of an object (UA3, ADR-0170): the labels, who made it and, for the organization's own object, the
 * form that changes the labels and the removal. Whether it may be changed is the flag `editable` the API sends; the API
 * checks the change again and answers in its own words.
 */
export function ObjectDetailsPanel({ object, reload, authorship }: ObjectTabProps) {
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const [editing, setEditing] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [labels, setLabels] = useState({
    label: object.label,
    pluralLabel: object.pluralLabel,
    description: object.description,
  });
  const { busy, notice, problem, act } = useAction(reload);
  const objectApiName = object.apiName;
  const canChange = object.editable && offersManage;

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = { ...labels, version: object.version };
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, { kind: "UPDATE_OBJECT", objectApiName, updateObject: body })
        : await api.PUT("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } }, body });
      if (response.ok) {
        setEditing(false);
      }
      return {
        error,
        response,
        text: workingSet ? DRAFTED_TEXT : "The object was saved.",
        problem: problemText(error, response),
      };
    });
  }

  function remove() {
    setConfirmRemove(false);
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_OBJECT", objectApiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } } });
      if (response.ok && !workingSet) {
        router.push(OBJECT_SETTINGS_HREF);
      }
      return {
        error,
        response,
        text: workingSet ? DRAFTED_TEXT : "The object was removed.",
        problem: problemText(error, response),
      };
    });
  }

  const actions = editing ? (
    <>
      <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
        Cancel
      </button>
      <button type="submit" form="object-labels-form" className="button" disabled={busy}>
        Save the labels
      </button>
    </>
  ) : canChange ? (
    <>
      <button
        type="button"
        className="button button-danger"
        disabled={busy}
        onClick={() => setConfirmRemove((value) => !value)}
      >
        Remove the object
      </button>
      <button
        type="button"
        className="button"
        disabled={busy}
        onClick={() => {
          setLabels({ label: object.label, pluralLabel: object.pluralLabel, description: object.description });
          setEditing(true);
        }}
      >
        Edit
      </button>
    </>
  ) : null;

  return (
    <PageFrame
      title={tabTitle(object, "Details")}
      breadcrumb={<Breadcrumb crumbs={listCrumbs(object, "Details")} />}
      tools={actions}
      notices={<ActionMessages notice={notice} problem={problem} testId="object" />}
      testId="object-details"
    >
      {confirmRemove ? (
        <p role="alertdialog" aria-label="Remove the object" data-testid="object-remove-confirm">
          Remove this object and all its fields? The permissions on them end too.{" "}
          <button type="button" className="button button-danger" disabled={busy} onClick={remove}>
            Yes, remove the object
          </button>{" "}
          <button type="button" className="link-button" onClick={() => setConfirmRemove(false)}>
            Cancel
          </button>
        </p>
      ) : null}
      <DetailSection title="Detail">
        {editing ? (
          <form id="object-labels-form" onSubmit={save} noValidate>
            <DetailGrid>
              <div className="field">
                <label htmlFor="edit-label">Label</label>
                <input
                  id="edit-label"
                  value={labels.label}
                  maxLength={80}
                  onChange={(event) => setLabels({ ...labels, label: event.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="edit-plural">Plural label</label>
                <input
                  id="edit-plural"
                  value={labels.pluralLabel}
                  maxLength={80}
                  onChange={(event) => setLabels({ ...labels, pluralLabel: event.target.value })}
                />
              </div>
              <div className="field span-2">
                <label htmlFor="edit-description">Description</label>
                <input
                  id="edit-description"
                  value={labels.description}
                  maxLength={500}
                  onChange={(event) => setLabels({ ...labels, description: event.target.value })}
                />
              </div>
            </DetailGrid>
          </form>
        ) : (
          <DetailGrid>
            <DetailItem label="Label">{object.label}</DetailItem>
            <DetailItem label="Plural label">{object.pluralLabel}</DetailItem>
            <DetailItem label="API name" tone="mono">
              {object.apiName}
            </DetailItem>
            <DetailItem label="Type">
              <Tag tone={object.kind === "CUSTOM" ? "on" : "quiet"}>{KIND_TEXT[object.kind] ?? object.kind}</Tag>
            </DetailItem>
            <DetailItem label="Description" wide tone={object.description ? undefined : "muted"}>
              {object.description || "—"}
            </DetailItem>
            {object.managedBy ? <DetailItem label="Records managed by">{object.managedBy}</DetailItem> : null}
            <DetailItem label="Created by">
              <Author
                authorship={authorship}
                kind="OBJECT"
                apiName={object.apiName}
                which="created"
                platformDefined={object.kind !== "CUSTOM"}
              />
            </DetailItem>
            <DetailItem label="Modified by">
              <Author
                authorship={authorship}
                kind="OBJECT"
                apiName={object.apiName}
                which="updated"
                platformDefined={object.kind !== "CUSTOM"}
              />
            </DetailItem>
          </DetailGrid>
        )}
      </DetailSection>
    </PageFrame>
  );
}
