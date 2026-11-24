"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import {
  canReplay,
  deliveryStateWords,
  whenWords,
  type Webhook,
  type WebhookAttempt,
  type WebhookCatalogue,
  type WebhookDelivery,
} from "@/lib/webhooks/model";

interface Props {
  webhook: Webhook;
  catalogue: WebhookCatalogue | undefined;
  /** Whether the API's list of the member's abilities allows a replay; hides the buttons only when it rules it out. */
  offersReplay: boolean;
  /** Called after a replay, so the page reads the webhook again. */
  onChanged: () => void;
  /** Changes whenever the page read the webhook again, so the log is read again too. */
  refreshToken: number;
}

/**
 * The delivery log of one webhook (Sprint 32, ADR-0278): newest first, filtered by a state from the catalogue, read on a
 * page at a time, with the attempts of a delivery on request and the replay of one delivery or of every delivery that was set
 * aside. It shows identifiers, states and the platform's own sentences; it never shows a payload, a header or an answer,
 * because the API has none to give, and it decides nothing: a delivery that cannot be replayed answers in the API's words.
 */
export function WebhookDeliveries({ webhook, catalogue, offersReplay, onChanged, refreshToken }: Props) {
  const [filter, setFilter] = useState("");
  const [items, setItems] = useState<WebhookDelivery[] | undefined>();
  const [next, setNext] = useState<string | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [attempts, setAttempts] = useState<Record<string, WebhookAttempt[] | undefined>>({});
  const [busy, setBusy] = useState(false);

  const read = useCallback(
    async (before: string | undefined) => {
      try {
        const answer = await api.GET("/api/v1/webhooks/{id}/deliveries", {
          params: { path: { id: webhook.id }, query: { state: filter ? [filter] : undefined, before, limit: 25 } },
        });
        if (!answer.data) {
          setFailure(await failureText(answer.error, answer.response));
          return;
        }
        setFailure(undefined);
        setItems((current) => (before && current ? [...current, ...answer.data.data.items] : answer.data.data.items));
        setNext(answer.data.data.next ?? undefined);
      } catch {
        setFailure(COMMON_TEXT.network);
      }
    },
    [webhook.id, filter],
  );

  useEffect(() => {
    void Promise.resolve().then(() => read(undefined));
  }, [read, refreshToken]);

  const showAttempts = async (delivery: WebhookDelivery) => {
    if (attempts[delivery.id]) {
      setAttempts((current) => ({ ...current, [delivery.id]: undefined }));
      return;
    }
    try {
      const answer = await api.GET("/api/v1/webhooks/{id}/deliveries/{delivery}/attempts", {
        params: { path: { id: webhook.id, delivery: delivery.id } },
      });
      if (answer.data) {
        setAttempts((current) => ({ ...current, [delivery.id]: answer.data.data }));
      } else {
        setFailure(await failureText(answer.error, answer.response));
      }
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  };

  const replay = async (delivery: WebhookDelivery | undefined) => {
    setBusy(true);
    setNotice(undefined);
    try {
      const answer = delivery
        ? await api.POST("/api/v1/webhooks/{id}/deliveries/{delivery}/replay", {
            params: { path: { id: webhook.id, delivery: delivery.id } },
          })
        : await api.POST("/api/v1/webhooks/{id}/deliveries/replay", { params: { path: { id: webhook.id } } });
      if (answer.data) {
        const count = answer.data.data.replayed ?? 0;
        setNotice(
          count === 0
            ? "Nothing was put back: it had been sent again already."
            : `${count} ${count === 1 ? "delivery was" : "deliveries were"} put back in the queue.${
                answer.data.data.more ? " More remain: send them again to continue." : ""
              }`,
        );
        setFailure(undefined);
        onChanged();
        await read(undefined);
      } else {
        setFailure(await failureText(answer.error, answer.response));
      }
    } catch {
      setFailure(COMMON_TEXT.network);
    } finally {
      setBusy(false);
    }
  };

  const setAside = webhook.queue.deadLettered ?? 0;

  return (
    <div data-testid="webhook-deliveries">
      <div className="filters">
        <div className="field">
          <label htmlFor="webhook-delivery-filter">Show</label>
          <select id="webhook-delivery-filter" value={filter} onChange={(event) => setFilter(event.target.value)}>
            <option value="">Every delivery</option>
            {(catalogue?.deliveryStates ?? []).map((one) => (
              <option key={one.key} value={one.key}>
                {one.title}
              </option>
            ))}
          </select>
        </div>
        {offersReplay && setAside > 0 ? (
          <button type="button" className="button button-secondary" disabled={busy} onClick={() => void replay(undefined)}>
            {`Send the ${setAside} that were set aside again`}
          </button>
        ) : null}
      </div>
      {failure ? (
        <p className="form-message" role="alert">
          {failure}
        </p>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
      {!items ? (
        <p className="hint">Loading the deliveries…</p>
      ) : items.length === 0 ? (
        <p className="hint" data-testid="webhook-deliveries-empty">
          There are no deliveries to show.
        </p>
      ) : (
        <table className="table" data-testid="webhook-delivery-table">
          <thead>
            <tr>
              <th scope="col">When</th>
              <th scope="col">Event</th>
              <th scope="col">State</th>
              <th scope="col">Why</th>
              <th scope="col">Attempts</th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((delivery) => (
              <DeliveryRow
                key={delivery.id}
                delivery={delivery}
                catalogue={catalogue}
                attempts={attempts[delivery.id]}
                busy={busy}
                offersReplay={offersReplay}
                onAttempts={() => void showAttempts(delivery)}
                onReplay={() => void replay(delivery)}
              />
            ))}
          </tbody>
        </table>
      )}
      {next ? (
        <button type="button" className="link-button" onClick={() => void read(next)}>
          Show older deliveries
        </button>
      ) : null}
    </div>
  );
}

function DeliveryRow({
  delivery,
  catalogue,
  attempts,
  busy,
  offersReplay,
  onAttempts,
  onReplay,
}: {
  delivery: WebhookDelivery;
  catalogue: WebhookCatalogue | undefined;
  attempts: WebhookAttempt[] | undefined;
  busy: boolean;
  offersReplay: boolean;
  onAttempts: () => void;
  onReplay: () => void;
}) {
  const event = catalogue?.events.find((one) => one.key === delivery.eventType)?.title ?? delivery.eventType;
  return (
    <>
      <tr data-testid="webhook-delivery">
        <td>{whenWords(delivery.createdAt, "")}</td>
        <td>{event}</td>
        <td>{deliveryStateWords(delivery.state, catalogue)}</td>
        <td>{delivery.reasonWords ?? delivery.queuedReasonWords ?? ""}</td>
        <td>{`${delivery.attempts ?? 0}${(delivery.generation ?? 1) > 1 ? ` (send ${delivery.generation})` : ""}`}</td>
        <td>
          <button type="button" className="link-button" onClick={onAttempts}>
            {attempts ? "Hide attempts" : "Attempts"}
          </button>
          {offersReplay && canReplay(delivery) ? (
            <>
              {" "}
              <button type="button" className="link-button" disabled={busy} onClick={onReplay}>
                Send again
              </button>
            </>
          ) : null}
        </td>
      </tr>
      {attempts ? (
        <tr data-testid="webhook-attempts">
          <td colSpan={6}>
            {attempts.length === 0 ? (
              <span className="hint">No attempt was made.</span>
            ) : (
              <ul>
                {attempts.map((attempt) => (
                  <li key={`${attempt.generation}-${attempt.attemptNo}-${attempt.outcome}`}>
                    {`Attempt ${attempt.attemptNo} of send ${attempt.generation}: ${whenWords(attempt.startedAt, "")}, ${
                      attempt.durationMs ?? 0
                    } ms, ${attempt.outcome.toLowerCase()}${attempt.httpStatus ? `, answer ${attempt.httpStatus}` : ""}${
                      attempt.reasonWords ? `. ${attempt.reasonWords}` : ""
                    }`}
                  </li>
                ))}
              </ul>
            )}
          </td>
        </tr>
      ) : null}
    </>
  );
}
