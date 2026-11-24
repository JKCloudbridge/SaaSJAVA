"use client";

import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, type ActionResult } from "@/components/console/useAction";
import { Modal } from "@/components/ui/Modal";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { SecretDialog } from "@/components/setup/SecretDialog";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import {
  isSwitchedOff,
  lastUsedWords,
  objectsText,
  objectsWords,
  parseObjects,
  secretEnded,
  stateWords,
  stoppedForMember,
  type ConnectedApp,
  type ConnectedAppCatalogue,
  type ConnectedAppDetail,
} from "@/lib/connectedApps/model";
import { useOffers } from "@/lib/session/SessionProvider";

/** The API's words for a refusal, with every problem it lists. */
async function refusal(error: unknown, response: Response): Promise<string> {
  if (response.status === 429) {
    return failureText(error, response);
  }
  const failure = failureFromResponse(error, response);
  const lines = Object.values(failure.fields ?? {}).flat();
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

interface FormValues {
  name: string;
  description: string;
  scopes: string[];
  objects: string[] | undefined;
  tokenMinutes: number | undefined;
}

/**
 * The organization's connected applications (Sprint 31, ADR-0267 to ADR-0270): other people's software that asks for
 * short-lived tokens with a client identifier and a secret and acts as one member. A list of applications; a row opens one;
 * New and Edit open a form; the secrets are made and ended from the page of the application.
 *
 * <p>The scopes, the limits, the address of the token endpoint, the worked request and the words that explain whom an
 * application acts as all come from the API's catalogue: the page holds no list of its own and decides nothing, it shows
 * what the API answers, in its words.
 *
 * <p>A secret exists on this page only between the answer that carries it and the closing of its dialog: it is held in
 * this component's memory, shown once, never put in an address, storage, a shared cache or a log, and gone when the
 * dialog closes or the page is left.
 */
export function ConnectedAppsPanel() {
  const [items, setItems] = useState<ConnectedApp[] | undefined>();
  const [catalogue, setCatalogue] = useState<ConnectedAppCatalogue | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [detail, setDetail] = useState<ConnectedAppDetail | undefined>();
  const [tick, setTick] = useState(0);
  const [editing, setEditing] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [removing, setRemoving] = useState<ConnectedApp | undefined>();
  const [stopHelp, setStopHelp] = useState(false);
  const [secret, setSecret] = useState<string | undefined>();
  const offersManage = useOffers("clients.manage");

  const reload = useCallback(async () => {
    try {
      const list = await api.GET("/api/v1/connected-apps");
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setItems(list.data.data);
      setTick((count) => count + 1);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(async () => {
      try {
        const answer = await api.GET("/api/v1/connected-apps/catalogue");
        if (answer.data) {
          setCatalogue(answer.data.data);
        }
      } catch {
        // The list reports its own failure; the form and the notes wait for the catalogue.
      }
    });
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const openId = screen.openId;
  const messages = <ActionMessages notice={notice} problem={problem} testId="connected-apps" />;

  useEffect(() => {
    if (!openId) {
      return;
    }
    let current = true;
    void Promise.resolve().then(async () => {
      try {
        const answer = await api.GET("/api/v1/connected-apps/{id}", { params: { path: { id: openId } } });
        if (current) {
          setDetail(answer.data ? answer.data.data : undefined);
        }
      } catch {
        if (current) {
          setDetail(undefined);
        }
      }
    });
    return () => {
      current = false;
    };
  }, [openId, tick]);

  const closeSecret = () => setSecret(undefined);

  const register = (values: FormValues): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const result = await api.POST("/api/v1/connected-apps", {
        body: {
          name: values.name,
          description: values.description,
          scopes: values.scopes,
          objects: values.objects,
          tokenMinutes: values.tokenMinutes,
        },
      });
      if (result.response.ok && result.data) {
        // Kept in memory only until the dialog closes; never returned from this function or stored anywhere else.
        setSecret(result.data.data.clientSecret);
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: "The connected application was registered. Its secret is shown once.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const update = (app: ConnectedApp, values: FormValues): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const latest = detail?.app.id === app.id ? detail.app : app;
      const result = await api.PUT("/api/v1/connected-apps/{id}", {
        params: { path: { id: app.id } },
        body: {
          name: values.name,
          description: values.description,
          scopes: values.scopes,
          objects: values.objects,
          tokenMinutes: values.tokenMinutes ?? latest.tokenMinutes ?? 0,
          version: latest.version ?? 0,
        },
      });
      if (result.response.ok) {
        setEditing(false);
      } else if (result.response.status === 409) {
        // Someone else changed it: read it again, keep what was typed, and let the person decide.
        await reload();
      }
      return {
        error: result.error,
        response: result.response,
        text: "The connected application was changed.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const rotate = (app: ConnectedApp, overlapHours: number, endTokensNow: boolean): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const result = await api.POST("/api/v1/connected-apps/{id}/secrets/rotate", {
        params: { path: { id: app.id } },
        body: { overlapHours, endTokensNow },
      });
      if (result.response.ok && result.data) {
        setSecret(result.data.data.clientSecret);
        setRotating(false);
      }
      return {
        error: result.error,
        response: result.response,
        text: "A new secret was made. It is shown once.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const endSecret = (app: ConnectedApp, secretId: string) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.DELETE("/api/v1/connected-apps/{id}/secrets/{secretId}", {
        params: { path: { id: app.id, secretId } },
      });
      return { error, response, text: "The secret was ended." };
    });

  const switchTo = (app: ConnectedApp, on: boolean) =>
    act(async (): Promise<ActionResult> => {
      const path = { params: { path: { id: app.id } } };
      const { error, response } = on
        ? await api.POST("/api/v1/connected-apps/{id}/enable", path)
        : await api.POST("/api/v1/connected-apps/{id}/disable", path);
      return { error, response, text: on ? "The connected application was switched on." : "The connected application was switched off." };
    });

  const remove = (app: ConnectedApp) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.DELETE("/api/v1/connected-apps/{id}", { params: { path: { id: app.id } } });
      setRemoving(undefined);
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The connected application was removed." };
    });

  const scopeTitle = (key: string) => catalogue?.scopes.find((scope) => scope.key === key)?.title ?? key;
  const scopesWords = (app: ConnectedApp) => app.scopes.map(scopeTitle).join(", ");

  const dialogs = (
    <>
      {secret ? <SecretDialog secret={secret} onClose={closeSecret} /> : null}
      {stopHelp && catalogue ? (
        <Modal title="This application has stopped" onClose={() => setStopHelp(false)} testId="connected-app-stopped">
          <p>{catalogue.stoppedNote}</p>
          <div className="form-actions">
            <button type="button" className="button" onClick={() => setStopHelp(false)}>
              Close
            </button>
          </div>
        </Modal>
      ) : null}
    </>
  );

  if (screen.creating && items && catalogue) {
    return (
      <>
        <AppForm
          key="new"
          title="New connected application"
          submitLabel="Register the application"
          catalogue={catalogue}
          busy={busy}
          messages={messages}
          onCancel={() => screen.back()}
          onSubmit={(values) => void register(values)}
        />
        {dialogs}
      </>
    );
  }

  const listed = items?.find((candidate) => candidate.id === openId);
  const item = detail && detail.app.id === openId ? detail.app : listed;
  if (openId && items && !listed) {
    return (
      <>
        <MissingDetail
          listLabel="Connected applications"
          onBack={() => screen.back()}
          what="This connected application does not exist any more."
        />
        {dialogs}
      </>
    );
  }

  if (item && editing && catalogue) {
    return (
      <>
        <AppForm
          key={item.id}
          title={`Change ${item.name}`}
          submitLabel="Save the changes"
          catalogue={catalogue}
          initial={item}
          busy={busy}
          messages={messages}
          onCancel={() => setEditing(false)}
          onSubmit={(values) => void update(item, values)}
        />
        {dialogs}
      </>
    );
  }

  if (item) {
    return (
      <>
        <DetailPage
          listLabel="Connected applications"
          onBack={() => screen.back()}
          title={item.name}
          notices={messages}
          testId="connected-app"
          actions={
            offersManage ? (
              <>
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => setEditing(true)}>
                  Change
                </button>
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => setRotating(true)}>
                  Make a new secret
                </button>
                {isSwitchedOff(item) ? (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => void switchTo(item, true)}>
                    Switch on
                  </button>
                ) : (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => void switchTo(item, false)}>
                    Switch off
                  </button>
                )}
                <button type="button" className="button button-danger" disabled={busy} onClick={() => setRemoving(item)}>
                  Remove
                </button>
              </>
            ) : null
          }
        >
          <DetailSection title="Detail">
            <DetailGrid>
              <DetailItem label="Client identifier" tone="mono">
                {item.clientId}
              </DetailItem>
              <DetailItem label="State">
                <Tag tone={item.running ? "ok" : "quiet"}>{item.running ? "Running" : "Stopped"}</Tag>{" "}
                <span>{stateWords(item.state)}</span>
              </DetailItem>
              <DetailItem label="Acts as" tone={item.actsAs ? undefined : "muted"}>
                {item.actsAs ? (item.actsAsYou ? `${item.actsAs} (you)` : item.actsAs) : "A member who left"}
              </DetailItem>
              <DetailItem label="Scopes">{scopesWords(item)}</DetailItem>
              <DetailItem label="Objects" tone={item.objects ? undefined : "muted"}>
                {objectsWords(item)}
              </DetailItem>
              <DetailItem label="Token life">{`${item.tokenMinutes ?? 0} minutes`}</DetailItem>
              <DetailItem label="Registered">{new Date(item.createdAt).toLocaleString()}</DetailItem>
              <DetailItem label="Last used" tone={item.lastUsedAt ? undefined : "muted"}>
                {lastUsedWords(item)}
              </DetailItem>
              {item.description ? (
                <DetailItem label="Description" wide>
                  {item.description}
                </DetailItem>
              ) : null}
            </DetailGrid>
            {stoppedForMember(item) && catalogue ? (
              <p className="hint" data-testid="connected-app-stopped-note">
                {catalogue.stoppedNote}
              </p>
            ) : null}
          </DetailSection>
          <DetailSection title="Secrets">
            {detail && detail.app.id === item.id ? (
              detail.secrets.length === 0 ? (
                <p className="hint">This application has no secret.</p>
              ) : (
                <table className="table" data-testid="connected-app-secrets">
                  <thead>
                    <tr>
                      <th scope="col">Secret</th>
                      <th scope="col">State</th>
                      <th scope="col">Made</th>
                      <th scope="col">Last used</th>
                      <th scope="col">Ends</th>
                      <th scope="col">
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.secrets.map((one) => (
                      <tr key={one.id} data-testid="connected-app-secret">
                        <td>{`Secret ${one.number ?? ""}`.trim()}</td>
                        <td>{stateWords(one.state)}</td>
                        <td>{new Date(one.createdAt).toLocaleString()}</td>
                        <td>{lastUsedWords(one)}</td>
                        <td>{one.endsAt ? new Date(one.endsAt).toLocaleString() : "Not set"}</td>
                        <td>
                          {offersManage && !secretEnded(one) ? (
                            <button type="button" className="link-button" disabled={busy} onClick={() => void endSecret(item, one.id)}>
                              End it now
                            </button>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )
            ) : (
              <p className="hint">Loading the secrets…</p>
            )}
            <p className="hint">A secret is shown once, when it is made, and cannot be shown again.</p>
          </DetailSection>
          {catalogue ? (
            <DetailSection title="Asking for a token" stack>
              <p className="hint">
                The software asks for a token at <code>{catalogue.tokenPath}</code> on the host of this organization.
              </p>
              <div data-testid="connected-app-example">
                {catalogue.tokenRequestExample.split("\n").map((line, index) => (
                  <div key={index}>{line ? <code>{line}</code> : <br />}</div>
                ))}
              </div>
            </DetailSection>
          ) : null}
        </DetailPage>
        {rotating ? (
          <RotateDialog
            catalogue={catalogue}
            busy={busy}
            onClose={() => setRotating(false)}
            onRotate={(hours, endNow) => void rotate(item, hours, endNow)}
          />
        ) : null}
        {removing ? (
          <Modal title={`Remove ${removing.name}?`} onClose={() => setRemoving(undefined)} testId="connected-app-remove">
            <p>Its secrets end and its tokens stop working now. It cannot be brought back.</p>
            <div className="form-actions">
              <button type="button" className="button button-secondary" onClick={() => setRemoving(undefined)}>
                Cancel
              </button>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(removing)}>
                Remove the application
              </button>
            </div>
          </Modal>
        ) : null}
        {dialogs}
      </>
    );
  }

  return (
    <>
      <ListPage<ConnectedApp>
        title="Connected applications"
        items={items}
        failure={failure}
        rowKey={(row) => row.id}
        primaryHeader="Name"
        primary={(row) => row.name}
        sortPrimary
        columns={[
          { key: "scopes", header: "Scopes", kind: "muted", cell: (row) => scopesWords(row) },
          { key: "actsAs", header: "Acts as", kind: "muted", cell: (row) => row.actsAs ?? "A member who left" },
          { key: "used", header: "Last used", kind: "muted", cell: (row) => lastUsedWords(row) },
          {
            key: "state",
            header: "State",
            kind: "tag",
            cell: (row) => (
              <>
                <Tag tone={row.running ? "ok" : "quiet"}>{row.running ? "Running" : "Stopped"}</Tag>
                {stoppedForMember(row) ? (
                  <>
                    {" "}
                    <Tag tone="error">Stopped: its member is no longer active</Tag>{" "}
                    <button type="button" className="link-button" onClick={() => setStopHelp(true)}>
                      What to do
                    </button>
                  </>
                ) : null}
              </>
            ),
          },
        ]}
        onOpen={(row) => {
          setEditing(false);
          setRotating(false);
          setDetail(undefined);
          screen.open(row.id);
        }}
        searchText={(row) => `${row.name} ${row.clientId} ${row.actsAs ?? ""}`}
        onNew={offersManage && catalogue ? screen.create : undefined}
        newLabel="New connected application"
        notices={messages}
        emptyText="There are no connected applications yet."
        testId="connected-apps"
        rowTestId="connected-app-row"
      />
      {dialogs}
    </>
  );
}

/** Making a new secret: how long the older ones keep working, and whether their tokens end now as well. */
function RotateDialog({
  catalogue,
  busy,
  onClose,
  onRotate,
}: {
  catalogue: ConnectedAppCatalogue | undefined;
  busy: boolean;
  onClose: () => void;
  onRotate: (overlapHours: number, endTokensNow: boolean) => void;
}) {
  const [overlap, setOverlap] = useState("0");
  const [endNow, setEndNow] = useState(false);
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const hours = Number.parseInt(overlap, 10);
    onRotate(Number.isFinite(hours) && hours > 0 ? hours : 0, endNow);
  };
  return (
    <Modal title="Make a new secret" onClose={onClose} testId="connected-app-rotate">
      <form onSubmit={submit} noValidate>
        <p>
          The older secrets stop working at once, unless you give them a time to overlap so that the software can be changed
          over. The tokens already issued last until they end, unless you end them now.
        </p>
        <div className="field">
          <label htmlFor="connected-app-overlap">Hours the older secrets keep working</label>
          <input
            id="connected-app-overlap"
            type="number"
            min={0}
            max={catalogue?.maxOverlapHours}
            value={overlap}
            onChange={(event) => setOverlap(event.target.value)}
          />
          {catalogue?.maxOverlapHours ? <span className="hint">{`0 ends them now; at most ${catalogue.maxOverlapHours}.`}</span> : null}
        </div>
        <label>
          <input type="checkbox" checked={endNow} onChange={(event) => setEndNow(event.target.checked)} /> Also end the tokens
          they already made now
        </label>
        <div className="form-actions">
          <button type="button" className="button button-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="button" disabled={busy}>
            Make the new secret
          </button>
        </div>
      </form>
    </Modal>
  );
}

interface FormProps {
  title: string;
  submitLabel: string;
  catalogue: ConnectedAppCatalogue;
  /** The application being changed; without it the form registers a new one. */
  initial?: ConnectedApp;
  busy: boolean;
  messages: ReactNode;
  onCancel: () => void;
  onSubmit: (values: FormValues) => void;
}

/** The form of a connected application: what it is, which scopes it holds, the objects it is limited to, how long its tokens live. */
function AppForm({ title, submitLabel, catalogue, initial, busy, messages, onCancel, onSubmit }: FormProps) {
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [scopes, setScopes] = useState<string[]>(initial?.scopes ?? []);
  const [objects, setObjects] = useState(objectsText(initial?.objects));
  const [minutes, setMinutes] = useState(initial?.tokenMinutes ? String(initial.tokenMinutes) : "");

  const toggle = (key: string, on: boolean) =>
    setScopes((current) => (on ? [...current, key] : current.filter((one) => one !== key)));

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const parsed = Number.parseInt(minutes, 10);
    onSubmit({
      name: name.trim(),
      description: description.trim(),
      scopes: catalogue.scopes.map((scope) => scope.key).filter((key) => scopes.includes(key)),
      objects: parseObjects(objects),
      tokenMinutes: Number.isFinite(parsed) ? parsed : undefined,
    });
  };

  return (
    <DetailPage
      listLabel="Connected applications"
      onBack={onCancel}
      title={title}
      notices={messages}
      actions={
        <>
          <button type="button" className="button button-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" form="connected-app-form" className="button" disabled={busy}>
            {submitLabel}
          </button>
        </>
      }
    >
      <form id="connected-app-form" onSubmit={submit} noValidate autoComplete="off">
        {initial ? null : (
          <p className="hint" data-testid="connected-app-acting">
            {catalogue.actingNote} This application will act as the member who is signed in: you.
          </p>
        )}
        <DetailSection title="Detail">
          <DetailGrid>
            <div className="field">
              <label htmlFor="connected-app-name">Name</label>
              <input id="connected-app-name" value={name} onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="connected-app-description">Description</label>
              <textarea id="connected-app-description" value={description} onChange={(event) => setDescription(event.target.value)} />
            </div>
          </DetailGrid>
        </DetailSection>
        <DetailSection title="What it may do" stack>
          {catalogue.scopes.map((scope) => (
            <div className="field" key={scope.key}>
              <label>
                <input
                  type="checkbox"
                  checked={scopes.includes(scope.key)}
                  onChange={(event) => toggle(scope.key, event.target.checked)}
                />{" "}
                {scope.title}
              </label>
              <span className="hint">{scope.description}</span>
            </div>
          ))}
          <div className="field">
            <label htmlFor="connected-app-objects">Only these objects</label>
            <textarea id="connected-app-objects" value={objects} onChange={(event) => setObjects(event.target.value)} />
            <span className="hint">
              One API name per line. Leave it empty for every object its member may reach.
              {catalogue.maxObjects ? ` At most ${catalogue.maxObjects}.` : ""}
            </span>
          </div>
          <div className="field">
            <label htmlFor="connected-app-minutes">Minutes a token lives</label>
            <input
              id="connected-app-minutes"
              type="number"
              min={catalogue.minTokenMinutes}
              max={catalogue.maxTokenMinutes}
              placeholder={catalogue.defaultTokenMinutes ? String(catalogue.defaultTokenMinutes) : undefined}
              value={minutes}
              onChange={(event) => setMinutes(event.target.value)}
            />
            <span className="hint">
              {`Between ${catalogue.minTokenMinutes ?? ""} and ${catalogue.maxTokenMinutes ?? ""} minutes; empty means ${
                catalogue.defaultTokenMinutes ?? ""
              }.`}
            </span>
          </div>
        </DetailSection>
      </form>
    </DetailPage>
  );
}
