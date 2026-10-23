"use client";

import { useState } from "react";
import { ActionMessages } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import { useOffers } from "@/lib/session/SessionProvider";
import { Author } from "./authorship";
import { FieldForm } from "./FieldForm";
import { detailParents, listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { KIND_TEXT, ON_DELETE_TEXT, problemText, type FieldView } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

const ROUTE = { openParam: "field", keep: ["section"] } as const;

/** The settings of a field's type as label and value pairs, from what the API sent. */
function settingRows(field: FieldView): Array<{ label: string; value: string }> {
  const s = field.settings;
  const rows: Array<{ label: string; value: string }> = [];
  const add = (label: string, value: string | number | undefined | null) => {
    if (value !== undefined && value !== null && value !== "") {
      rows.push({ label, value: String(value) });
    }
  };
  add("Maximum length", s.maxLength);
  add("Digits", s.digits);
  if (s.precision != null) {
    add("Digits in all", s.precision);
    add("Digits after the point", s.scale ?? 0);
  }
  add("Points to", s.targetObject);
  if (s.onDelete) {
    add("When the other record is removed", ON_DELETE_TEXT[s.onDelete] ?? s.onDelete);
  }
  if (s.reparentable) {
    add("Moving", "A record may be moved to another master");
  }
  add("Label of the list on the other object", s.listLabel);
  add("Result of the formula", s.expression ? (s.resultType ?? "Text") : undefined);
  if (s.prefix != null && s.startAt != null) {
    add("Prefix", s.prefix || "none");
    add("First number", s.startAt);
    add("Number of digits", s.width ?? 1);
  }
  add("Default value", field.defaultValue);
  return rows;
}

/**
 * The Fields tab of an object (UA3, ADR-0170): the fields as a list (label, API name, type); a row opens the field's page
 * with its details, the settings of its type and who made it; New opens the form that adds a field. What may be changed is
 * the flags `editable` and `extensible` the API sends, and the API checks every change again and answers in its own words.
 */
export function FieldsPanel({ object, types, objects, reload, authorship }: ObjectTabProps) {
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const screen = useListScreen(reload, ROUTE);
  const { busy, notice, problem, act } = screen;
  const [editing, setEditing] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);

  const objectApiName = object.apiName;
  const typeLabel = (type: string) => types.find((candidate) => candidate.type === type)?.label ?? type;
  const messages = <ActionMessages notice={notice} problem={problem} testId="object" />;
  const crumbs = listCrumbs(object, "Fields");
  const parents = detailParents(object);

  const check = { objectApiName, fields: object.fields };

  if (screen.creating && object.extensible) {
    return (
      <DetailPage
        listLabel="Fields"
        parents={parents}
        onBack={() => screen.back()}
        title="New field"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={() => screen.back()}>
              Cancel
            </button>
            <button type="submit" form="field-form" className="button" disabled={busy}>
              Add the field
            </button>
          </>
        }
        testId="field-new"
      >
        <DetailSection title="Detail">
          <FieldForm
            formId="field-form"
            hideActions
            types={types}
            objects={objects}
            siblings={object.fields}
            busy={busy}
            check={check}
            onCancel={() => screen.back()}
            onCreate={(body) =>
              void act(async () => {
                const { error, response } = workingSet
                  ? await addToSet(workingSet, { kind: "CREATE_FIELD", objectApiName, createField: body })
                  : await api.POST("/api/v1/metadata/objects/{objectApiName}/fields", {
                      params: { path: { objectApiName } },
                      body,
                    });
                if (response.ok) {
                  screen.back(true);
                }
                return {
                  error,
                  response,
                  text: workingSet ? DRAFTED_TEXT : "The field was added.",
                  problem: problemText(error, response),
                };
              })
            }
            onUpdate={() => undefined}
          />
        </DetailSection>
      </DetailPage>
    );
  }

  const field = object.fields.find((candidate) => candidate.apiName === screen.openId);
  if (screen.openId && !field) {
    return <MissingDetail listLabel="Fields" onBack={() => screen.back()} what="This field does not exist any more." />;
  }

  if (field) {
    const platformDefined = field.kind !== "CUSTOM";
    const flags = [
      field.required ? "Required" : "",
      field.unique ? "Unique" : "",
      field.indexed ? "Indexed" : "",
      field.searchable ? "Searchable" : "",
      field.externalId ? "External identifier" : "",
      field.organizationValues ? "Values are yours" : "",
      field.platformWritten ? "Written by the platform" : "",
    ].filter(Boolean);
    const rows = settingRows(field);

    const remove = () =>
      act(async () => {
        const { error, response } = workingSet
          ? await addToSet(workingSet, { kind: "DELETE_FIELD", objectApiName, itemApiName: field.apiName })
          : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}", {
              params: { path: { objectApiName, fieldApiName: field.apiName } },
            });
        if (response.ok) {
          screen.back(true);
        }
        setConfirmRemove(false);
        return {
          error,
          response,
          text: workingSet ? DRAFTED_TEXT : "The field was removed.",
          problem: problemText(error, response),
        };
      });

    const actions = editing ? (
      <>
        <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
          Cancel
        </button>
        <button type="submit" form="field-form" className="button" disabled={busy}>
          Save the field
        </button>
      </>
    ) : field.editable && offersManage ? (
      <>
        <button
          type="button"
          className="button button-danger"
          disabled={busy}
          onClick={() => setConfirmRemove((value) => !value)}
        >
          Remove
        </button>
        <button type="button" className="button" disabled={busy} onClick={() => setEditing(true)}>
          Edit
        </button>
      </>
    ) : field.organizationValues && offersManage ? (
      // The platform owns the field; the organization owns its list of values and its pairs (FA2A, ADR-0182).
      <button type="button" className="button" disabled={busy} onClick={() => setEditing(true)}>
        Edit the values
      </button>
    ) : null;

    return (
      <DetailPage
        listLabel="Fields"
        parents={parents}
        onBack={() => {
          setEditing(false);
          setConfirmRemove(false);
          screen.back();
        }}
        title={field.label}
        actions={actions}
        notices={messages}
        testId="field-detail"
      >
        {confirmRemove ? (
          <p role="alertdialog" aria-label="Remove the field" data-testid="field-remove-confirm">
            Remove this field? Records that hold a value in it keep the removal from happening.{" "}
            <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove()}>
              Yes, remove the field
            </button>{" "}
            <button type="button" className="link-button" onClick={() => setConfirmRemove(false)}>
              Cancel
            </button>
          </p>
        ) : null}
        {editing ? (
          <DetailSection title="Edit field">
            <FieldForm
              key={field.apiName}
              formId="field-form"
              hideActions
              types={types}
              objects={objects}
              editing={field}
              siblings={object.fields}
              ownsList={!field.editable}
              busy={busy}
              check={check}
              onCancel={() => setEditing(false)}
              onCreate={() => undefined}
              onUpdate={(body) =>
                void act(async () => {
                  const fieldApiName = field.apiName;
                  const { error, response } = workingSet
                    ? await addToSet(workingSet, {
                        kind: "UPDATE_FIELD",
                        objectApiName,
                        itemApiName: fieldApiName,
                        updateField: body,
                      })
                    : await api.PUT("/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}", {
                        params: { path: { objectApiName, fieldApiName } },
                        body,
                      });
                  if (response.ok) {
                    setEditing(false);
                  }
                  return {
                    error,
                    response,
                    text: workingSet ? DRAFTED_TEXT : "The field was saved.",
                    problem: problemText(error, response),
                  };
                })
              }
            />
          </DetailSection>
        ) : (
          <>
            <DetailSection title="Detail">
              <DetailGrid>
                <DetailItem label="Label">
                  {field.label}
                  {field.retired ? <span className="hint"> (retired)</span> : null}
                </DetailItem>
                <DetailItem label="API name" tone="mono">
                  {field.apiName}
                </DetailItem>
                <DetailItem label="Description" wide tone={field.description ? undefined : "muted"}>
                  {field.description || "—"}
                </DetailItem>
                <DetailItem label="Object name">{object.label}</DetailItem>
                <DetailItem label="Type">{typeLabel(field.type)}</DetailItem>
                <DetailItem label="Kind">{KIND_TEXT[field.kind] ?? field.kind}</DetailItem>
                <DetailItem label="Properties">
                  {flags.length === 0 ? "—" : flags.map((flag) => <Tag key={flag}>{flag}</Tag>)}
                </DetailItem>
                <DetailItem label="Created by">
                  <Author
                    authorship={authorship}
                    kind="FIELD"
                    apiName={field.apiName}
                    which="created"
                    platformDefined={platformDefined}
                  />
                </DetailItem>
                <DetailItem label="Modified by">
                  <Author
                    authorship={authorship}
                    kind="FIELD"
                    apiName={field.apiName}
                    which="updated"
                    platformDefined={platformDefined}
                  />
                </DetailItem>
              </DetailGrid>
              {field.problem ? (
                <p className="form-message" role="note" data-testid="formula-problem">
                  This formula is not valid, so it is not calculated. {field.problem}
                </p>
              ) : null}
            </DetailSection>
            {rows.length > 0 || field.settings.values || field.settings.expression || field.settings.dependency ? (
              <DetailSection title={`${typeLabel(field.type)} settings`}>
                <DetailGrid>
                  {rows.map((row) => (
                    <DetailItem key={row.label} label={row.label}>
                      {row.value}
                    </DetailItem>
                  ))}
                  {field.settings.expression ? (
                    <DetailItem label="Formula" wide tone="mono">
                      {field.settings.expression}
                    </DetailItem>
                  ) : null}
                  {field.settings.dependency ? (
                    <DetailItem label="Depends on">
                      {object.fields.find((other) => other.apiName === field.settings.dependency?.controllingField)
                        ?.label ?? field.settings.dependency.controllingField}
                      {` (${Object.values(field.settings.dependency.allowed).reduce((total, row) => total + row.length, 0)} pairs)`}
                    </DetailItem>
                  ) : null}
                  {field.settings.values ? (
                    <DetailItem label="Values" wide>
                      <ul data-testid="field-values">
                        {field.settings.values.map((value) => (
                          <li key={value.value}>
                            {value.label || value.value} {value.active ? null : <Tag>Not offered</Tag>}
                          </li>
                        ))}
                      </ul>
                    </DetailItem>
                  ) : null}
                </DetailGrid>
              </DetailSection>
            ) : null}
          </>
        )}
      </DetailPage>
    );
  }

  return (
    <ListPage<FieldView>
      title={tabTitle(object, "Fields")}
      crumbs={crumbs}
      items={object.fields}
      rowKey={(row) => row.apiName}
      primaryHeader="Label"
      primary={(row) => row.label}
      primaryExtra={(row) => (
        <>
          {row.retired ? <span className="hint"> (retired)</span> : null}
          {row.problem ? <Tag tone="error">Formula not valid</Tag> : null}
        </>
      )}
      columns={[
        { key: "api", header: "API name", kind: "muted", cell: (row) => <code>{row.apiName}</code> },
        { key: "type", header: "Type", kind: "tag", cell: (row) => <Tag>{typeLabel(row.type)}</Tag> },
      ]}
      onOpen={(row) => {
        setEditing(false);
        setConfirmRemove(false);
        screen.open(row.apiName);
      }}
      searchText={(row) => `${row.label} ${row.apiName} ${typeLabel(row.type)}`}
      searchLabel="Search fields"
      onNew={object.extensible && offersManage ? screen.create : undefined}
      newLabel="New"
      notices={messages}
      emptyText="This object has no fields."
      testId="fields"
      rowTestId="field-row"
    />
  );
}
