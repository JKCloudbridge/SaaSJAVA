import type { Metadata } from "next";
import { Suspense } from "react";
import { CredentialsPanel } from "@/components/setup/CredentialsPanel";

export const metadata: Metadata = { title: "Credentials" };

/** The organization's credentials (Sprint 27). The open row is kept in the address. */
export default function CredentialsPage() {
  return (
    <Suspense fallback={null}>
      <CredentialsPanel />
    </Suspense>
  );
}
