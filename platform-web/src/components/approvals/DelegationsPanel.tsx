"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { personText } from "@/lib/approvals/words";
import { call } from "@/lib/records/call";
import { useSession } from "@/lib/session/SessionProvider";
import { PersonPicker } from "./PersonPicker";

type Delegation = components["schemas"]["ApprovalDelegationView"];
type Process = components["schemas"]["ApprovalProcessView"];

/** The ability the API lists for managing approvals: it only decides whether the screen offers the extra choices. */
const MANAGE = "approvals.manage";

/** Today as the date input writes it. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Delegations (Sprint 24, ADR-0151): while a delegation is in force, the tasks that would be given to the delegator are also
 * given to the delegate; the delegator keeps them, and tasks already given are not moved. A member makes one for themselves;
 * a member who may manage approvals sees everybody's and makes one for somebody else. The screen holds no rule of the
 * length of a delegation or the number a person may have: the API refuses and says why.
 */
export function DelegationsPanel() {
  const { state } = useSession();
  const manages = state.status === "signedIn" && state.user.abilities.includes(MANAGE);
  const [rows, setRows] = useState<Delegation[] | undefined>();
  const [processes, setProcesses] = useState<Process[]>([]);
  const [everybody, setEverybody] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [delegate, setDelegate] = useState("");
  const [delegateName, setDelegateName] = useState("");
  const [delegator, setDelegator] = useState("");
  const [delegatorName, setDelegatorName] = useState("");
  const [starts, setStarts] = useState(today());
  const [ends, setEnds] = useState(today());
  const [zone, setZone] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
  const [process, setProcess] = useState("");

  const load = useCallback(async () => {
    const outcome = await call(() =>
      api.GET("/api/v1/approvals/delegations", { params: { query: { all: everybody } } }),
    );
    if (!outcome.ok) {
      setFailure(outcome.failure.message);
      return;
    }
    setFailure(undefined);
    setRows(outcome.data.data);
  }, [everybody]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  useEffect(() => {
    if (!manages) {
      return;
    }
    void call(() => api.GET("/api/v1/metadata/approvals")).then((outcome) => {
      if (outcome.ok) {
        setProcesses(outcome.data.data);
      }
    });
  }, [manages]);

  async function create() {
    setBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    const chosen = processes.find((one) => `${one.objectApiName}.${one.apiName}` === process);
    const outcome = await call(() =>
      api.POST("/api/v1/approvals/delegations", {
        body: {
          delegatorId: delegator === "" ? undefined : delegator,
          delegateId: delegate,
          objectApiName: chosen?.objectApiName,
          processApiName: chosen?.apiName,
          startsOn: starts,
          endsOn: ends,
          timeZone: zone.trim() === "" ? "UTC" : zone.trim(),
        },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      setNotice("The delegation was made. Tasks that are given from now on are also given to the delegate.");
      setDelegate("");
      setDelegateName("");
      await load();
    } else {
      setProblem(outcome.failure.message);
    }
  }

  async function revoke(row: Delegation) {
    setBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    const outcome = await call(() =>
      api.DELETE("/api/v1/approvals/delegations/{id}", { params: { path: { id: row.id } } }),
    );
    setBusy(false);
    if (outcome.ok) {
      setNotice("The delegation ended. Tasks the delegate already holds stay.");
      await load();
    } else {
      setProblem(outcome.failure.message);
    }
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="delegations-failed">
        {failure}
      </p>
    );
  }

  return (
    <section aria-labelledby="delegations-heading" className="stack" data-testid="delegations">
      <div className="list-head">
        <h1 id="delegations-heading">Delegations</h1>
        <Link className="button button-secondary" href="/approvals">
          Approvals
        </Link>
      </div>
      <p className="hint">
        While a delegation is in force, the tasks that would be given to the delegator are also given to the delegate. The
        delegator keeps them, so someone who comes back early is not locked out. Tasks that are already given are not moved;
        hand those on yourself. A delegate&apos;s own delegation is not followed.
      </p>

      <form
        className="stack card"
        aria-label="Name a delegate"
        onSubmit={(event) => {
          event.preventDefault();
          void create();
        }}
      >
        {manages ? (
          <PersonPicker
            id="delegation-delegator"
            label="For (leave empty for yourself)"
            value={delegator}
            name={delegatorName}
            onChange={(identifier, name) => {
              setDelegator(identifier);
              setDelegatorName(name);
            }}
          />
        ) : null}
        <PersonPicker
          id="delegation-delegate"
          label="Delegate"
          value={delegate}
          name={delegateName}
          onChange={(identifier, name) => {
            setDelegate(identifier);
            setDelegateName(name);
          }}
        />
        <div className="record-grid record-grid-two">
          <div className="field">
            <label htmlFor="delegation-starts">First day</label>
            <input id="delegation-starts" type="date" value={starts} onChange={(event) => setStarts(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="delegation-ends">Last day (included)</label>
            <input id="delegation-ends" type="date" value={ends} onChange={(event) => setEnds(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="delegation-zone">Time zone of the days</label>
            <input id="delegation-zone" value={zone} onChange={(event) => setZone(event.target.value)} />
          </div>
          {manages ? (
            <div className="field">
              <label htmlFor="delegation-process">Only for one process (optional)</label>
              <select id="delegation-process" value={process} onChange={(event) => setProcess(event.target.value)}>
                <option value="">Every process</option>
                {processes.map((one) => (
                  <option key={`${one.objectApiName}.${one.apiName}`} value={`${one.objectApiName}.${one.apiName}`}>
                    {one.label}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
        </div>
        {problem ? (
          <p className="form-message" role="alert" data-testid="delegations-problem">
            {problem}
          </p>
        ) : null}
        {notice ? (
          <p role="status" data-testid="delegations-notice">
            {notice}
          </p>
        ) : null}
        <div>
          <button type="submit" className="button" disabled={busy || delegate === ""}>
            Name the delegate
          </button>
        </div>
      </form>

      {manages ? (
        <label>
          <input type="checkbox" checked={everybody} onChange={(event) => setEverybody(event.target.checked)} /> Show
          everybody&apos;s delegations
        </label>
      ) : null}

      {!rows ? (
        <p aria-live="polite">Loading…</p>
      ) : rows.length === 0 ? (
        <p data-testid="delegations-empty">No delegations.</p>
      ) : (
        <table className="table" data-testid="delegations-table">
          <thead>
            <tr>
              <th>For</th>
              <th>Delegate</th>
              <th>Days</th>
              <th>Process</th>
              <th>State</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid="delegation-row">
                <td>{personText(row.delegator)}</td>
                <td>{personText(row.delegate)}</td>
                <td>
                  {row.startsOn} to {row.endsOn} ({row.timeZone})
                </td>
                <td>{row.processApiName ?? "Every process"}</td>
                <td>
                  <span className="badge">{row.inForce ? "In force" : "Not in force"}</span>
                </td>
                <td>
                  <button type="button" className="link-button" disabled={busy} onClick={() => void revoke(row)}>
                    End it
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
