"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ageText } from "@/lib/approvals/words";
import { announceChange, safePath } from "@/lib/notifications/model";
import { call } from "@/lib/records/call";

type Notice = components["schemas"]["NotificationView"];

/** How long ago, in words: "just now" or "5 minutes ago". */
function whenText(since: string): string {
  const age = ageText(since);
  return age === "just now" ? age : `${age} ago`;
}

/**
 * The notices of the signed-in member (Sprint 25, ADR-0154): newest first, all or only the unread, a page at a time. Each
 * shows the title and the sentence the API built from the template of its kind (the words are the organization's or the
 * platform's, never this screen's), how long ago it was made, and where it leads. Following a notice opens the inbox or a
 * record; the page it opens decides what the member may see, because a notice grants no access. Marking read is one notice
 * at a time or all of them. The screen decides nothing and holds no kind of notice: it draws what the API answers, and the
 * API's own words for a refusal.
 */
export function NotificationsPanel() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [notices, setNotices] = useState<Notice[] | undefined>();
  const [next, setNext] = useState<string | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  const load = useCallback(
    async (cursor?: string) => {
      const outcome = await call(() =>
        api.GET("/api/v1/notifications", { params: { query: { unread: unreadOnly, cursor } } }),
      );
      if (!outcome.ok) {
        setFailure(outcome.failure.message);
        return;
      }
      setFailure(undefined);
      setNotices((known) => (cursor && known ? [...known, ...outcome.data.data] : outcome.data.data));
      setNext(outcome.data.pagination.hasMore ? outcome.data.pagination.nextCursor : undefined);
    },
    [unreadOnly],
  );

  useEffect(() => {
    void Promise.resolve().then(() => load());
  }, [load]);

  async function markRead(notice: Notice) {
    setProblem(undefined);
    const outcome = await call(() =>
      api.POST("/api/v1/notifications/{id}/read", { params: { path: { id: notice.id } } }),
    );
    if (!outcome.ok) {
      setProblem(outcome.failure.message);
      return;
    }
    setNotices((known) => known?.map((one) => (one.id === notice.id ? { ...one, read: true } : one)));
    announceChange();
  }

  async function markAllRead() {
    setProblem(undefined);
    const outcome = await call(() => api.POST("/api/v1/notifications/read-all"));
    if (!outcome.ok) {
      setProblem(outcome.failure.message);
      return;
    }
    setNotices((known) => known?.map((one) => ({ ...one, read: true })));
    announceChange();
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="notifications-failed">
        {failure}
      </p>
    );
  }
  const anyUnread = notices?.some((one) => !one.read) ?? false;
  return (
    <section aria-labelledby="notifications-heading" className="stack" data-testid="notifications">
      <div className="list-head">
        <h1 id="notifications-heading">Notifications</h1>
        <Link className="button button-secondary" href="/notifications/preferences">
          Preferences
        </Link>
      </div>
      <div className="filters">
        <label>
          <input type="checkbox" checked={unreadOnly} onChange={(event) => setUnreadOnly(event.target.checked)} /> Only
          the unread
        </label>
        <button type="button" className="button button-secondary" disabled={!anyUnread} onClick={() => void markAllRead()}>
          Mark all as read
        </button>
      </div>
      {problem ? (
        <p className="form-message" role="alert" data-testid="notifications-problem">
          {problem}
        </p>
      ) : null}
      {notices === undefined ? <p aria-live="polite">Loading…</p> : null}
      {notices !== undefined && notices.length === 0 ? (
        <p className="empty-state" data-testid="notifications-empty">
          {unreadOnly ? "Nothing unread." : "Nothing yet. When something needs you, it appears here."}
        </p>
      ) : null}
      {notices !== undefined && notices.length > 0 ? (
        <ul className="stack" aria-label="Notifications">
          {notices.map((notice) => {
            const path = safePath(notice.href);
            return (
              <li key={notice.id} className="card" data-testid="notice" data-unread={notice.read ? "false" : "true"}>
                <p>
                  <strong>{notice.title}</strong> {notice.read ? null : <span className="badge">New</span>}
                </p>
                <p>{notice.body}</p>
                <p className="hint">{whenText(notice.createdAt)}</p>
                <div className="filters-actions" role="group" aria-label="Notification actions">
                  {path ? (
                    <Link className="button button-secondary" href={path} onClick={() => void markRead(notice)}>
                      Open
                    </Link>
                  ) : null}
                  {notice.read ? null : (
                    <button type="button" className="link-button" onClick={() => void markRead(notice)}>
                      Mark as read
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
      {next ? (
        <button type="button" className="button button-secondary" onClick={() => void load(next)}>
          Show older
        </button>
      ) : null}
    </section>
  );
}
