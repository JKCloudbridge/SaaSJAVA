import type { Metadata } from "next";
import { ExecutionDetail } from "@/components/workflows/ExecutionDetail";

export const metadata: Metadata = {
  title: "Workflow run",
};

/** One run of a workflow with its steps; the address names it. */
export default function ExecutionPage() {
  return <ExecutionDetail />;
}
