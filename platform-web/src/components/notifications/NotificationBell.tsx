"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Icon } from "@/components/ui/Icon";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { api } from "@/lib/api/client";
import { badgeText, bellLabel, NOTIFICATIONS_CHANGED } from "@/lib/notifications/model";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The bell in the header (Sprint 25, ADR-0154): a link to the member's notices with the number of unread ones. The count
 * is one cheap question to the API, asked when the page changes, when the window gets focus again and when another part of
 * the page says it changed something; it never blocks anything, and a failure to ask shows the bell with no number and no
 * error (nothing is lost by it, the list answers for itself). Only a signed-in member of an organization has a bell.
 */
export function NotificationBell() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const pathname = usePathname() ?? "";
  const [unread, setUnread] = useState(0);
  const member = state.status === "signedIn" && platform.isPlatformHost === false;

  const ask = useCallback(async () => {
    try {
      const { data } = await api.GET("/api/v1/notifications/summary");
      setUnread(data?.data.unread ?? 0);
    } catch {
      // The bell stays as it was.
    }
  }, []);

  useEffect(() => {
    if (!member) {
      return undefined;
    }
    void Promise.resolve().then(ask);
    const onChange = () => void ask();
    window.addEventListener("focus", onChange);
    window.addEventListener(NOTIFICATIONS_CHANGED, onChange);
    return () => {
      window.removeEventListener("focus", onChange);
      window.removeEventListener(NOTIFICATIONS_CHANGED, onChange);
    };
  }, [member, ask, pathname]);

  if (!member) {
    return null;
  }
  const badge = badgeText(unread);
  return (
    <Link
      href="/notifications"
      className="bell"
      aria-label={bellLabel(unread)}
      aria-current={pathname.startsWith("/notifications") ? "page" : undefined}
      data-testid="notification-bell"
    >
      <Icon name="bell" className="nav-icon" />
      {badge === "" ? null : (
        <span className="badge" data-testid="notification-count">
          {badge}
        </span>
      )}
    </Link>
  );
}
