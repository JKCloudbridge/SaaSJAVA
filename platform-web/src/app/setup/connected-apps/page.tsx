import type { Metadata } from "next";
import { Suspense } from "react";
import { ConnectedAppsPanel } from "@/components/setup/ConnectedAppsPanel";

export const metadata: Metadata = { title: "Connected applications" };

/** The organization's connected applications (Sprint 31). The open row is kept in the address. */
export default function ConnectedAppsPage() {
  return (
    <Suspense fallback={null}>
      <ConnectedAppsPanel />
    </Suspense>
  );
}
