import type { Metadata } from "next";
import { Suspense } from "react";
import { IntegrationsPanel } from "@/components/setup/IntegrationsPanel";

export const metadata: Metadata = { title: "Integrations" };

/** The organization's connections to external systems (Sprint 26). The open row is kept in the address. */
export default function IntegrationsPage() {
  return (
    <Suspense fallback={null}>
      <IntegrationsPanel />
    </Suspense>
  );
}
