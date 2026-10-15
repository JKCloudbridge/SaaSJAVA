import type { Metadata } from "next";
import { EntryNewRecordRoute } from "@/components/records/RecordRoutes";

export const metadata: Metadata = {
  title: "New record",
};

/** The create form of an object, inside the application that lists it (Sprint 19). */
export default function EntryNewRecordPage() {
  return <EntryNewRecordRoute />;
}
