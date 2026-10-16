import type { Metadata } from "next";
import { SetupFrame } from "@/components/setup/SetupFrame";
import { TabsPanel } from "@/components/setup/TabsPanel";

export const metadata: Metadata = {
  title: "Tabs",
};

/** The tabs of the organization: one per object, and the custom tabs that show a custom page. */
export default function TabsPage() {
  return (
    <>
      <h1>Tabs</h1>
      <SetupFrame>
        <TabsPanel />
      </SetupFrame>
    </>
  );
}
