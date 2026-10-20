import type { Metadata } from "next";
import { ApprovalProcessesPanel } from "@/components/setup/ApprovalProcessesPanel";

export const metadata: Metadata = {
  title: "Approval processes",
};

/** The approval processes of the organization: what decides a record, step by step, and the way to the requests (a list; a row opens the builder). */
export default function ApprovalProcessesPage() {
  return <ApprovalProcessesPanel />;
}
