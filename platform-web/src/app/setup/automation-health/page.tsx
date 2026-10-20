import type { Metadata } from "next";
import { HealthPanel } from "@/components/automation/HealthPanel";

export const metadata: Metadata = {
  title: "Automation health",
};

/** What waits, what failed and what is overdue in the organization's automation. */
export default function AutomationHealthPage() {
  return <HealthPanel scope="organization" />;
}
