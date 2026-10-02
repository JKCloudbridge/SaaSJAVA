import type { Metadata } from "next";
import { AccessPoliciesPanel } from "@/components/setup/AccessPoliciesPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Access policies",
};

/** The access policies of the organization this address belongs to. */
export default function AccessPoliciesPage() {
  return (
    <>
      <h1>Access policies</h1>
      <SetupFrame>
        <AccessPoliciesPanel />
      </SetupFrame>
    </>
  );
}
