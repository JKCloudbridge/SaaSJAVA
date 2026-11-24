"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, type ActionResult } from "@/components/console/useAction";
import { SecretDialog } from "@/components/setup/SecretDialog";
import { WebhookDeliveries } from "@/components/setup/WebhookDeliveries";
import { WebhookForm, type WebhookFormValues } from "@/components/setup/WebhookForm";
import { Modal } from "@/components/ui/Modal";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import { useOffers } from "@/lib/session/SessionProvider";
import {
  eventsWords,
  isFailing,
  isSwitchedOff,
  objectsWords,
  stateWords,
  stoppedForMember,
  whenWords,
  type Webhook,
  type WebhookCatalogue,
} from "@/lib/webhooks/model";

/** The API's words for a refusal, with every problem it lists. */
async function refusal(error: unknown, response: Response): Promise<string> {
  if (response.status === 429) {
    return failureText(error, response);
  }
  const failure = failureFromResponse(error, response);
  const lines = Object.values(failure.fields ?? {}).flat();
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

/**
 * The organization's webhooks (Sprint 32, ADR-0274 to ADR-0279): standing instructions to be told, at an address of its
 * own software, with a small signed message of identifiers, when records change. A list; a row opens one with its delivery
 * log; New and Change open a form; the signing secret is made and replaced from the page of the webhook.
 *
 * <p>The events, the states of the filter, the sentences for a reason, the limits and the words that explain whom a
 * webhook acts as all come from the API: the page holds no list of its own and decides nothing, it shows what the API
 * answers, in its words.
 *
 * <p>A signing secret exists on this page only between the answer that carries it and the closing of its dialog: it is held
 * in this component's memory, shown once, never put in an address, storage, a shared cache or a log, and gone when the
 * dialog closes or the page is left.
 */
export function WebhooksPanel() {
  const [items, setItems] = useState<Webhook[] | undefined>();
  const [catalogue, setCatalogue] = useState<WebhookCatalogue | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [detail, setDetail] = useState<Webhook | undefined>();
  const [tick, setTick] = useState(0);
  const [editing, setEditing] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [removing, setRemoving] = useState<Webhook | undefined>();
  const [stopHelp, setStopHelp] = useState(false);
  const [secret, setSecret] = useState<string | undefined>();
  const offersManage = useOffers("webhooks.manage");
  const offersReplay = useOffers("webhooks.replay");

  const reload = useCallback(async () => {
    try {
      const list = await api.GET("/api/v1/webhooks");
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
        const answer = await api.GET("/api/v1/webhooks/catalogue");
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
  const messages = <ActionMessages notice={notice} problem={problem} testId="webhooks" />;

  useEffect(() => {
    if (!openId) {
      return;
    }
    let current = true;
    void Promise.resolve().then(async () => {
      try {
        const answer = await api.GET("/api/v1/webhooks/{id}", { params: { path: { id: openId } } });
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

  const make = (values: WebhookFormValues): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const result = await api.POST("/api/v1/webhooks", { body: { ...values, version: 0 } });
      if (result.response.ok && result.data) {
        // Kept in memory only until the dialog closes; never returned from this function or stored anywhere else.
        setSecret(result.data.data.secret);
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: "The webhook was made. Its signing secret is shown once. Send a test to verify the address, then switch it on.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const update = (hook: Webhook, values: WebhookFormValues): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const latest = detail?.id === hook.id ? detail : hook;
      const result = await api.PUT("/api/v1/webhooks/{id}", {
        params: { path: { id: hook.id } },
        body: { ...values, version: latest.version ?? 0 },
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
        text: "The webhook was changed. Send a test to verify the address again.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const test = (hook: Webhook) =>
    act(async (): Promise<ActionResult> => {
      const result = await api.POST("/api/v1/webhooks/{id}/test", { params: { path: { id: hook.id } } });
      const said = result.data?.data;
      return {
        error: result.error,
        response: result.response,
        text: said?.delivered ? said.message : undefined,
        problem: said ? (said.delivered ? undefined : said.message) : await refusal(result.error, result.response),
        failed: said ? !said.delivered : false,
      };
    });

  const switchTo = (hook: Webhook, on: boolean) =>
    act(async (): Promise<ActionResult> => {
      const result = on
        ? await api.POST("/api/v1/webhooks/{id}/enable", {
            params: { path: { id: hook.id } },
            body: { version: (detail?.id === hook.id ? detail : hook).version ?? 0 },
          })
        : await api.POST("/api/v1/webhooks/{id}/disable", { params: { path: { id: hook.id } } });
      return {
        error: result.error,
        response: result.response,
        text: on ? "The webhook was switched on." : "The webhook was switched off.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const rotate = (hook: Webhook, overlapHours: number): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const result = await api.POST("/api/v1/webhooks/{id}/secret/rotate", {
        params: { path: { id: hook.id } },
        body: { overlapHours },
      });
      if (result.response.ok && result.data) {
        setSecret(result.data.data.secret);
        setRotating(false);
      }
      return {
        error: result.error,
        response: result.response,
        text: "A new signing secret was made. It is shown once.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const endOverlap = (hook: Webhook) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.POST("/api/v1/webhooks/{id}/secret/end-overlap", {
        params: { path: { id: hook.id } },
      });
      return { error, response, text: "The old signing secret no longer signs." };
    });

  const remove = (hook: Webhook) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.DELETE("/api/v1/webhooks/{id}", { params: { path: { id: hook.id } } });
      setRemoving(undefined);
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The webhook was removed." };
    });

  const dialogs = (
    <>
      {secret ? (
        <SecretDialog
          secret={secret}
          onClose={closeSecret}
          title="Copy the signing secret now"
          label="Signing secret"
          keptAs="It is kept sealed so that the platform can sign with it, and cannot be shown again."
          testId="webhook-secret-dialog"
          inputId="webhook-secret-value"
        />
      ) : null}
      {stopHelp && catalogue ? (
        <Modal title="This webhook has stopped" onClose={() => setStopHelp(false)} testId="webhook-stopped">
          <p>{catalogue.stopped}</p>
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
        <WebhookForm
          key="new"
          title="New webhook"
          submitLabel="Make the webhook"
          catalogue={catalogue}
          busy={busy}
          messages={messages}
          onCancel={() => screen.back()}
          onSubmit={(values) => void make(values)}
        />
        {dialogs}
      </>
    );
  }

  const listed = items?.find((candidate) => candidate.id === openId);
  const item = detail && detail.id === openId ? detail : listed;
  if (openId && items && !listed) {
    return (
      <>
        <MissingDetail listLabel="Webhooks" onBack={() => screen.back()} what="This webhook does not exist any more." />
        {dialogs}
      </>
    );
  }

  if (item && editing && catalogue) {
    return (
      <>
        <WebhookForm
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
    const off = isSwitchedOff(item);
    return (
      <>
        <DetailPage
          listLabel="Webhooks"
          onBack={() => screen.back()}
          title={item.name}
          notices={messages}
          testId="webhook"
          actions={
            offersManage ? (
              <>
                {off ? (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => setEditing(true)}>
                    Change
                  </button>
                ) : null}
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => void test(item)}>
                  Send a test
                </button>
                {off ? (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => void switchTo(item, true)}>
                    Switch on
                  </button>
                ) : (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => void switchTo(item, false)}>
                    Switch off
                  </button>
                )}
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => setRotating(true)}>
                  Make a new signing secret
                </button>
                <button type="button" className="button button-danger" disabled={busy} onClick={() => setRemoving(item)}>
                  Remove
                </button>
              </>
            ) : null
          }
        >
          <DetailSection title="Detail">
            <DetailGrid>
              <DetailItem label="Address" tone="mono" wide>
                {item.address}
              </DetailItem>
              <DetailItem label="State">
                <Tag tone={item.running ? "ok" : "quiet"}>{item.running ? "Running" : "Stopped"}</Tag>{" "}
                <span>{stateWords(item.state)}</span>
                {isFailing(item) ? (
                  <>
                    {" "}
                    <Tag tone="error">Failing</Tag>
                  </>
                ) : null}
              </DetailItem>
              <DetailItem label="Address verified">{item.verified ? "Yes, as it stands now" : "No: send a test"}</DetailItem>
              <DetailItem label="Acts as" tone={item.actsAs ? undefined : "muted"}>
                {item.actsAs ? (item.actsAsYou ? `${item.actsAs} (you)` : item.actsAs) : "A member who left"}
              </DetailItem>
              <DetailItem label="Events">{eventsWords(item.events, catalogue)}</DetailItem>
              <DetailItem label="Objects" tone={item.objects ? undefined : "muted"}>
                {objectsWords(item)}
              </DetailItem>
              <DetailItem label="Made">{whenWords(item.createdAt, "")}</DetailItem>
              <DetailItem label="Last delivered" tone={item.lastSuccessAt ? undefined : "muted"}>
                {whenWords(item.lastSuccessAt, "Never")}
              </DetailItem>
              <DetailItem label="Last failure" tone={item.lastFailureAt ? undefined : "muted"}>
                {whenWords(item.lastFailureAt, "None")}
              </DetailItem>
              {item.failingSince ? <DetailItem label="Failing since">{whenWords(item.failingSince, "")}</DetailItem> : null}
              <DetailItem label="Waiting">{`${item.queue.pending ?? 0} waiting, ${item.queue.deadLettered ?? 0} set aside`}</DetailItem>
            </DetailGrid>
            {stoppedForMember(item) && catalogue ? (
              <p className="hint" data-testid="webhook-stopped-note">
                {catalogue.stopped}{" "}
                <button type="button" className="link-button" onClick={() => setStopHelp(true)}>
                  What to do
                </button>
              </p>
            ) : null}
          </DetailSection>
          <DetailSection title="Signing secret">
            <DetailGrid>
              <DetailItem label="Secret number">{`Secret ${item.secretNumber ?? ""}`.trim()}</DetailItem>
              <DetailItem label="Made">{whenWords(item.secretMadeAt, "")}</DetailItem>
              {item.previousSecretUntil ? (
                <DetailItem label="The old secret also signs until">{whenWords(item.previousSecretUntil, "")}</DetailItem>
              ) : null}
            </DetailGrid>
            {offersManage && item.previousSecretUntil ? (
              <button type="button" className="link-button" disabled={busy} onClick={() => void endOverlap(item)}>
                Stop the old secret now
              </button>
            ) : null}
            {catalogue ? <p className="hint">{catalogue.secretOnce}</p> : null}
          </DetailSection>
          <DetailSection title="Deliveries" stack>
            {catalogue ? <p className="hint">{catalogue.duplicates}</p> : null}
            <WebhookDeliveries
              webhook={item}
              catalogue={catalogue}
              offersReplay={offersReplay}
              onChanged={() => void reload()}
              refreshToken={tick}
            />
          </DetailSection>
        </DetailPage>
        {rotating ? (
          <RotateDialog
            catalogue={catalogue}
            busy={busy}
            onClose={() => setRotating(false)}
            onRotate={(hours) => void rotate(item, hours)}
          />
        ) : null}
        {removing ? (
          <Modal title={`Remove ${removing.name}?`} onClose={() => setRemoving(undefined)} testId="webhook-remove">
            <p>What is waiting is set aside and its signing secrets are removed. It cannot be brought back.</p>
            <div className="form-actions">
              <button type="button" className="button button-secondary" onClick={() => setRemoving(undefined)}>
                Cancel
              </button>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(removing)}>
                Remove the webhook
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
      <ListPage<Webhook>
        title="Webhooks"
        items={items}
        failure={failure}
        rowKey={(row) => row.id}
        primaryHeader="Name"
        primary={(row) => row.name}
        sortPrimary
        columns={[
          { key: "events", header: "Events", kind: "muted", cell: (row) => eventsWords(row.events, catalogue) },
          { key: "actsAs", header: "Acts as", kind: "muted", cell: (row) => row.actsAs ?? "A member who left" },
          { key: "last", header: "Last delivered", kind: "muted", cell: (row) => whenWords(row.lastSuccessAt, "Never") },
          {
            key: "state",
            header: "State",
            kind: "tag",
            cell: (row) => (
              <>
                <Tag tone={row.running ? "ok" : "quiet"}>{row.running ? "Running" : "Stopped"}</Tag>
                {isFailing(row) ? (
                  <>
                    {" "}
                    <Tag tone="error">Failing</Tag>
                  </>
                ) : null}
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
        searchText={(row) => `${row.name} ${row.actsAs ?? ""}`}
        onNew={offersManage && catalogue ? screen.create : undefined}
        newLabel="New webhook"
        notices={messages}
        emptyText="There are no webhooks yet."
        testId="webhooks"
        rowTestId="webhook-row"
      />
      {dialogs}
    </>
  );
}

/** Making a new signing secret: how long the old one keeps signing as well, so that the receiver can change over. */
function RotateDialog({
  catalogue,
  busy,
  onClose,
  onRotate,
}: {
  catalogue: WebhookCatalogue | undefined;
  busy: boolean;
  onClose: () => void;
  onRotate: (overlapHours: number) => void;
}) {
  const [overlap, setOverlap] = useState("0");
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const hours = Number.parseInt(overlap, 10);
    onRotate(Number.isFinite(hours) && hours > 0 ? hours : 0);
  };
  return (
    <Modal title="Make a new signing secret" onClose={onClose} testId="webhook-rotate">
      <form onSubmit={submit} noValidate>
        <p>
          The old secret stops signing at once, unless you give it a time to overlap: during that time every delivery carries
          two signatures and your software accepts either, so it can be changed over without losing a delivery. If the old
          secret leaked, end it now.
        </p>
        <div className="field">
          <label htmlFor="webhook-overlap">Hours the old secret keeps signing</label>
          <input
            id="webhook-overlap"
            type="number"
            min={0}
            max={catalogue?.maxOverlapHours}
            value={overlap}
            onChange={(event) => setOverlap(event.target.value)}
          />
          {catalogue?.maxOverlapHours ? <span className="hint">{`0 ends it now; at most ${catalogue.maxOverlapHours}.`}</span> : null}
        </div>
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
