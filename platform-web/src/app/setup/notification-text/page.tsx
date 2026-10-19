import type { Metadata } from "next";
import { NotificationTextPanel } from "@/components/setup/NotificationTextPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Notification text",
};

/** The text of the notices and mails the platform sends, which an administrator may rewrite. */
export default function NotificationTextPage() {
  return (
    <>
      <h1>Notification text</h1>
      <SetupFrame>
        <NotificationTextPanel />
      </SetupFrame>
    </>
  );
}
