"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, type ActionResult } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag, type TagTone } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import {
  changed,
  draftOf,
  newDraft,
  placeWords,
  requestOf,
  type ConnectorType,
  type Draft,
  type Integration,
  type SettingSpec,
} from "@/lib/integrations/model";
import { useOffers } from "@/lib/session/SessionProvider";
import { IntegrationMonitor } from "./IntegrationMonitor";
import { MappingDesigner } from "./MappingDesigner";

type ObjectSummary = components["schemas"]["ObjectSummaryView"];

const stateTone = (state: string): TagTone => (state === "ENABLED" ? "ok" : state === "FAILING" ? "error" : "quiet");

/** The API's words for a refusal, with every problem it lists under the form's own place names. */
async function refusal(error: unknown, response: Response, type: ConnectorType | undefined): Promise<string> {
  if (response.status === 429) {
    return failureText(error, response);
  }
  const failure = failureFromResponse(error, response);
  const lines = Object.entries(failure.fields ?? {}).flatMap(([field, problems]) =>
    problems.map((problem) => `${placeWords(field, type)}: ${problem}`),
  );
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

/**
 * The organization's connections to external systems (Sprint 26, ADR-0207 to ADR-0210). A list; a row opens the
 * connection (its state, the last test, what it listens for and may touch); New and Change open a form drawn from the
 * connector type the API describes. The page holds no connector type, setting, event, limit or reason of its own and
 * decides nothing: it shows what the API answers and its words for every refusal. A connection that is on is switched
 * off before it is changed (the API says which one can).
 */
export function IntegrationsPanel() {
  const [items, setItems] = useState<Integration[] | undefined>();
  const [types, setTypes] = useState<ConnectorType[]>([]);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [credentialNames, setCredentialNames] = useState<string[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [editing, setEditing] = useState(false);
  const offersManage = useOffers("integrations.manage");
  const offersTest = useOffers("integrations.test");
  const offersMappings = useOffers("mappings.view");

  const reload = useCallback(async () => {
    try {
      const [list, kinds, known, credentials] = await Promise.all([
        api.GET("/api/v1/integrations"),
        api.GET("/api/v1/integrations/connector-types"),
        api.GET("/api/v1/metadata/objects"),
        // A person who may not see credentials gets a refusal here and types the name instead.
        api.GET("/api/v1/credentials").catch(() => undefined),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setItems(list.data.data);
      setTypes(kinds.data?.data ?? []);
      setObjects(known.data?.data ?? []);
      setCredentialNames(credentials?.data?.data.map((one) => one.name) ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const messages = <ActionMessages notice={notice} problem={problem} testId="integrations" />;
  const typeOf = (key: string) => types.find((candidate) => candidate.key === key);

  const save = (draft: Draft, existing: Integration | undefined): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const body = requestOf(draft);
      const type = typeOf(draft.connectorType);
      if (existing) {
        const result = await api.PUT("/api/v1/integrations/{name}", { params: { path: { name: existing.name } }, body });
        if (result.response.ok) {
          setEditing(false);
        }
        return {
          error: result.error,
          response: result.response,
          text: "The connection was saved.",
          problem: result.response.ok ? undefined : await refusal(result.error, result.response, type),
        };
      }
      const result = await api.POST("/api/v1/integrations", { body });
      if (result.response.ok) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: "The connection was made. Test it, then switch it on.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response, type),
      };
    });

  const call = (item: Integration, path: "test" | "disable" | "enable") =>
    act(async (): Promise<ActionResult> => {
      const params = { params: { path: { name: item.name } } };
      if (path === "test") {
        const result = await api.POST("/api/v1/integrations/{name}/test", params);
        return { error: result.error, response: result.response, text: result.data?.data.words };
      }
      if (path === "enable") {
        const result = await api.POST("/api/v1/integrations/{name}/enable", { ...params, body: { version: item.version } });
        return { error: result.error, response: result.response, text: "The connection is switched on." };
      }
      const result = await api.POST("/api/v1/integrations/{name}/disable", params);
      return { error: result.error, response: result.response, text: "The connection is switched off." };
    });

  const remove = (item: Integration) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.DELETE("/api/v1/integrations/{name}", { params: { path: { name: item.name } } });
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The connection was removed." };
    });

  if (screen.creating && items) {
    return (
      <IntegrationForm
        key="new"
        start={newDraft(types[0])}
        types={types}
        objects={objects}
        credentialNames={credentialNames}
        busy={busy}
        messages={messages}
        onCancel={() => screen.back()}
        onSubmit={(draft) => void save(draft, undefined)}
        title="New connection"
        submitLabel="Make the connection"
      />
    );
  }

  const item = items?.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && items && !item) {
    return <MissingDetail listLabel="Integrations" onBack={() => screen.back()} what="This connection does not exist any more." />;
  }

  if (item && editing) {
    return (
      <IntegrationForm
        key={`${item.id}-${item.version}`}
        start={draftOf(item)}
        existing={item}
        types={types}
        objects={objects}
        credentialNames={credentialNames}
        busy={busy}
        messages={messages}
        onCancel={() => setEditing(false)}
        onSubmit={(draft) => void save(draft, item)}
        title={`Change ${item.label}`}
        submitLabel="Save"
      />
    );
  }

  if (item) {
    const type = typeOf(item.connectorType);
    const labelOf = (key: string) => objects.find((candidate) => candidate.apiName === key)?.label ?? key;
    return (
      <DetailPage
        listLabel="Integrations"
        onBack={() => screen.back()}
        title={item.label}
        titleExtra={<Tag tone={stateTone(item.state)}>{item.stateLabel}</Tag>}
        notices={messages}
        testId="integration"
        actions={
          <>
            {offersTest ? (
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => void call(item, "test")}>
                Test
              </button>
            ) : null}
            {offersManage && (item.state === "ENABLED" || item.state === "FAILING") ? (
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => void call(item, "disable")}>
                Switch off
              </button>
            ) : null}
            {offersManage && (item.state !== "ENABLED" && item.state !== "FAILING") ? (
              <button
                type="button"
                className="button"
                disabled={busy || !item.canEnable}
                title={item.canEnable ? undefined : "Test the connection successfully before switching it on."}
                onClick={() => void call(item, "enable")}
              >
                Switch on
              </button>
            ) : null}
            {offersManage ? (
              <button
                type="button"
                className="button button-secondary"
                disabled={busy || !item.canChange}
                title={item.canChange ? undefined : "Switch the connection off before changing it."}
                onClick={() => setEditing(true)}
              >
                Change
              </button>
            ) : null}
            {offersManage ? (
              <button
                type="button"
                className="button button-danger"
                disabled={busy || !item.canChange}
                title={item.canChange ? undefined : "Switch the connection off before removing it."}
                onClick={() => void remove(item)}
              >
                Remove
              </button>
            ) : null}
          </>
        }
      >
        <DetailSection title="Status">
          <DetailGrid>
            <DetailItem label="State">{item.stateLabel}</DetailItem>
            <DetailItem label="Last test" tone={item.lastTest ? undefined : "muted"}>
              {item.lastTest ? `${item.lastTest.words} (${new Date(item.lastTest.at).toLocaleString()})` : "Not tested yet."}
            </DetailItem>
          </DetailGrid>
        </DetailSection>
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Kind">{item.connectorTypeLabel}</DetailItem>
            <DetailItem label="Address" tone="mono">{item.baseUrl}</DetailItem>
            <DetailItem label="Sign-in">
              {type?.authKinds.find((kind) => kind.key === item.authKind)?.label ?? item.authKind}
            </DetailItem>
            {item.credentialName ? <DetailItem label="Credential" tone="mono">{item.credentialName}</DetailItem> : null}
            {item.authHeaderName ? <DetailItem label="Header" tone="mono">{item.authHeaderName}</DetailItem> : null}
            {type?.settings.map((setting) => (
              <DetailItem key={setting.key} label={setting.label}>
                {item.settings[setting.key] ?? "—"}
              </DetailItem>
            ))}
          </DetailGrid>
        </DetailSection>
        <DetailSection title="Listens for">
          {item.subscriptions.length === 0 ? <p className="dl-value muted">Nothing.</p> : null}
          <ul data-testid="integration-events">
            {item.subscriptions.map((one) => (
              <li key={`${one.event}-${one.object}`}>
                {type?.platformEvents.find((event) => event.key === one.event)?.label ?? one.event}: {labelOf(one.object)}
              </li>
            ))}
          </ul>
        </DetailSection>
        {offersMappings ? <MappingDesigner key={item.id} connection={item} /> : null}
        <IntegrationMonitor key={`monitor-${item.id}`} connection={item} />
        <DetailSection title="May touch">
          {item.scope.length === 0 ? <p className="dl-value muted">Nothing.</p> : null}
          <ul data-testid="integration-scope">
            {item.scope.map((one) => (
              <li key={one.object}>
                {labelOf(one.object)}: {[one.read ? "read" : "", one.create ? "create" : "", one.update ? "change" : ""]
                  .filter(Boolean)
                  .join(", ")}
              </li>
            ))}
          </ul>
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<Integration>
      title="Integrations"
      items={items}
      failure={failure}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.label}
      sortPrimary
      columns={[
        { key: "kind", header: "Kind", kind: "muted", cell: (row) => row.connectorTypeLabel },
        { key: "address", header: "Address", kind: "muted", cell: (row) => row.baseUrl },
        { key: "state", header: "State", kind: "tag", cell: (row) => <Tag tone={stateTone(row.state)}>{row.stateLabel}</Tag> },
      ]}
      onOpen={(row) => {
        setEditing(false);
        screen.open(row.id);
      }}
      searchText={(row) => `${row.label} ${row.name} ${row.connectorTypeLabel}`}
      onNew={offersManage && types.length > 0 ? screen.create : undefined}
      newLabel="New connection"
      notices={messages}
      emptyText="There are no connections to external systems yet."
      testId="integrations"
      rowTestId="integration-row"
    />
  );
}

interface FormProps {
  start: Draft;
  existing?: Integration;
  types: ConnectorType[];
  objects: ObjectSummary[];
  /** The names of the credentials the person may see; empty when the API offers none. */
  credentialNames: string[];
  busy: boolean;
  messages: ReactNode;
  title: string;
  submitLabel: string;
  onCancel: () => void;
  onSubmit: (draft: Draft) => void;
}

/** The form for a connection, drawn from the connector type the API describes. */
function IntegrationForm({
  start,
  existing,
  types,
  objects,
  credentialNames,
  busy,
  messages,
  title,
  submitLabel,
  onCancel,
  onSubmit,
}: FormProps) {
  const [draft, setDraft] = useState<Draft>(start);
  const type = useMemo(() => types.find((candidate) => candidate.key === draft.connectorType), [types, draft.connectorType]);
  const patch = (change: Partial<Draft>) => setDraft((old) => ({ ...old, ...change }));
  const setting = (key: string, value: string) => setDraft((old) => ({ ...old, settings: { ...old.settings, [key]: value } }));
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit(draft);
  };
  const objectInput = (id: string, value: string, label: string, onChange: (next: string) => void) =>
    objects.length > 0 ? (
      <select id={id} aria-label={label} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Choose an object</option>
        {objects.map((object) => (
          <option key={object.apiName} value={object.apiName}>
            {object.label}
          </option>
        ))}
      </select>
    ) : (
      <input id={id} aria-label={label} value={value} onChange={(event) => onChange(event.target.value)} />
    );

  return (
    <DetailPage
      listLabel="Integrations"
      onBack={onCancel}
      title={title}
      notices={messages}
      actions={
        <>
          {changed(draft, start) ? <span className="hint">Unsaved changes</span> : null}
          <button type="button" className="button button-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" form="integration-form" className="button" disabled={busy}>
            {submitLabel}
          </button>
        </>
      }
    >
      <form id="integration-form" onSubmit={submit} noValidate>
        <DetailSection title="Detail">
          <DetailGrid>
            {existing ? null : (
              <div className="field">
                <label htmlFor="integration-name">Name</label>
                <input id="integration-name" value={draft.name} maxLength={40} onChange={(event) => patch({ name: event.target.value })} />
              </div>
            )}
            <div className="field">
              <label htmlFor="integration-label">Label</label>
              <input id="integration-label" value={draft.label} maxLength={80} onChange={(event) => patch({ label: event.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="integration-kind">Kind</label>
              <select
                id="integration-kind"
                value={draft.connectorType}
                disabled={existing !== undefined}
                onChange={(event) => {
                  const next = types.find((candidate) => candidate.key === event.target.value);
                  patch({ connectorType: event.target.value, authKind: next?.authKinds[0]?.key ?? "", settings: newDraft(next).settings });
                }}
              >
                {types.map((candidate) => (
                  <option key={candidate.key} value={candidate.key}>
                    {candidate.label}
                  </option>
                ))}
              </select>
              {type ? <span className="hint">{type.description}</span> : null}
            </div>
            <div className="field">
              <label htmlFor="integration-url">Address</label>
              <input id="integration-url" value={draft.baseUrl} maxLength={2048} onChange={(event) => patch({ baseUrl: event.target.value })} />
            </div>
          </DetailGrid>
        </DetailSection>

        <DetailSection title="Sign-in">
          <DetailGrid>
            <div className="field">
              <label htmlFor="integration-auth">How it signs in</label>
              <select id="integration-auth" value={draft.authKind} onChange={(event) => patch({ authKind: event.target.value })}>
                {type?.authKinds.map((kind) => (
                  <option key={kind.key} value={kind.key}>
                    {kind.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="integration-credential">Name of the credential</label>
              {credentialNames.length > 0 ? (
                <select
                  id="integration-credential"
                  value={draft.credentialName}
                  onChange={(event) => patch({ credentialName: event.target.value })}
                >
                  <option value="">Choose a credential</option>
                  {draft.credentialName !== "" && !credentialNames.includes(draft.credentialName) ? (
                    <option value={draft.credentialName}>{draft.credentialName}</option>
                  ) : null}
                  {credentialNames.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  id="integration-credential"
                  value={draft.credentialName}
                  maxLength={128}
                  onChange={(event) => patch({ credentialName: event.target.value })}
                />
              )}
              <span className="hint">
                Only the name is kept here. The credential itself is never shown or stored with the connection; it is
                added under Credentials.
              </span>
            </div>
            <div className="field">
              <label htmlFor="integration-header">Header that carries the credential</label>
              <input
                id="integration-header"
                value={draft.authHeaderName}
                maxLength={64}
                onChange={(event) => patch({ authHeaderName: event.target.value })}
              />
            </div>
          </DetailGrid>
        </DetailSection>

        {type && type.settings.length > 0 ? (
          <DetailSection title="Settings">
            <DetailGrid>
              {type.settings.map((spec) => (
                <SettingField key={spec.key} spec={spec} value={draft.settings[spec.key] ?? ""} onChange={(value) => setting(spec.key, value)} />
              ))}
            </DetailGrid>
          </DetailSection>
        ) : null}

        <DetailSection title="Listens for">
          {draft.subscriptions.map((row, index) => (
            <div className="form-actions" key={index}>
              <select
                aria-label={`Event ${index + 1}`}
                value={row.event}
                onChange={(event) =>
                  patch({ subscriptions: draft.subscriptions.map((one, at) => (at === index ? { ...one, event: event.target.value } : one)) })
                }
              >
                <option value="">Choose an event</option>
                {type?.platformEvents.map((event) => (
                  <option key={event.key} value={event.key}>
                    {event.label}
                  </option>
                ))}
              </select>
              {objectInput(`subscription-object-${index}`, row.object, `Object of event ${index + 1}`, (next) =>
                patch({ subscriptions: draft.subscriptions.map((one, at) => (at === index ? { ...one, object: next } : one)) }),
              )}
              <button
                type="button"
                className="link-button"
                onClick={() => patch({ subscriptions: draft.subscriptions.filter((_, at) => at !== index) })}
              >
                Take out
              </button>
            </div>
          ))}
          <button
            type="button"
            className="button button-secondary"
            onClick={() => patch({ subscriptions: [...draft.subscriptions, { event: "", object: "" }] })}
          >
            Add an event
          </button>
        </DetailSection>

        <DetailSection title="May touch">
          {draft.scope.map((row, index) => (
            <div className="form-actions" key={index}>
              {objectInput(`scope-object-${index}`, row.object, `Object ${index + 1}`, (next) =>
                patch({ scope: draft.scope.map((one, at) => (at === index ? { ...one, object: next } : one)) }),
              )}
              {(["read", "create", "update"] as const).map((action) => (
                <label key={action}>
                  <input
                    type="checkbox"
                    checked={row[action]}
                    onChange={(event) =>
                      patch({ scope: draft.scope.map((one, at) => (at === index ? { ...one, [action]: event.target.checked } : one)) })
                    }
                  />{" "}
                  {action === "update" ? "change" : action}
                </label>
              ))}
              <button type="button" className="link-button" onClick={() => patch({ scope: draft.scope.filter((_, at) => at !== index) })}>
                Take out
              </button>
            </div>
          ))}
          <button
            type="button"
            className="button button-secondary"
            onClick={() => patch({ scope: [...draft.scope, { object: "", read: true, create: false, update: false }] })}
          >
            Add an object
          </button>
        </DetailSection>
      </form>
    </DetailPage>
  );
}

/** One setting, drawn by the kind the connector type gives it. */
function SettingField({ spec, value, onChange }: { spec: SettingSpec; value: string; onChange: (value: string) => void }) {
  const id = `setting-${spec.key}`;
  return (
    <div className="field">
      <label htmlFor={id}>{spec.label}</label>
      {spec.kind === "CHOICE" ? (
        <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
          {spec.choices.map((choice) => (
            <option key={choice.key} value={choice.key}>
              {choice.label}
            </option>
          ))}
        </select>
      ) : spec.kind === "BOOLEAN" ? (
        <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      ) : (
        <input
          id={id}
          type={spec.kind === "INTEGER" ? "number" : "text"}
          min={spec.min ?? undefined}
          max={spec.max ?? undefined}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      <span className="hint">{spec.hint}</span>
    </div>
  );
}
