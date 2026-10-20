import type { Metadata } from "next";
import { AuditViewer } from "@/components/audit/AuditViewer";

export const metadata: Metadata = {
  title: "Audit trail",
};

/** What happened in the organization this address belongs to, for members who may view it. */
export default function AuditPage() {
  return <AuditViewer scope="organization" />;
}
