import type { Metadata } from "next";
import { Suspense } from "react";
import { AccessPoliciesPanel } from "@/components/setup/AccessPoliciesPanel";

export const metadata: Metadata = {
  title: "Access policies",
};

/** The access policies of the organization this address belongs to (a list; a row opens in the same pane). */
export default function AccessPoliciesPage() {
  return (
    <Suspense fallback={null}>
      <AccessPoliciesPanel />
    </Suspense>
  );
}
