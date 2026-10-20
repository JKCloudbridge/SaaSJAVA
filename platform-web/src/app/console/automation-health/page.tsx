import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { HealthPanel } from "@/components/automation/HealthPanel";

export const metadata: Metadata = {
  title: "Automation health",
};

/** What waits, what failed and what is overdue in automation across organizations, as counts without any name. */
export default function PlatformAutomationHealthPage() {
  return (
    <ConsoleFrame>
      <HealthPanel scope="platform" />
    </ConsoleFrame>
  );
}
