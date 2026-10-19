import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { NOTIFICATIONS_CHANGED } from "@/lib/notifications/model";
import { NotificationsPanel } from "./NotificationsPanel";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = "GET /api/v1/notifications";
const LAST = { limit: 30, hasMore: false };

function notice(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    kind: "APPROVAL_TASK",
    category: "APPROVALS",
    title: "A record needs your decision",
    body: "A record of Expense waits for your decision in the approval Large expense.",
    href: "/approvals",
    read: false,
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    ...extra,
  };
}

describe("the notices of the member", () => {
  it("draws the words the API built, how long ago, and where each leads", async () => {
    fakeApi({ [LIST]: () => json({ data: [notice("n-1"), notice("n-2", { href: null, read: true })], pagination: LAST }) });

    render(<NotificationsPanel />);

    const rows = await screen.findAllByTestId("notice");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("A record needs your decision");
    expect(rows[0]).toHaveTextContent("waits for your decision in the approval Large expense");
    expect(rows[0]).toHaveTextContent("5 minutes ago");
    expect(within(rows[0]!).getByRole("link", { name: "Open" })).toHaveAttribute("href", "/approvals");
    expect(rows[0]).toHaveAttribute("data-unread", "true");
    expect(rows[1]).toHaveAttribute("data-unread", "false");
    expect(within(rows[1]!).queryByRole("link", { name: "Open" })).not.toBeInTheDocument();
  });

  it("follows no address that leaves the organization's own pages", async () => {
    fakeApi({
      [LIST]: () =>
        json({ data: [notice("n-1", { href: "https://elsewhere.example.test/x" }), notice("n-2", { href: "//x" })], pagination: LAST }),
    });

    render(<NotificationsPanel />);

    await screen.findAllByTestId("notice");
    expect(screen.queryByRole("link", { name: "Open" })).not.toBeInTheDocument();
  });

  it("marks one notice read and tells the bell", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [notice("n-1")], pagination: LAST }),
      "POST /api/v1/notifications/n-1/read": () => new Response(null, { status: 204 }),
    });
    const changed = vi.fn();
    window.addEventListener(NOTIFICATIONS_CHANGED, changed);

    render(<NotificationsPanel />);
    const row = await screen.findByTestId("notice");
    fireEvent.click(within(row).getByRole("button", { name: "Mark as read" }));

    await waitFor(() => expect(row).toHaveAttribute("data-unread", "false"));
    expect(calls.some((one) => one.key === "POST /api/v1/notifications/n-1/read")).toBe(true);
    expect(changed).toHaveBeenCalled();
    window.removeEventListener(NOTIFICATIONS_CHANGED, changed);
  });

  it("marks them all read", async () => {
    const calls = fakeApi({
      [LIST]: () => json({ data: [notice("n-1"), notice("n-2")], pagination: LAST }),
      "POST /api/v1/notifications/read-all": () => new Response(null, { status: 204 }),
    });

    render(<NotificationsPanel />);
    await screen.findAllByTestId("notice");
    fireEvent.click(screen.getByRole("button", { name: "Mark all as read" }));

    await waitFor(() => expect(screen.getAllByTestId("notice").every((one) => one.dataset.unread === "false")).toBe(true));
    expect(calls.some((one) => one.key === "POST /api/v1/notifications/read-all")).toBe(true);
    expect(screen.getByRole("button", { name: "Mark all as read" })).toBeDisabled();
  });

  it("asks only for the unread ones when told to, and pages with the cursor", async () => {
    const queries: string[] = [];
    fakeApi({
      [LIST]: (request) => {
        const url = new URL(request.url);
        queries.push(url.search);
        return url.searchParams.get("cursor")
          ? json({ data: [notice("n-2")], pagination: LAST })
          : json({ data: [notice("n-1")], pagination: { limit: 30, hasMore: true, nextCursor: "c-1" } });
      },
    });

    render(<NotificationsPanel />);
    await screen.findByTestId("notice");
    fireEvent.click(screen.getByRole("button", { name: "Show older" }));
    await waitFor(() => expect(screen.getAllByTestId("notice")).toHaveLength(2));
    fireEvent.click(screen.getByLabelText(/Only the unread/));

    await waitFor(() => expect(queries.some((one) => one.includes("unread=true"))).toBe(true));
    expect(queries.some((one) => one.includes("cursor=c-1"))).toBe(true);
  });

  it("says so when there is nothing, and shows the API's words for a refusal", async () => {
    fakeApi({ [LIST]: () => json({ data: [], pagination: LAST }) });
    const first = render(<NotificationsPanel />);
    expect(await screen.findByTestId("notifications-empty")).toHaveTextContent("Nothing yet");
    first.unmount();

    fakeApi({ [LIST]: () => refusal("NOT_FOUND", "This does not exist.", 404) });
    render(<NotificationsPanel />);
    expect(await screen.findByTestId("notifications-failed")).toHaveTextContent("This does not exist.");
  });
});
