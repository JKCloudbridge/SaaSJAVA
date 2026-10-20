import type { Metadata } from "next";
import { Suspense } from "react";
import { TabsPanel } from "@/components/setup/TabsPanel";

export const metadata: Metadata = {
  title: "Tabs",
};

/** The tabs and custom pages of the organization (a list; a custom tab opens in the same pane). */
export default function TabsPage() {
  return (
    <Suspense fallback={null}>
      <TabsPanel />
    </Suspense>
  );
}
