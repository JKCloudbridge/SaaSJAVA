import type { Metadata } from "next";
import { Suspense } from "react";
import { RolesPanel } from "@/components/setup/RolesPanel";

export const metadata: Metadata = {
  title: "Roles",
};

/** The role hierarchy of the organization this address belongs to (a list; a row opens in the same pane). */
export default function RolesPage() {
  return (
    <Suspense fallback={null}>
      <RolesPanel />
    </Suspense>
  );
}
