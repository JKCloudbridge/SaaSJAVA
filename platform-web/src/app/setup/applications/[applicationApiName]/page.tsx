import type { Metadata } from "next";
import { ApplicationBuilder } from "@/components/setup/ApplicationBuilder";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Application",
};

/** The builder of one application; the address names it ("new" starts one). */
export default function ApplicationBuilderPage() {
  return (
    <>
      <h1>Applications</h1>
      <SetupFrame>
        <ApplicationBuilder />
      </SetupFrame>
    </>
  );
}
