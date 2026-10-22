"use client";

import Link from "next/link";
import { SheetWorkspace } from "@/components/sheets/SheetWorkspace";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useRecord } from "@/components/records/RecordFormPage";
import { TEMPLATE_OBJECT, text } from "./template";

/**
 * One template: the facts of its header and, under them, its sheet with the history of its versions, the form view and the
 * grid view (Functional track FA3). The header is an ordinary record; the sheet is the generic workspace, given the
 * record it belongs to. A template the API does not give the member is shown as not found, whether it never existed or is
 * out of reach.
 */
export function TemplateDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const record = useRecord(TEMPLATE_OBJECT, id);

  if (record.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the template…
      </p>
    );
  }
  if (record.status === "failed") {
    return <MissingDetail listLabel="Templates" onBack={onBack} what="This template is not available. It may have been removed, or it may not be part of your access." />;
  }
  const fields = record.record.fields;
  const name = text(fields.name) || "Untitled template";
  return (
    <DetailPage
      listLabel="Templates"
      onBack={onBack}
      title={name}
      actions={
        <Link className="button button-secondary" href={`/records/${TEMPLATE_OBJECT}/${id}`}>
          Edit the details
        </Link>
      }
      testId="template-detail"
    >
      <DetailSection title="Details">
        <DetailGrid>
          <DetailItem label="Category">{text(fields.category) || "None"}</DetailItem>
          <DetailItem label="Sub-category">{text(fields.subCategory) || "None"}</DetailItem>
          <DetailItem label="Number" tone="mono">
            {text(fields.number) || "Not numbered yet"}
          </DetailItem>
          <DetailItem label="Status">{fields.retired === true ? <Tag>Retired</Tag> : "In use"}</DetailItem>
        </DetailGrid>
      </DetailSection>
      <DetailSection title="Sheet" stack>
        <SheetWorkspace owner={{ object: TEMPLATE_OBJECT, id }} label={name} />
      </DetailSection>
    </DetailPage>
  );
}
