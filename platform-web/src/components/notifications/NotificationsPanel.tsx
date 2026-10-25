"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ageText } from "@/lib/approvals/words";
import { announceChange, safePath } from "@/lib/notifications/model";
import { call } from "@/lib/records/call";
import { ListPage } from "@/components/ui/lists/ListPage";

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
  const router = useRouter();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [notices, setNotices] = useState<Notice[] | undefined>();
  const [next, setNext] = useState<string | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();
  const [explanation, setExplanation] = useState<string | undefined>();
  const [opening, setOpening] = useState(false);

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

  async function open(notice: Notice) {
    if (opening) return;
    setOpening(true);
    setProblem(undefined);
    const outcome = await call(() =>
      api.POST("/api/v1/notifications/{id}/open", { params: { path: { id: notice.id } } }),
    );
    setOpening(false);
    if (!outcome.ok) {
      setProblem(outcome.failure.message);
      return;
    }
    setNotices((known) => known?.map((one) => (one.id === notice.id ? { ...one, read: true } : one)));
    announceChange();
    const target = outcome.data?.data;
    const path = safePath(target?.href);
    if (path && path !== "/notifications") router.push(path);
    else setExplanation(target?.explanation ?? "This notice has no record you can open here.");
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
    <ListPage<Notice>
      title="Notifications" items={notices} rowKey={(notice) => notice.id}
      primaryHeader="Notice" primary={(notice) => notice.title} sortPrimary
      primaryExtra={(notice) => notice.read ? null : <span className="badge">New</span>}
      searchText={(notice) => `${notice.title} ${notice.body}`}
      onOpen={(notice) => void open(notice)}
      columns={[
        { key: "body", header: "Details", cell: (notice) => notice.body, sort: (notice) => notice.body },
        { key: "created", header: "Received", cell: (notice) => whenText(notice.createdAt), sort: (notice) => notice.createdAt },
        { key: "read", header: "Read", cell: (notice) => notice.read ? "Yes" : "No", sort: (notice) => Number(notice.read) },
      ]}
      tools={
        <Link className="button button-secondary" href="/notifications/preferences">
          Preferences
        </Link>
      }
      above={<div className="filters">
        <label>
          <input type="checkbox" checked={unreadOnly} onChange={(event) => setUnreadOnly(event.target.checked)} /> Only
          the unread
        </label>
        <button type="button" className="button button-secondary" disabled={!anyUnread} onClick={() => void markAllRead()}>
          Mark all as read
        </button>
      </div>}
      notices={<>
        {explanation ? <p className="hint" role="status">{explanation}</p> : null}
        {opening ? <p className="hint" role="status">Opening the notice…</p> : null}
        {problem ? (
        <p className="form-message" role="alert" data-testid="notifications-problem">
          {problem}
        </p>
      ) : null}</>}
      below={next ? (
        <button type="button" className="button button-secondary" onClick={() => void load(next)}>
          Show older
        </button>
      ) : null}
      emptyText={unreadOnly ? "Nothing unread." : "Nothing yet. When something needs you, it appears here."}
      testId="notifications" rowTestId="notice"
    />
  );
}
