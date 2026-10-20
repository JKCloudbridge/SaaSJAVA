"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag, type TagTone } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { ChangeSetReport } from "./ChangeSetReport";
import { CHANGE_KIND_TEXT, problemText } from "./objectText";
import { useWorkingSet } from "./workingSet";

type ChangeSet = components["schemas"]["ChangeSetView"];
type Report = components["schemas"]["ChangeSetReportView"];

const STATUS_TEXT: Record<string, string> = { DRAFT: "Open", PUBLISHED: "Published", DISCARDED: "Discarded" };
const STATUS_TONE: Record<string, TagTone> = { DRAFT: "on", PUBLISHED: "ok", DISCARDED: "quiet" };

/**
 * The change sets of the organization, as a list; a row opens the change set: what is in it, and the actions to check it,
 * preview it, publish it or discard it. New starts one. A change set is a group of changes that goes live all together or
 * not at all; nothing in an open one is live. The page shows what the API answers (the report of a check, the words of a
 * refusal) and decides nothing.
 */
export function ChangeSetsPanel() {
  const { id: working, select } = useWorkingSet();
  const [sets, setSets] = useState<ChangeSet[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [detail, setDetail] = useState<ChangeSet | undefined>();
  const [report, setReport] = useState<Report | undefined>();
  const [draft, setDraft] = useState({ name: "", description: "" });
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const offersManage = useOffers("metadata.manage");
  // The row that is open, as reload() needs it without being rebuilt each time it changes.
  const openRef = useRef<string | undefined>(undefined);
  // reload() is called from several places (on mount, when the open row changes, and after every action) and these calls
  // can overlap; a slow one that started earlier must never overwrite what a later one found. Each call takes a ticket,
  // and only the call holding the newest ticket when it finishes is allowed to apply what it read.
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
      const id = openRef.current;
      if (id && list.data.data.some((set) => set.id === id)) {
        const one = await api.GET("/api/v1/metadata/change-sets/{id}", { params: { path: { id } } });
        if (ticket !== latestRequest.current) {
          return;
        }
        setDetail(one.data?.data);
      } else {
        setDetail(undefined);
      }
    } catch {
      if (ticket === latestRequest.current) {
        setFailure(COMMON_TEXT.network);
      }
    }
  }, []);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act, openId } = screen;

  useEffect(() => {
    openRef.current = openId;
    void Promise.resolve().then(() => {
      // Another row is another page: what was shown of the last one goes.
      setReport(undefined);
      setConfirmDiscard(false);
      return reload();
    });
  }, [openId, reload]);

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/metadata/change-sets", { body: draft });
      if (data) {
        setDraft({ name: "", description: "" });
        screen.open(data.data.id);
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
        screen.back(true);
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

  const messages = <ActionMessages notice={notice} problem={problem} testId="change-sets" />;

  if (screen.creating && sets) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Change sets"
        onBack={cancel}
        title="Start a change set"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="change-set-form" className="button" disabled={busy}>
              Start the change set
            </button>
          </>
        }
      >
        <form id="change-set-form" onSubmit={create} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
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
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  if (screen.openId && sets && !sets.some((set) => set.id === screen.openId)) {
    return (
      <MissingDetail listLabel="Change sets" onBack={() => screen.back()} what="This change set does not exist any more." />
    );
  }

  if (screen.openId && sets && !detail) {
    return <p aria-live="polite">Loading…</p>;
  }

  if (detail) {
    const open = detail.status === "DRAFT";
    return (
      <DetailPage
        listLabel="Change sets"
        onBack={() => screen.back()}
        title={detail.name}
        notices={messages}
        testId="change-set"
        actions={
          open ? (
            <>
              {confirmDiscard ? (
                <>
                  <button type="button" className="button button-danger" disabled={busy} onClick={() => discard(detail)}>
                    Yes, discard it
                  </button>
                  <button type="button" className="button button-secondary" onClick={() => setConfirmDiscard(false)}>
                    Cancel
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="button button-secondary"
                  disabled={busy}
                  onClick={() => setConfirmDiscard(true)}
                >
                  Discard it
                </button>
              )}
              {working === detail.id ? (
                <button type="button" className="button button-secondary" onClick={() => select(undefined)}>
                  Stop working in it
                </button>
              ) : (
                <button type="button" className="button button-secondary" onClick={() => select(detail.id)}>
                  Work in it
                </button>
              )}
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => check("validate", detail)}>
                Check it
              </button>
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => check("preview", detail)}>
                Preview it
              </button>
              <button type="button" className="button" disabled={busy} onClick={() => publish(detail)}>
                Publish it
              </button>
            </>
          ) : undefined
        }
      >
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Name">{detail.name}</DetailItem>
            <DetailItem label="Status">
              <Tag tone={STATUS_TONE[detail.status] ?? "quiet"}>{STATUS_TEXT[detail.status] ?? detail.status}</Tag>
              {detail.releaseNumber != null ? ` release ${detail.releaseNumber}` : ""}
            </DetailItem>
            <DetailItem label="Description" wide tone={detail.description ? undefined : "muted"}>
              {detail.description || "—"}
            </DetailItem>
          </DetailGrid>
        </DetailSection>

        <DetailSection title="Changes">
          {detail.changes.length === 0 ? (
            <p>
              No changes yet. Choose this change set on the object pages (&ldquo;Changes on the object pages go&rdquo;) and
              make the changes there.
            </p>
          ) : (
            <ol data-testid="changes">
              {detail.changes.map((change) => (
                <li key={change.id} data-testid="change-row">
                  {CHANGE_KIND_TEXT[change.kind] ?? change.kind}
                  {change.objectApiName ? (
                    <>
                      {" "}
                      <code>{change.objectApiName}</code>
                    </>
                  ) : null}
                  {change.itemApiName ? (
                    <>
                      {" "}
                      <code>{change.itemApiName}</code>
                    </>
                  ) : null}
                  {open ? (
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
        </DetailSection>

        {report ? (
          <DetailSection title="Check and preview">
            <ChangeSetReport report={report} testId="report" />
          </DetailSection>
        ) : null}
      </DetailPage>
    );
  }

  return (
    <ListPage<ChangeSet>
      title="Change sets"
      intro="A change set is a group of changes to objects, fields and record types that goes live all together or not at all. While it is open nothing in it is live. Check it to see every problem, preview it to see the result, then publish it."
      items={sets}
      failure={failure}
      rowKey={(row) => row.id}
      primaryHeader="Change set"
      primary={(row) => row.name}
      primaryExtra={(row) => row.description || null}
      onOpen={(row) => screen.open(row.id)}
      columns={[
        { key: "changes", header: "Changes", kind: "muted", cell: (row) => row.changeCount },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <>
              <Tag tone={STATUS_TONE[row.status] ?? "quiet"}>{STATUS_TEXT[row.status] ?? row.status}</Tag>
              {row.releaseNumber != null ? <span className="row-extra">release {row.releaseNumber}</span> : null}
            </>
          ),
        },
      ]}
      searchText={(row) => `${row.name} ${row.description} ${STATUS_TEXT[row.status] ?? row.status}`}
      onNew={offersManage ? screen.create : undefined}
      newLabel="Start a change set"
      notices={messages}
      emptyText="There are no change sets yet."
      testId="change-sets"
      rowTestId="change-set-row"
    />
  );
}
