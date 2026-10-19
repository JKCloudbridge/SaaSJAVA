import type { Metadata } from "next";
import { NotificationsPanel } from "@/components/notifications/NotificationsPanel";

export const metadata: Metadata = {
  title: "Notifications",
};

/** The notices of the signed-in member: what needs them, newest first. */
export default function NotificationsPage() {
  return <NotificationsPanel />;
}
