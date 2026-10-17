import type { Metadata } from "next";
import { SetupFrame } from "@/components/setup/SetupFrame";
import { WorkflowBuilder } from "@/components/setup/WorkflowBuilder";

export const metadata: Metadata = {
  title: "Workflow",
};

/** The builder of one workflow; the address names its object and it ("new" starts one). */
export default function WorkflowBuilderPage() {
  return (
    <>
      <h1>Workflows</h1>
      <SetupFrame>
        <WorkflowBuilder />
      </SetupFrame>
    </>
  );
}
