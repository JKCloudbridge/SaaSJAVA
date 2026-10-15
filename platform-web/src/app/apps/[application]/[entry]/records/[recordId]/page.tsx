import type { Metadata } from "next";
import { EntryRecordRoute } from "@/components/records/RecordRoutes";

export const metadata: Metadata = {
  title: "Record",
};

/** The edit form of one record, inside the application that lists its object (Sprint 19). */
export default function EntryRecordPage() {
  return <EntryRecordRoute />;
}
