import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { AuditViewer } from "@/components/audit/AuditViewer";

export const metadata: Metadata = {
  title: "Platform audit trail",
};

/** What happened on the platform: platform actions, support access, organization status, retention. */
export default function PlatformAuditPage() {
  return (
    <ConsoleFrame>
      <AuditViewer scope="platform" />
    </ConsoleFrame>
  );
}
