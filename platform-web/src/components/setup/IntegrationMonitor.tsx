"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailSection } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { Modal } from "@/components/ui/Modal";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import type { Integration } from "@/lib/integrations/model";
import { useOffers } from "@/lib/session/SessionProvider";

type Health = components["schemas"]["IntegrationHealthView"];
type Circuit = components["schemas"]["CircuitView"];
type CircuitAction = components["schemas"]["CircuitAction"];
type Delivery = components["schemas"]["DeliveryView"];
type Attempt = components["schemas"]["AttemptView"];

/** What the person is asked to confirm: the API's own sentence about what will happen, and what to do on yes. */
type Confirmation = { title: string; effect: string; confirm: string; run: () => Promise<void> };

const when = (value: string | null | undefined) => (value ? new Date(value).toLocaleString() : "—");

/**
 * How the flows of one connection stand (Sprint 29, ADR-0235): the circuit breaker, the queue by what it holds, the
 * limits on sending, the changes that wait and the ones set aside with the attempts made for each. It draws what the API
 * answers in the API's words and decides nothing: the actions on the circuit, the right to send a change again and the
 * words for every state, reason and refusal come from the answers, and the browser holds no list of them. Anything that
 * changes something asks first, in the API's own sentence about what happens.
 */
export function IntegrationMonitor({ connection }: { connection: Integration }) {
  const offersOperate = useOffers("integrations.operate");
  const [health, setHealth] = useState<Health | undefined>();
  const [waiting, setWaiting] = useState<Delivery[]>([]);
  const [setAside, setSetAside] = useState<Delivery[]>([]);
  const [attempts, setAttempts] = useState<Record<string, Attempt[]>>({});
  const [open, setOpen] = useState<string | undefined>();
  const [confirmation, setConfirmation] = useState<Confirmation | undefined>();
  const [rate, setRate] = useState("");
  const [concurrent, setConcurrent] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  const name = connection.name;

  const load = useCallback(async () => {
    try {
      const [state, queued, aside] = await Promise.all([
        api.GET("/api/v1/integrations/{name}/health", { params: { path: { name } } }),
        api.GET("/api/v1/integrations/{name}/deliveries", {
          params: { path: { name }, query: { limit: 10, state: ["PENDING", "SENDING", "WAITING_REMOTE"] } },
        }),
        api.GET("/api/v1/integrations/{name}/deliveries", {
          params: { path: { name }, query: { limit: 20, state: ["DEAD_LETTERED"] } },
        }),
      ]);
      if (!state.data) {
        setProblem(await failureText(state.error, state.response));
        return;
      }
      setHealth(state.data.data);
      setRate(String(state.data.data.limits.ratePerMinute));
      setConcurrent(String(state.data.data.limits.maxConcurrent));
      setWaiting(queued.data?.data ?? []);
      setSetAside(aside.data?.data ?? []);
      setProblem(undefined);
    } catch {
      setProblem(COMMON_TEXT.network);
    }
  }, [name]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  /** Runs one change after the person confirmed it; the API's words are shown whatever it answers. */
  const perform = async (work: () => Promise<{ ok: boolean; text: string }>) => {
    setBusy(true);
    setNotice(undefined);
    setProblem(undefined);
    try {
      const result = await work();
      await load();
      if (result.ok) {
        setNotice(result.text);
      } else {
        setProblem(result.text);
      }
    } catch {
      setProblem(COMMON_TEXT.network);
    } finally {
      setBusy(false);
      setConfirmation(undefined);
    }
  };

  const act = (action: CircuitAction) =>
    setConfirmation({
      title: action.label,
      effect: action.effect,
      confirm: action.label,
      run: () =>
        perform(async () => {
          const done = await api.POST("/api/v1/integrations/{name}/circuit", {
            params: { path: { name } },
            body: { action: action.key },
          });
          return done.data ? { ok: true, text: done.data.data.words } : { ok: false, text: await failureText(done.error, done.response) };
        }),
    });

  const replayOne = (delivery: Delivery) =>
    setConfirmation({
      title: "Send again",
      effect: "The change is read again as it is now and put back in the queue. What was already done on the other side is not repeated.",
      confirm: "Send again",
      run: () =>
        perform(async () => {
          const done = await api.POST("/api/v1/integrations/{name}/deliveries/{delivery}/replay", {
            params: { path: { name, delivery: delivery.id } },
          });
          return done.data
            ? { ok: true, text: "It is back in the queue." }
            : { ok: false, text: await failureText(done.error, done.response) };
        }),
    });

  const replayAll = () =>
    setConfirmation({
      title: "Send all again",
      effect: "The oldest changes that were set aside, up to 100, are read again as they are now and put back in the queue.",
      confirm: "Send all again",
      run: () =>
        perform(async () => {
          const done = await api.POST("/api/v1/integrations/{name}/deliveries/replay", {
            params: { path: { name } },
            body: {},
          });
          return done.data
            ? { ok: true, text: `${done.data.data.replayed} put back in the queue, ${done.data.data.remaining} still set aside.` }
            : { ok: false, text: await failureText(done.error, done.response) };
        }),
    });

  const saveLimits = (event: FormEvent) => {
    event.preventDefault();
    void perform(async () => {
      const done = await api.PUT("/api/v1/integrations/{name}/limits", {
        params: { path: { name } },
        body: { ratePerMinute: Number(rate), maxConcurrent: Number(concurrent) },
      });
      return done.data ? { ok: true, text: "The limits are saved." } : { ok: false, text: await failureText(done.error, done.response) };
    });
  };

  const showAttempts = async (delivery: Delivery) => {
    if (open === delivery.id) {
      setOpen(undefined);
      return;
    }
    setOpen(delivery.id);
    if (attempts[delivery.id]) {
      return;
    }
    const read = await api.GET("/api/v1/integrations/{name}/deliveries/{delivery}/attempts", {
      params: { path: { name, delivery: delivery.id } },
    });
    if (read.data) {
      setAttempts((known) => ({ ...known, [delivery.id]: read.data?.data ?? [] }));
    } else {
      setProblem(await failureText(read.error, read.response));
    }
  };

  const circuit: Circuit | undefined = health?.circuit;
  const canReplayAny = setAside.some((one) => one.canReplay);

  return (
    <DetailSection title="Monitoring">
      <div className="stack" data-testid="integration-monitor">
        {problem ? (
          <p className="form-message" role="alert">
            {problem}
          </p>
        ) : null}
        {notice ? <p role="status">{notice}</p> : null}
        {!health ? <p className="dl-value muted">Loading…</p> : null}
        {health && circuit ? (
          <>
            <DetailGrid>
              <DetailItem label="Sending">
                <span data-testid="monitor-circuit">{circuit.words}</span>
              </DetailItem>
              <DetailItem label="Waiting to be sent">{health.waiting}</DetailItem>
              <DetailItem label="Being sent now">{health.sending}</DetailItem>
              <DetailItem label="Waiting for the service">{health.waitingRemote}</DetailItem>
              <DetailItem label="Set aside">
                <Tag tone={health.deadLettered > 0 ? "error" : "quiet"}>{health.deadLettered}</Tag>
              </DetailItem>
              <DetailItem label="Sent">{health.done}</DetailItem>
              <DetailItem label="Longest wait began">{when(health.oldestOpenSince)}</DetailItem>
              <DetailItem label="Last success">{when(circuit.lastSuccessAt)}</DetailItem>
              <DetailItem label="Last failure">
                {circuit.lastFailureAt ? `${when(circuit.lastFailureAt)}${circuit.lastFailureWords ? `: ${circuit.lastFailureWords}` : ""}` : "—"}
              </DetailItem>
              <DetailItem label="Send rate">
                {health.limits.ratePerMinute} a minute, {health.limits.maxConcurrent} at once
              </DetailItem>
            </DetailGrid>
            {health.files ? (
              <DetailGrid>
                <DetailItem label="Inbox last looked at">{when(health.files.lastLookAt)}</DetailItem>
                <DetailItem label="What the last look found">
                  <span data-testid="monitor-files-reason">{health.files.lastReasonWords ?? "Nothing wrong."}</span>
                </DetailItem>
                <DetailItem label="Next look">{when(health.files.nextLookAt)}</DetailItem>
                <DetailItem label="Files waiting in the inbox">{health.files.filesWaiting}</DetailItem>
                <DetailItem label="Files being applied">{health.files.filesInProgress}</DetailItem>
                <DetailItem label="Files applied">{health.files.filesDone}</DetailItem>
                <DetailItem label="Files set aside">
                  <Tag tone={health.files.filesSetAside > 0 ? "error" : "quiet"}>{health.files.filesSetAside}</Tag>
                </DetailItem>
              </DetailGrid>
            ) : null}
            <div className="split">
              {circuit.actions.map((action) => (
                <button key={action.key} type="button" className="button button-secondary" disabled={busy} onClick={() => act(action)}>
                  {action.label}
                </button>
              ))}
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => void load()}>
                Refresh
              </button>
            </div>
            {offersOperate ? (
              <form className="form" onSubmit={saveLimits} aria-label="Limits on sending">
                <div className="split">
                  <label className="field">
                    <span>Sends a minute (at most {health.limits.ratePerMinuteCeiling})</span>
                    <input type="number" min={1} max={health.limits.ratePerMinuteCeiling} value={rate} onChange={(e) => setRate(e.target.value)} />
                  </label>
                  <label className="field">
                    <span>Sends at once (at most {health.limits.maxConcurrentCeiling})</span>
                    <input type="number" min={1} max={health.limits.maxConcurrentCeiling} value={concurrent} onChange={(e) => setConcurrent(e.target.value)} />
                  </label>
                </div>
                <button type="submit" className="button button-secondary" disabled={busy}>
                  Save limits
                </button>
              </form>
            ) : null}
          </>
        ) : null}

        <h3>Waiting</h3>
        {health && waiting.length === 0 ? <p className="dl-value muted">Nothing is waiting.</p> : null}
        <ul data-testid="monitor-waiting">
          {waiting.map((one) => (
            <li key={one.id}>
              {one.kind.toLowerCase()} ({one.object}): {one.waitingWords ?? "Ready to be sent."}
              {one.nextAttemptAt ? ` Next look: ${when(one.nextAttemptAt)}.` : ""}
              {one.attempts > 0 ? ` Attempts so far: ${one.attempts}.` : ""}
            </li>
          ))}
        </ul>

        <h3>Set aside</h3>
        {health && setAside.length === 0 ? <p className="dl-value muted">Nothing is set aside.</p> : null}
        {canReplayAny ? (
          <div className="split">
            <button type="button" className="button button-secondary" disabled={busy} onClick={replayAll}>
              Send all again
            </button>
          </div>
        ) : null}
        <ul data-testid="monitor-set-aside">
          {setAside.map((one) => (
            <li key={one.id}>
              <span>
                {one.kind.toLowerCase()} ({one.object}): {one.words ?? "Set aside."} Attempts: {one.attempts}. {when(one.finishedAt)}
              </span>{" "}
              <button type="button" className="link-button" onClick={() => void showAttempts(one)} aria-expanded={open === one.id}>
                Attempts
              </button>{" "}
              {one.canReplay ? (
                <button type="button" className="link-button" disabled={busy} onClick={() => replayOne(one)}>
                  Send again
                </button>
              ) : null}
              {open === one.id ? (
                <ul aria-label="Attempts">
                  {(attempts[one.id] ?? []).map((attempt) => (
                    <li key={`${attempt.generation}-${attempt.attempt}-${attempt.outcome}`}>
                      #{attempt.attempt}: {attempt.words ?? attempt.outcome.toLowerCase()} ({attempt.statusClass}, {attempt.durationMs} ms,{" "}
                      {when(attempt.startedAt)})
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ul>
      </div>
      {confirmation ? (
        <Modal title={confirmation.title} onClose={() => setConfirmation(undefined)} testId="monitor-confirmation">
          <div className="stack modal-body">
            <p>{confirmation.effect}</p>
            <div className="split">
              <button type="button" className="button" disabled={busy} onClick={() => void confirmation.run()}>
                {confirmation.confirm}
              </button>
              <button type="button" className="button button-secondary" onClick={() => setConfirmation(undefined)}>
                Cancel
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </DetailSection>
  );
}
