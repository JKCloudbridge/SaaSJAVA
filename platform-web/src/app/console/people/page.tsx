import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { PeoplePanel } from "@/components/console/PeoplePanel";

export const metadata: Metadata = {
  title: "Platform people",
};

/** Who holds a platform role. */
export default function PeoplePage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <PeoplePanel />
      </ConsoleFrame>
    </>
  );
}
