import type { Metadata } from "next";
import { WorkflowBuilder } from "@/components/setup/WorkflowBuilder";

export const metadata: Metadata = {
  title: "Workflow",
};

/** The builder of one workflow; the address names its object and it ("new" starts one). */
export default function WorkflowBuilderPage() {
  return <WorkflowBuilder />;
}
