import type { Metadata } from "next";
import { RecordPageBuilder } from "@/components/setup/RecordPageBuilder";

export const metadata: Metadata = {
  title: "Record page",
};

/** The builder of one record page of an object; the address names the object and the page ("new" starts one). */
export default function RecordPageBuilderPage() {
  return <RecordPageBuilder />;
}
