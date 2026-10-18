import type { Metadata } from "next";
import { PreferencesPanel } from "@/components/notifications/PreferencesPanel";

export const metadata: Metadata = {
  title: "Notification preferences",
};

/** What reaches the signed-in member, and how. */
export default function NotificationPreferencesPage() {
  return <PreferencesPanel />;
}
