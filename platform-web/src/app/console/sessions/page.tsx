import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { SessionsPanel } from "@/components/console/SessionsPanel";

export const metadata: Metadata = {
  title: "Sessions",
};

/** The live sign-ins of a person, and signing them out everywhere. */
export default function SessionsPage() {
  return (
    <ConsoleFrame>
      <SessionsPanel />
    </ConsoleFrame>
  );
}
