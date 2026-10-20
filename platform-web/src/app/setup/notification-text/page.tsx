import type { Metadata } from "next";
import { Suspense } from "react";
import { NotificationTextPanel } from "@/components/setup/NotificationTextPanel";

export const metadata: Metadata = {
  title: "Notification text",
};

/** The text of the notices and mails the platform sends, which an administrator may rewrite (a list of kinds; a row opens in the same pane). */
export default function NotificationTextPage() {
  return (
    <Suspense fallback={null}>
      <NotificationTextPanel />
    </Suspense>
  );
}
