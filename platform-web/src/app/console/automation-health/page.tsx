import type { Metadata } from "next";
import { HealthPanel } from "@/components/automation/HealthPanel";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";

export const metadata: Metadata = {
  title: "Automation health",
};

/** What waits, what failed and what is overdue in automation across organizations, as counts without any name. */
export default function PlatformAutomationHealthPage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <HealthPanel scope="platform" />
      </ConsoleFrame>
    </>
  );
}
