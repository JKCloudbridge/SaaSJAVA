import type { Metadata } from "next";
import { SetupFrame } from "@/components/setup/SetupFrame";
import { WorkflowsPanel } from "@/components/setup/WorkflowsPanel";

export const metadata: Metadata = {
  title: "Workflows",
};

/** The workflows of the organization: what starts them, what they do, and the way to their runs. */
export default function WorkflowsPage() {
  return (
    <>
      <h1>Workflows</h1>
      <SetupFrame>
        <WorkflowsPanel />
      </SetupFrame>
    </>
  );
}
