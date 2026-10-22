"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { cloneHref } from "@/lib/records/paths";
import { useObjectForm, useRecordPage } from "@/lib/records/hooks";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import type { RecordPageBlock, RecordPageDescriptor, RecordView } from "@/lib/records/types";
import { CustomPageFrame } from "@/components/apps/CustomPageFrame";
import { FieldReadValue } from "./FieldRegistry";
import { RecordDetails } from "./RecordDetails";
import { RecordForm } from "./RecordForm";
import { RecordPageRelatedList } from "./RecordPageRelatedList";
import { ApprovalPanel, type ApprovalState } from "./ApprovalPanel";
import { RecordFiles } from "./RecordFiles";
import { SharePanel } from "./SharePanel";
import { headingOf, useRecord } from "./RecordFormPage";

/** The id of the one form on the page: the header's Save submits it from outside the form. */
const FORM_ID = "record-page-form";

/** The blocks of a page in the order they are drawn: before the tabs, in each tab, after the tabs, the side region. */
function allBlocks(page: RecordPageDescriptor): RecordPageBlock[] {
  return [...page.above, ...page.tabs.flatMap((tab) => tab.blocks), ...page.below, ...page.sidebar];
}

/** A block that is not the details: a related list or a tab of the organization. */
function OtherBlock({ block, recordId, recordHref }: {
  block: RecordPageBlock;
  recordId: string;
  recordHref: (objectApiName: string, id: string) => string;
}) {
  switch (block.kind) {
    case "RELATED_LIST":
      return (
        <section aria-label={block.label}>
          <h2>{block.label}</h2>
          <RecordPageRelatedList block={block} recordId={recordId} recordHref={recordHref} />
        </section>
      );
    case "TAB":
      return block.customPageApiName ? (
        <CustomPageFrame customPage={block.customPageApiName} label={block.label} assetId={block.assetId} />
      ) : null;
    default:
      return null;
  }
}

/**
 * The record page of one object (Sprint 20, ADR-0131): a header card with its actions, the page's blocks and tabs,
 * and the record's files, built entirely from what the API resolved for this member (`GET .../record-page`) and what
 * it gave for the object's form (`GET .../form`, Sprint 19, asked with the layout the page names). Nothing here
 * decides a layout, a permission or a visibility rule a second time.
 *
 * <p>A record is read first and changed on purpose: the details are text until the person chooses Edit, which turns
 * the same layout into the form of Sprint 19 (its own preview, conflict recovery and refusals); Save and Cancel then
 * sit in the header beside where Edit was. The other actions are the ones the server says this member may use: Delete
 * (confirmed), Clone (a new record that starts from this one), Share (the record's own sharing, Sprint 17).
 *
 * @param newHref where the create form of the object is, for Clone; without it Clone is not offered
 */
export function RecordPageView({ objectApiName, recordId, listHref, recordHref, newHref }: {
  objectApiName: string;
  recordId: string;
  listHref: string;
  recordHref: (objectApiName: string, id: string) => string;
  newHref?: string;
}) {
  const router = useRouter();
  const [recordType, setRecordType] = useState<string | undefined>();
  const [activeTab, setActiveTab] = useState(0);
  const [opened, setOpened] = useState<ReadonlySet<number>>(new Set([0]));
  const [sharing, setSharing] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  const [overriddenRecord, setOverriddenRecord] = useState<RecordView | undefined>();
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [refresh, setRefresh] = useState(0);
  const { state: pageState, reload: reloadPage } = useRecordPage(objectApiName, recordType, recordId);
  const page = pageState.status === "ready" ? pageState.page : pageState.status === "loading" ? pageState.previous : undefined;
  // The form is asked once the page has named its layout; while another page is on its way the last good one stays.
  const { state } = useObjectForm(objectApiName, recordType, recordId, page?.layout ?? undefined, page !== undefined);
  const recordState = useRecord(objectApiName, recordId, refresh);
  const form = state.status === "ready" ? state.form : state.status === "loading" ? state.previous : undefined;

  if (state.status === "failed" || pageState.status === "failed" || recordState.status === "failed") {
    return (
      <div className="empty-state" role="status" data-testid="record-unavailable">
        <p>
          <strong>This page is not available.</strong>
        </p>
        <p>It may have been removed, or it may not be part of your access.</p>
        <Link href={listHref}>Back to the list</Link>
      </div>
    );
  }
  if (!form || !page || recordState.status !== "ready") {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite">
        <span className="skeleton-line" />
        <span className="skeleton-line skeleton-short" />
        <span className="visually-hidden">Loading</span>
      </div>
    );
  }
  const record = overriddenRecord ?? recordState.record;
  const ownerField = form.fields.find((field) => field.apiName === "ownerId");
  const offered = (action: string) => page.actions.find((candidate) => candidate.action === action);
  const mayEdit = offered("EDIT") !== undefined && form.actions.update;
  const mayDelete = offered("DELETE") !== undefined && form.actions.delete;
  const mayClone = offered("CLONE") !== undefined && form.actions.create && newHref !== undefined;
  const mayShare = offered("SHARE") !== undefined && form.actions.update && ownerField !== undefined;
  // A record that is being approved cannot be changed or removed (ADR-0148); the lock comes from the approval panel, which
  // reads it from the API. Someone who may override it is not held back here: the API decides again on every call.
  const lockedForMe = approval.locked && !approval.mayOverride;
  const lockedWords = "This record is locked while it is being approved.";

  // A page whose every block was left out for this member still shows the record.
  const blocks = allBlocks(page);
  const details: RecordPageBlock = blocks.find((block) => block.kind === "DETAILS") ?? {
    kind: "DETAILS",
    label: "Details",
    columns: [],
  };
  const above = blocks.length === 0 ? [details] : page.above;
  const tabs = page.tabs;
  const current = Math.min(activeTab, Math.max(tabs.length - 1, 0));
  const tabOfDetails = tabs.findIndex((tab) => tab.blocks.includes(details));

  function startEditing() {
    setFailure(undefined);
    setSharing(false);
    if (tabOfDetails >= 0) {
      setActiveTab(tabOfDetails);
      setOpened((previous) => new Set(previous).add(tabOfDetails));
    }
    setEditing(true);
  }

  function stopEditing() {
    setEditing(false);
    // The record's own type decides again: a type picked while editing is only a proposal until it is saved.
    setRecordType(undefined);
  }

  async function remove() {
    setBusy(true);
    const outcome = await call(() =>
      api.DELETE("/api/v1/objects/{objectApiName}/records/{id}", { params: { path: { objectApiName, id: record.id } } }),
    );
    setBusy(false);
    if (outcome.ok) {
      router.push(listHref);
    } else {
      setConfirmingDelete(false);
      setFailure(outcome.failure.message);
    }
  }

  function draw(block: RecordPageBlock, key: string) {
    if (block.kind !== "DETAILS") {
      return <OtherBlock key={key} block={block} recordId={recordId} recordHref={recordHref} />;
    }
    return block === details && editing ? (
      <RecordForm
        key={key}
        formId={FORM_ID}
        hideActions
        objectApiName={objectApiName}
        form={form as NonNullable<typeof form>}
        record={record}
        recordHref={recordHref}
        onRecordTypeChange={setRecordType}
        onSaved={(saved) => {
          setOverriddenRecord(saved);
          setEditing(false);
          setRecordType(undefined);
          reloadPage();
        }}
        onCancel={stopEditing}
      />
    ) : (
      <RecordDetails key={key} objectApiName={objectApiName} form={form as NonNullable<typeof form>} record={record} recordHref={recordHref} />
    );
  }

  return (
    <div className="stack">
      <p>
        <Link href={listHref}>← {form.pluralLabel}</Link>
      </p>
      <div className="card">
        <div className="list-head">
          <h1 className="entry-title">{headingOf(form, record)}</h1>
          <div className="filters-actions" role="group" aria-label="Record actions" data-testid="record-actions">
            {editing ? (
              <>
                <button type="submit" form={FORM_ID} className="button" data-testid="page-save">
                  Save
                </button>
                <button type="button" className="button button-secondary" onClick={stopEditing} data-testid="page-cancel">
                  Cancel
                </button>
              </>
            ) : (
              <>
                {mayEdit ? (
                  <button
                    type="button"
                    className="button"
                    onClick={startEditing}
                    disabled={lockedForMe}
                    title={lockedForMe ? lockedWords : undefined}
                    data-testid="page-edit"
                  >
                    {offered("EDIT")?.label ?? "Edit"}
                  </button>
                ) : null}
                {mayClone && newHref ? (
                  <Link className="button button-secondary" href={cloneHref(newHref, record.id)} data-testid="page-clone">
                    {offered("CLONE")?.label ?? "Clone"}
                  </Link>
                ) : null}
                {mayShare ? (
                  <button type="button" className="button button-secondary" onClick={() => setSharing(true)} data-testid="page-share">
                    {offered("SHARE")?.label ?? "Share"}
                  </button>
                ) : null}
                {mayDelete ? (
                  confirmingDelete ? (
                    <span className="inline-confirm" role="group" aria-label="Confirm delete">
                      Move this record to the recycle bin?
                      <button type="button" className="button button-danger" onClick={() => void remove()} disabled={busy}>
                        Yes, delete
                      </button>
                      <button type="button" className="link-button" onClick={() => setConfirmingDelete(false)}>
                        Keep it
                      </button>
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="button button-danger"
                      onClick={() => setConfirmingDelete(true)}
                      disabled={lockedForMe}
                      title={lockedForMe ? lockedWords : undefined}
                      data-testid="page-delete"
                    >
                      {offered("DELETE")?.label ?? "Delete"}
                    </button>
                  )
                ) : null}
              </>
            )}
          </div>
        </div>
        {failure ? (
          <p className="form-message" role="alert" data-testid="page-failure">
            {failure}
          </p>
        ) : null}
        {page.header.highlightFields.length > 0 ? (
          <div className="record-grid record-grid-two">
            {page.header.highlightFields.map((name) => {
              const field = form.fields.find((candidate) => candidate.apiName === name);
              if (!field) {
                return null;
              }
              const value = recordValue(record, name);
              const text = formatValue(field, value);
              return (
                <div key={name} className="detail-item">
                  <div className="dl-label">{field.label}</div>
                  <div className={`dl-value${text === "" ? " muted" : ""}`}>
                    {text === "" ? "—" : <FieldReadValue field={field} value={value} recordHref={recordHref} />}
                  </div>
                </div>
              );
            })}
          </div>
        ) : null}
      </div>

      <ApprovalPanel
        objectApiName={objectApiName}
        recordId={recordId}
        recordVersion={record.version}
        refresh={refresh}
        onState={setApproval}
        onChanged={() => {
          setOverriddenRecord(undefined);
          setRefresh((now) => now + 1);
          reloadPage();
        }}
      />

      {sharing && ownerField ? (
        <SharePanel
          objectApiName={objectApiName}
          record={record}
          ownerField={ownerField}
          onClose={() => setSharing(false)}
          onOwnerChanged={(saved) => {
            setOverriddenRecord(saved);
            setSharing(false);
          }}
        />
      ) : null}

      {above.map((block, index) => draw(block, `above-${index}`))}

      {tabs.length > 1 ? (
        <div className="control-tabs" role="tablist">
          {tabs.map((tab, index) => (
            <button
              key={`${tab.label}-${index}`}
              type="button"
              role="tab"
              aria-selected={index === current}
              onClick={() => {
                setActiveTab(index);
                setOpened((previous) => new Set(previous).add(index));
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>
      ) : null}

      {/* A tab that was opened stays drawn (hidden), so a form half filled in is not lost by looking at another tab. */}
      {tabs.map((tab, index) =>
        opened.has(index) || index === current ? (
          <div key={`${tab.label}-${index}`} role="tabpanel" aria-label={tab.label} hidden={index !== current}>
            {tab.blocks.map((block, at) => draw(block, `tab-${index}-${at}`))}
          </div>
        ) : null,
      )}

      {page.below.map((block, index) => draw(block, `below-${index}`))}

      {page.sidebar.length > 0 ? (
        <aside className="stack" aria-label="Side">
          {page.sidebar.map((block, index) => draw(block, `side-${index}`))}
        </aside>
      ) : null}

      <section>
        <h2>Files</h2>
        <RecordFiles objectApiName={objectApiName} recordId={recordId} mayChange={form.actions.update} />
      </section>
    </div>
  );
}
