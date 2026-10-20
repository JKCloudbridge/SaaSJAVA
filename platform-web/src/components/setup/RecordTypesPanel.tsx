"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { Author } from "./authorship";
import { detailParents, listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { problemText } from "./objectText";
import { RecordTypeAccessPanel } from "./RecordTypeAccessPanel";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type RecordTypeView = components["schemas"]["RecordTypeView"];
type CreateRecordTypeRequest = components["schemas"]["CreateRecordTypeRequest"];
type LayoutView = components["schemas"]["LayoutView"];

interface Draft {
  name: string;
  label: string;
  description: string;
  active: boolean;
  defaultType: boolean;
  /** The API name of the page layout the record type uses, or empty for none. */
  layout: string;
  allFields: boolean;
  fields: string[];
  /** The values a picklist is limited to; a picklist that is not here allows all its values. */
  limits: Record<string, string[]>;
}

const EMPTY: Draft = {
  name: "",
  label: "",
  description: "",
  active: true,
  defaultType: false,
  layout: "",
  allFields: true,
  fields: [],
  limits: {},
};

const ROUTE = { openParam: "recordType", keep: ["section"] } as const;

function draftOf(type: RecordTypeView): Draft {
  return {
    name: type.apiName,
    label: type.label,
    description: type.description,
    active: type.active,
    defaultType: type.defaultType,
    layout: type.layout ?? "",
    allFields: type.allFields,
    fields: type.availableFields,
    limits: Object.fromEntries(type.picklistSubsets.map((subset) => [subset.field, subset.values])),
  };
}

function toggle(list: string[], value: string, on: boolean): string[] {
  return on ? [...list.filter((item) => item !== value), value] : list.filter((item) => item !== value);
}

/** The fields a record type can offer: the object's own, standard and custom ones; the system fields are always there. */
function offerable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

function activeValues(field: FieldView): string[] {
  return (field.settings.values ?? []).filter((value) => value.active).map((value) => value.value);
}

/**
 * The Record types tab of an object (UA3, ADR-0170, ADR-0064): record types are variants of the object that offer some of
 * its fields and allow some of its picklist values. A list (record type, layout, status); a row opens the type's page, with
 * the profiles that use it (ADR-0098) as a section; New opens the form. Changes go live (one step in the history) or, when a
 * change set is chosen, into it. The page shows what the API answers; which fields and values are allowed, and what depends
 * on what, is the API's to decide, and it names the dependent when something cannot go.
 */
export function RecordTypesPanel({ object, reload: reloadObject, refreshToken, authorship }: ObjectTabProps) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const [types, setTypes] = useState<RecordTypeView[] | undefined>();
  const [layouts, setLayouts] = useState<LayoutView[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);

  const load = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/record-types", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setTypes(data.data);
      const layoutList = await api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", {
        params: { path: { objectApiName } },
      });
      setLayouts(layoutList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(load);
    }
  }, [load, managed]);

  const done = useCallback(async () => {
    await load();
    await reloadObject();
  }, [load, reloadObject]);
  const screen = useListScreen(done, ROUTE);
  const { busy, notice, problem, act } = screen;

  const messages = <ActionMessages notice={notice} problem={problem} testId="record-types" />;
  const crumbs = listCrumbs(object, "Record types");
  const parents = detailParents(object);
  const fields = offerable(object);
  const picklists = fields.filter((field) => field.settings.values != null);
  const layoutLabel = (apiName?: string) =>
    apiName ? (layouts.find((layout) => layout.apiName === apiName)?.label ?? apiName) : "None";

  function body(): CreateRecordTypeRequest {
    const available = draft.allFields ? undefined : draft.fields;
    const subsets = picklists
      .filter((field) => draft.limits[field.apiName] && (draft.allFields || draft.fields.includes(field.apiName)))
      .map((field) => ({ field: field.apiName, values: draft.limits[field.apiName] ?? [] }));
    return {
      name: draft.name,
      label: draft.label,
      description: draft.description,
      active: draft.active,
      defaultType: draft.defaultType,
      layout: draft.layout || undefined,
      availableFields: available,
      picklistSubsets: subsets,
    };
  }

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const request = body();
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_RECORD_TYPE", objectApiName, createRecordType: request })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-types", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record type was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const save = (type: RecordTypeView) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const update = { ...body(), version: type.version };
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "UPDATE_RECORD_TYPE",
            objectApiName,
            itemApiName: type.apiName,
            updateRecordType: update,
          })
        : await api.PUT("/api/v1/metadata/objects/{objectApiName}/record-types/{recordTypeApiName}", {
            params: { path: { objectApiName, recordTypeApiName: type.apiName } },
            body: update,
          });
      if (result.response.ok) {
        setEditing(false);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record type was saved.",
        problem: problemText(result.error, result.response),
      };
    });
  };

  const remove = (type: RecordTypeView) => {
    setConfirmRemove(false);
    return act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_RECORD_TYPE", objectApiName, itemApiName: type.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-types/{recordTypeApiName}", {
            params: { path: { objectApiName, recordTypeApiName: type.apiName } },
          });
      if (result.response.ok) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record type was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  };

  const form = (
    <RecordTypeFields
      draft={draft}
      onDraft={setDraft}
      fields={fields}
      picklists={picklists}
      layouts={layouts}
      editing={editing}
    />
  );

  if (object.managedBy) {
    return (
      <ListPage<RecordTypeView>
        title={tabTitle(object, "Record types")}
        crumbs={crumbs}
        items={[]}
        rowKey={(row) => row.apiName}
        primaryHeader="Record type"
        primary={(row) => row.label}
        columns={[]}
        emptyText="The records of this object belong to the platform, so it has no record types."
        testId="record-types"
      />
    );
  }

  if (screen.creating && types) {
    return (
      <DetailPage
        listLabel="Record types"
        parents={parents}
        onBack={() => screen.back()}
        title="New record type"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={() => screen.back()}>
              Cancel
            </button>
            <button type="submit" form="record-type-form" className="button" disabled={busy}>
              Add the record type
            </button>
          </>
        }
        testId="record-type-new"
      >
        <form id="record-type-form" onSubmit={create} noValidate aria-label="Add a record type">
          <DetailSection title="Detail" stack>
            {form}
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const type = types?.find((candidate) => candidate.apiName === screen.openId);
  if (screen.openId && types && !type) {
    return (
      <MissingDetail listLabel="Record types" onBack={() => screen.back()} what="This record type does not exist any more." />
    );
  }

  if (type) {
    const actions = editing ? (
      <>
        <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
          Cancel
        </button>
        <button type="submit" form="record-type-form" className="button" disabled={busy}>
          Save the record type
        </button>
      </>
    ) : offersManage ? (
      <>
        <button
          type="button"
          className="button button-danger"
          disabled={busy}
          onClick={() => setConfirmRemove((value) => !value)}
        >
          Remove
        </button>
        <button
          type="button"
          className="button"
          disabled={busy}
          onClick={() => {
            setDraft(draftOf(type));
            setEditing(true);
          }}
        >
          Edit
        </button>
      </>
    ) : null;
    const offered = fields.filter((field) => type.allFields || type.availableFields.includes(field.apiName));
    return (
      <DetailPage
        listLabel="Record types"
        parents={parents}
        onBack={() => {
          setEditing(false);
          setConfirmRemove(false);
          screen.back();
        }}
        title={type.label}
        actions={actions}
        notices={messages}
        testId="record-type-detail"
      >
        {confirmRemove ? (
          <p role="alertdialog" aria-label="Remove the record type" data-testid="record-type-remove-confirm">
            Remove this record type?{" "}
            <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(type)}>
              Yes, remove the record type
            </button>{" "}
            <button type="button" className="link-button" onClick={() => setConfirmRemove(false)}>
              Cancel
            </button>
          </p>
        ) : null}
        {editing ? (
          <form id="record-type-form" onSubmit={save(type)} noValidate aria-label="Change the record type">
            <DetailSection title="Edit record type" stack>
              {form}
            </DetailSection>
          </form>
        ) : (
          <>
            <DetailSection title="Detail">
              <DetailGrid>
                <DetailItem label="Label">{type.label}</DetailItem>
                <DetailItem label="API name" tone="mono">
                  {type.apiName}
                </DetailItem>
                <DetailItem label="Description" wide tone={type.description ? undefined : "muted"}>
                  {type.description || "—"}
                </DetailItem>
                <DetailItem label="Status">
                  <Tag tone={type.active ? "ok" : "quiet"}>{type.active ? "Active" : "Not active"}</Tag>{" "}
                  {type.defaultType ? <Tag tone="on">The default</Tag> : null}
                </DetailItem>
                <DetailItem label="Page layout">{layoutLabel(type.layout)}</DetailItem>
                <DetailItem label="Created by">
                  <Author authorship={authorship} kind="RECORD_TYPE" apiName={type.apiName} which="created" />
                </DetailItem>
                <DetailItem label="Modified by">
                  <Author authorship={authorship} kind="RECORD_TYPE" apiName={type.apiName} which="updated" />
                </DetailItem>
              </DetailGrid>
            </DetailSection>
            <DetailSection title="Fields offered">
              {type.allFields ? (
                <p>Every field.</p>
              ) : (
                <ul data-testid="record-type-fields">
                  {offered.map((field) => (
                    <li key={field.apiName}>
                      {field.label} <code>{field.apiName}</code>
                    </li>
                  ))}
                </ul>
              )}
              {type.picklistSubsets.length > 0 ? (
                <ul data-testid="record-type-limits">
                  {type.picklistSubsets.map((subset) => (
                    <li key={subset.field}>
                      {fields.find((field) => field.apiName === subset.field)?.label ?? subset.field} is limited to{" "}
                      {subset.values.join(", ") || "no values"}
                    </li>
                  ))}
                </ul>
              ) : null}
            </DetailSection>
            <DetailSection title="Profiles that use this record type">
              <RecordTypeAccessPanel object={object} recordType={type} refreshToken={refreshToken} />
            </DetailSection>
          </>
        )}
      </DetailPage>
    );
  }

  return (
    <ListPage<RecordTypeView>
      title={tabTitle(object, "Record types")}
      crumbs={crumbs}
      intro="A record type is a variant of this object (for example New business and Renewal): it offers some of the fields and allows some of the values of a picklist."
      items={types}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Record type"
      primary={(row) => row.label}
      primaryExtra={(row) => (row.description ? <span className="hint"> {row.description}</span> : null)}
      columns={[
        { key: "layout", header: "Layout", kind: "muted", cell: (row) => layoutLabel(row.layout) },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <>
              {row.defaultType ? <Tag tone="on">Default</Tag> : null}{" "}
              <Tag tone={row.active ? "ok" : "quiet"}>{row.active ? "Active" : "Not active"}</Tag>
            </>
          ),
        },
      ]}
      onOpen={(row) => {
        setEditing(false);
        setConfirmRemove(false);
        screen.open(row.apiName);
      }}
      searchText={(row) => `${row.label} ${row.apiName} ${layoutLabel(row.layout)}`}
      searchLabel="Search record types"
      onNew={offersManage ? () => {
        setDraft(EMPTY);
        setEditing(false);
        screen.create();
      } : undefined}
      notices={messages}
      emptyText="No record types yet."
      testId="record-types"
      rowTestId="record-type-row"
    />
  );
}

/** The fields of a record type as a form; the name is asked only for a new one. */
function RecordTypeFields({
  draft,
  onDraft,
  fields,
  picklists,
  layouts,
  editing,
}: {
  draft: Draft;
  onDraft: (next: Draft) => void;
  fields: FieldView[];
  picklists: FieldView[];
  layouts: LayoutView[];
  editing: boolean;
}) {
  return (
    <>
      {editing ? (
        <p>
          <code>{draft.name}</code>. The name of a record type never changes.
        </p>
      ) : (
        <div className="field">
          <label htmlFor="record-type-name">Name</label>
          <input
            id="record-type-name"
            value={draft.name}
            maxLength={40}
            onChange={(event) => onDraft({ ...draft, name: event.target.value })}
            required
            aria-describedby="record-type-name-hint"
          />
          <p className="hint" id="record-type-name-hint">
            The permanent API name, starting with a capital letter, for example Renewal. The platform adds the ending __c.
          </p>
        </div>
      )}
      <div className="field">
        <label htmlFor="record-type-label">Label</label>
        <input
          id="record-type-label"
          value={draft.label}
          maxLength={80}
          onChange={(event) => onDraft({ ...draft, label: event.target.value })}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="record-type-description">Description</label>
        <input
          id="record-type-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => onDraft({ ...draft, description: event.target.value })}
        />
      </div>
      <label>
        <input type="checkbox" checked={draft.active} onChange={(event) => onDraft({ ...draft, active: event.target.checked })} />{" "}
        Active
      </label>
      <label>
        <input
          type="checkbox"
          checked={draft.defaultType}
          onChange={(event) => onDraft({ ...draft, defaultType: event.target.checked })}
        />{" "}
        The default record type of this object
      </label>
      <div className="field">
        <label htmlFor="record-type-layout">Page layout of this record type</label>
        <select
          id="record-type-layout"
          value={draft.layout}
          onChange={(event) => onDraft({ ...draft, layout: event.target.value })}
        >
          <option value="">None (the layout of the profile or the default one applies)</option>
          {layouts.map((layout) => (
            <option key={layout.apiName} value={layout.apiName}>
              {layout.label}
            </option>
          ))}
        </select>
      </div>
      <label>
        <input
          type="checkbox"
          checked={draft.allFields}
          onChange={(event) => onDraft({ ...draft, allFields: event.target.checked })}
        />{" "}
        Offer every field
      </label>
      {draft.allFields ? null : (
        <fieldset className="field">
          <legend>Fields this record type offers</legend>
          {fields.map((field) => (
            <label key={field.apiName}>
              <input
                type="checkbox"
                checked={draft.fields.includes(field.apiName)}
                onChange={(event) =>
                  onDraft({ ...draft, fields: toggle(draft.fields, field.apiName, event.target.checked) })
                }
              />{" "}
              {field.label} <code>{field.apiName}</code>
              {field.required ? <span className="hint"> (required: it must be offered)</span> : null}
            </label>
          ))}
        </fieldset>
      )}
      {picklists
        .filter((field) => draft.allFields || draft.fields.includes(field.apiName))
        .map((field) => {
          const limited = draft.limits[field.apiName];
          return (
            <fieldset className="field" key={field.apiName}>
              <legend>
                {field.label} <code>{field.apiName}</code>
              </legend>
              <label>
                <input
                  type="checkbox"
                  checked={limited !== undefined}
                  onChange={(event) => {
                    const limits = { ...draft.limits };
                    if (event.target.checked) {
                      limits[field.apiName] = activeValues(field);
                    } else {
                      delete limits[field.apiName];
                    }
                    onDraft({ ...draft, limits });
                  }}
                />{" "}
                Limit the values of {field.label}
              </label>
              {limited === undefined
                ? null
                : activeValues(field).map((value) => (
                    <label key={value}>
                      <input
                        type="checkbox"
                        checked={limited.includes(value)}
                        onChange={(event) =>
                          onDraft({
                            ...draft,
                            limits: { ...draft.limits, [field.apiName]: toggle(limited, value, event.target.checked) },
                          })
                        }
                      />{" "}
                      {value}
                    </label>
                  ))}
            </fieldset>
          );
        })}
    </>
  );
}
