import type { Metadata } from "next";
import { NewRecordRoute } from "@/components/records/RecordRoutes";

export const metadata: Metadata = {
  title: "New record",
};

/** The create form of an object (Sprint 19). */
export default function NewRecordPage() {
  return <NewRecordRoute />;
}
