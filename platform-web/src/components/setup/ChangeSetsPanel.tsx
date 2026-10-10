"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ChangeSetReport } from "./ChangeSetReport";
import { CHANGE_KIND_TEXT, problemText } from "./objectText";
import { useWorkingSet } from "./workingSet";

type ChangeSet = components["schemas"]["ChangeSetView"];
type Report = components["schemas"]["ChangeSetReportView"];

const STATUS_TEXT: Record<string, string> = { DRAFT: "Open", PUBLISHED: "Published", DISCARDED: "Discarded" };

/**
 * The change sets of the organization: start one, see what is in it, check it, preview it, publish it or discard it. A
 * change set is a group of changes that goes live all together or not at all; nothing in an open one is live. The page
 * shows what the API answers (the report of a check, the words of a refusal) and decides nothing.
 */
export function ChangeSetsPanel() {
  const { id: working, select } = useWorkingSet();
  const [sets, setSets] = useState<ChangeSet[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [chosen, setChosen] = useState<string | undefined>();
  const [detail, setDetail] = useState<ChangeSet | undefined>();
  const [report, setReport] = useState<Report | undefined>();
  const [draft, setDraft] = useState({ name: "", description: "" });
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // reload() is called from several places (on mount, and after every action) and these calls can overlap; a slow
  // one that started earlier must never overwrite what a later one found. Each call takes a ticket, and only the
  // call holding the newest ticket when it finishes is allowed to apply what it read.
  const latestRequest = useRef(0);

  const reload = useCallback(async () => {
    const ticket = ++latestRequest.current;
    try {
      const list = await api.GET("/api/v1/metadata/change-sets");
      if (ticket !== latestRequest.current) {
        return;
      }
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setSets(list.data.data);
      const id = chosen ?? list.data.data[0]?.id;
      if (id && list.data.data.some((set) => set.id === id)) {
        const one = await api.GET("/api/v1/metadata/change-sets/{id}", { params: { path: { id } } });
        if (ticket !== latestRequest.current) {
          return;
        }
        setDetail(one.data?.data);
        setChosen(id);
      } else {
        setDetail(undefined);
      }
    } catch {
      if (ticket === latestRequest.current) {
        setFailure(COMMON_TEXT.network);
      }
    }
  }, [chosen]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function choose(id: string) {
    setChosen(id);
    setReport(undefined);
    setConfirmDiscard(false);
  }

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/metadata/change-sets", { body: draft });
      if (data) {
        setDraft({ name: "", description: "" });
        choose(data.data.id);
      }
      return { error, response, text: "The change set was started.", problem: problemText(error, response) };
    });
  }

  function check(how: "validate" | "preview", set: ChangeSet) {
    void act(async () => {
      const params = { params: { path: { id: set.id } } };
      const { data, error, response } =
        how === "validate"
          ? await api.POST("/api/v1/metadata/change-sets/{id}/validate", params)
          : await api.POST("/api/v1/metadata/change-sets/{id}/preview", params);
      setReport(data?.data);
      return { error, response, text: how === "validate" ? "The change set was checked." : "Here is the preview." };
    });
  }

  function publish(set: ChangeSet) {
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/metadata/change-sets/{id}/publish", {
        params: { path: { id: set.id } },
      });
      if (response.ok) {
        setReport(undefined);
        if (working === set.id) {
          select(undefined);
        }
      }
      return { error, response, text: "The change set was published.", problem: problemText(error, response) };
    });
  }

  function discard(set: ChangeSet) {
    setConfirmDiscard(false);
    void act(async () => {
      const { error, response } = await api.DELETE("/api/v1/metadata/change-sets/{id}", {
        params: { path: { id: set.id } },
      });
      if (response.ok) {
        setReport(undefined);
        if (working === set.id) {
          select(undefined);
        }
      }
      return { error, response, text: "The change set was discarded.", problem: problemText(error, response) };
    });
  }

  function takeOut(set: ChangeSet, changeId: string) {
    void act(async () => {
      const { error, response } = await api.DELETE("/api/v1/metadata/change-sets/{id}/changes/{changeId}", {
        params: { path: { id: set.id, changeId } },
      });
      setReport(undefined);
      return { error, response, text: "The change was taken out.", problem: problemText(error, response) };
    });
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="change-sets-failed">
        {failure}
      </p>
    );
  }
  if (!sets) {
    return <p aria-live="polite">Loading…</p>;
  }

  return (
    <div className="stack">
      <p>
        A change set is a group of changes to objects, fields and record types that goes live all together or not at all.
        While it is open nothing in it is live. Check it to see every problem, preview it to see the result, then publish
        it.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="change-sets" />

      <section aria-labelledby="change-set-form-heading">
        <h2 id="change-set-form-heading">Start a change set</h2>
        <form className="form" onSubmit={create} noValidate>
          <div className="field">
            <label htmlFor="change-set-name">Name</label>
            <input
              id="change-set-name"
              value={draft.name}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="change-set-description">Description</label>
            <input
              id="change-set-description"
              value={draft.description}
              maxLength={500}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>
          <button type="submit" className="button" disabled={busy}>
            Start the change set
          </button>
        </form>
      </section>

      <section aria-labelledby="change-sets-heading">
        <h2 id="change-sets-heading">Change sets</h2>
        {sets.length === 0 ? (
          <p>There are no change sets yet.</p>
        ) : (
          <table className="table" data-testid="change-sets">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Changes</th>
              </tr>
            </thead>
            <tbody>
              {sets.map((set) => (
                <tr key={set.id} data-testid="change-set-row">
                  <td>
                    <button type="button" className="link-button" onClick={() => choose(set.id)}>
                      {set.name}
                    </button>
                  </td>
                  <td>
                    {STATUS_TEXT[set.status] ?? set.status}
                    {set.releaseNumber != null ? <div className="hint">release {set.releaseNumber}</div> : null}
                  </td>
                  <td>{set.changeCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {detail ? (
        <section aria-labelledby="change-set-heading" data-testid="change-set">
          <h2 id="change-set-heading">{detail.name}</h2>
          {detail.description ? <p>{detail.description}</p> : null}
          <p>{STATUS_TEXT[detail.status] ?? detail.status}.</p>
          {detail.changes.length === 0 ? (
            <p>
              No changes yet. Choose this change set on the object pages (&ldquo;Changes on the object pages go&rdquo;) and
              make the changes there.
            </p>
          ) : (
            <ol data-testid="changes">
              {detail.changes.map((change) => (
                <li key={change.id} data-testid="change-row">
                  {CHANGE_KIND_TEXT[change.kind] ?? change.kind} <code>{change.objectApiName}</code>
                  {change.itemApiName ? (
                    <>
                      {" "}
                      <code>{change.itemApiName}</code>
                    </>
                  ) : null}
                  {detail.status === "DRAFT" ? (
                    <>
                      {" "}
                      <button type="button" className="link-button" disabled={busy} onClick={() => takeOut(detail, change.id)}>
                        Take out
                      </button>
                    </>
                  ) : null}
                </li>
              ))}
            </ol>
          )}
          {detail.status === "DRAFT" ? (
            <p>
              <button type="button" className="button" disabled={busy} onClick={() => check("validate", detail)}>
                Check it
              </button>{" "}
              <button type="button" className="button" disabled={busy} onClick={() => check("preview", detail)}>
                Preview it
              </button>{" "}
              <button type="button" className="button" disabled={busy} onClick={() => publish(detail)}>
                Publish it
              </button>{" "}
              {working === detail.id ? (
                <button type="button" className="link-button" onClick={() => select(undefined)}>
                  Stop working in it
                </button>
              ) : (
                <button type="button" className="link-button" onClick={() => select(detail.id)}>
                  Work in it
                </button>
              )}{" "}
              {confirmDiscard ? (
                <>
                  <button type="button" className="button" disabled={busy} onClick={() => discard(detail)}>
                    Yes, discard it
                  </button>{" "}
                  <button type="button" className="link-button" onClick={() => setConfirmDiscard(false)}>
                    Cancel
                  </button>
                </>
              ) : (
                <button type="button" className="link-button" disabled={busy} onClick={() => setConfirmDiscard(true)}>
                  Discard it
                </button>
              )}
            </p>
          ) : null}
          {report ? <ChangeSetReport report={report} testId="report" /> : null}
        </section>
      ) : null}
    </div>
  );
}
