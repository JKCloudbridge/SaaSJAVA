import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { ProvisionForm } from "@/components/console/ProvisionForm";

export const metadata: Metadata = {
  title: "Set up an organization",
};

/** A platform administrator sets up an organization for a client and invites its first administrator. */
export default function ProvisionPage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <ProvisionForm />
      </ConsoleFrame>
    </>
  );
}
