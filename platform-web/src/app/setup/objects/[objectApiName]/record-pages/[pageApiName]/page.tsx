import type { Metadata } from "next";
import { RecordPageBuilder } from "@/components/setup/RecordPageBuilder";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Record page",
};

/** The builder of one record page of an object; the address names the object and the page ("new" starts one). */
export default function RecordPageBuilderPage() {
  return (
    <>
      <h1>Objects</h1>
      <SetupFrame>
        <RecordPageBuilder />
      </SetupFrame>
    </>
  );
}
