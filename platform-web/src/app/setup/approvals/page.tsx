import type { Metadata } from "next";
import { ApprovalProcessesPanel } from "@/components/setup/ApprovalProcessesPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Approval processes",
};

/** The approval processes of the organization: what decides a record, step by step, and the way to the requests. */
export default function ApprovalProcessesPage() {
  return (
    <>
      <h1>Approval processes</h1>
      <SetupFrame>
        <ApprovalProcessesPanel />
      </SetupFrame>
    </>
  );
}
