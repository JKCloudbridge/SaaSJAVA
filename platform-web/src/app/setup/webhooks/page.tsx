import type { Metadata } from "next";
import { Suspense } from "react";
import { WebhooksPanel } from "@/components/setup/WebhooksPanel";

export const metadata: Metadata = { title: "Webhooks" };

/** The organization's webhooks and their delivery log (Sprint 32). The open row is kept in the address. */
export default function WebhooksPage() {
  return (
    <Suspense fallback={null}>
      <WebhooksPanel />
    </Suspense>
  );
}
