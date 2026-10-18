import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { NOTIFICATIONS_CHANGED } from "@/lib/notifications/model";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { NotificationBell } from "./NotificationBell";

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

afterEach(() => {
  vi.unstubAllGlobals();
});

const SUMMARY = "GET /api/v1/notifications/summary";

function memberApi(summary: () => Response, platformHost = false) {
  return fakeApi({
    "GET /api/v1/tenant/current": () =>
      platformHost
        ? refusal("NOT_FOUND", "No organization exists.", 404)
        : json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } }),
    "GET /api/v1/auth/me": () =>
      json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] } }),
    [SUMMARY]: summary,
  });
}

function renderBell() {
  return render(
    <SessionProvider>
      <NotificationBell />
    </SessionProvider>,
  );
}

describe("the bell", () => {
  it("links to the notices with the number of unread ones", async () => {
    memberApi(() => json({ data: { unread: 3 } }));

    renderBell();

    expect(await screen.findByTestId("notification-bell")).toHaveAttribute("href", "/notifications");
    expect(await screen.findByTestId("notification-count")).toHaveTextContent("3");
    expect(screen.getByTestId("notification-bell")).toHaveAccessibleName("Notifications, 3 unread");
  });

  it("shows no number when nothing is unread, and 99+ for a great many", async () => {
    memberApi(() => json({ data: { unread: 0 } }));
    const first = renderBell();
    await screen.findByTestId("notification-bell");
    expect(screen.queryByTestId("notification-count")).not.toBeInTheDocument();
    first.unmount();

    memberApi(() => json({ data: { unread: 250 } }));
    renderBell();
    expect(await screen.findByTestId("notification-count")).toHaveTextContent("99+");
  });

  it("shows the bell with no number and no error when it cannot ask", async () => {
    memberApi(() => refusal("INTERNAL_ERROR", "Something went wrong.", 500));

    renderBell();

    await screen.findByTestId("notification-bell");
    expect(screen.queryByTestId("notification-count")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("asks again when another part of the page says it changed something, and when the window gets focus", async () => {
    let unread = 2;
    const calls = memberApi(() => json({ data: { unread } }));
    renderBell();
    expect(await screen.findByTestId("notification-count")).toHaveTextContent("2");

    unread = 0;
    act(() => {
      window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));
    });
    await waitFor(() => expect(screen.queryByTestId("notification-count")).not.toBeInTheDocument());

    unread = 5;
    act(() => {
      window.dispatchEvent(new Event("focus"));
    });
    expect(await screen.findByTestId("notification-count")).toHaveTextContent("5");
    expect(calls.filter((one) => one.key === SUMMARY).length).toBeGreaterThanOrEqual(3);
  });

  it("is not shown on the platform address, where nobody is a member of an organization", async () => {
    const calls = memberApi(() => json({ data: { unread: 4 } }), true);

    renderBell();

    await waitFor(() => expect(calls.some((one) => one.key === "GET /api/v1/tenant/current")).toBe(true));
    expect(screen.queryByTestId("notification-bell")).not.toBeInTheDocument();
    expect(calls.some((one) => one.key === SUMMARY)).toBe(false);
  });
});
