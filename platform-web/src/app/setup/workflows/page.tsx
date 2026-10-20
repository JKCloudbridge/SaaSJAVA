import type { Metadata } from "next";
import { WorkflowsPanel } from "@/components/setup/WorkflowsPanel";

export const metadata: Metadata = {
  title: "Workflows",
};

/** The workflows of the organization: what starts them, what they do, and the way to their runs (a list; a row opens the builder). */
export default function WorkflowsPage() {
  return <WorkflowsPanel />;
}
