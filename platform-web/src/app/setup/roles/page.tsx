import type { Metadata } from "next";
import { RolesPanel } from "@/components/setup/RolesPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Roles",
};

/** The role hierarchy of the organization this address belongs to. */
export default function RolesPage() {
  return (
    <>
      <h1>Roles</h1>
      <SetupFrame>
        <RolesPanel />
      </SetupFrame>
    </>
  );
}
