import type { Metadata } from "next";
import { HealthPanel } from "@/components/automation/HealthPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Automation health",
};

/** What waits, what failed and what is overdue in the organization's automation. */
export default function AutomationHealthPage() {
  return (
    <>
      <h1>Automation health</h1>
      <SetupFrame>
        <HealthPanel scope="organization" />
      </SetupFrame>
    </>
  );
}
