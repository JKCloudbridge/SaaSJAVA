import type { Metadata } from "next";
import { AuditViewer } from "@/components/audit/AuditViewer";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";

export const metadata: Metadata = {
  title: "Platform audit trail",
};

/** What happened on the platform: platform actions, support access, organization status, retention. */
export default function PlatformAuditPage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <h2>Audit trail</h2>
        <AuditViewer scope="platform" />
      </ConsoleFrame>
    </>
  );
}
