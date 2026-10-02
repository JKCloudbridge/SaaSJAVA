import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { OrganizationsPanel } from "@/components/console/OrganizationsPanel";

export const metadata: Metadata = {
  title: "Console",
};

/** The platform console: the organizations of the platform. Platform address only. */
export default function ConsolePage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <OrganizationsPanel />
      </ConsoleFrame>
    </>
  );
}
