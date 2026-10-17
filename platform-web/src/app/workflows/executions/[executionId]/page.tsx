import type { Metadata } from "next";
import { SetupFrame } from "@/components/setup/SetupFrame";
import { ExecutionDetail } from "@/components/workflows/ExecutionDetail";

export const metadata: Metadata = {
  title: "Workflow run",
};

/** One run of a workflow with its steps; the address names it. */
export default function ExecutionPage() {
  return (
    <>
      <h1>Workflow runs</h1>
      <SetupFrame>
        <ExecutionDetail />
      </SetupFrame>
    </>
  );
}
