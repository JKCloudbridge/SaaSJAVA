/*
 * The model of the notification screens (Sprint 25, ADR-0154). Pure: no React, no network. The browser holds no kind of
 * notice, no category and no channel: the words and the choices come from the API (the notice has its title and body, the
 * preferences list their own labels). What is here is presentation: how to write a count, which address is safe to follow.
 */

/** The number on the bell: nothing for none, the number up to 99, then "99+". */
export function badgeText(unread: number): string {
  if (unread <= 0) {
    return "";
  }
  return unread > 99 ? "99+" : String(unread);
}

/** What a screen reader hears for the bell. */
export function bellLabel(unread: number): string {
  if (unread <= 0) {
    return "Notifications";
  }
  return `Notifications, ${unread} unread`;
}

/**
 * The path a notice leads to, or undefined. The API gives a path on the organization's own host; anything else (an address
 * with a scheme, a path that starts with two slashes) is not followed, so a notice can never send a person elsewhere.
 */
export function safePath(href: string | null | undefined): string | undefined {
  if (typeof href !== "string" || href === "") {
    return undefined;
  }
  if (!href.startsWith("/") || href.startsWith("//") || href.includes("\\")) {
    return undefined;
  }
  return href;
}

/** Tells the other parts of the page that the count of unread notices changed (the bell asks again). */
export const NOTIFICATIONS_CHANGED = "platform-notifications-changed";

export function announceChange(): void {
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));
}
