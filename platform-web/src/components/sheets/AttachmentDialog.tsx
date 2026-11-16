"use client";

import { Modal } from "@/components/ui/Modal";
import { FillAttachments } from "./FillAttachments";

export interface AttachmentDialogProps {
  /** The header record of the fill-in: its files are the fill-in's files (ADR-0254). */
  object: string;
  recordId: string;
  /** The words the cell's place is called by, such as "B12", for the title. */
  place: string;
  /** The identifiers the cell names now. */
  value: readonly string[];
  /** The most files this cell holds. */
  maxFiles: number;
  /** Whether the answer is required at Submit (at least one file). */
  required: boolean;
  /** True when the person may look at and download the files but not change them. */
  readOnly: boolean;
  /** Names the files in the cell; answers with the editor's refusal in words, or null. */
  onChange(files: string[]): string | null;
  beforeRemove(): Promise<boolean>;
  afterRemove(): Promise<void>;
  onClose(): void;
}

/**
 * The popup the fill-in grid opens on an attachment cell (SH5.1, ADR-0263): one click on its paperclip, Enter or a
 * double click. It holds the same attach control as the form view, so choosing, naming, downloading and taking a file
 * off are the same calls and the same words. It decides nothing: the file service and the fill-in's own rules answer.
 */
export function AttachmentDialog({
  object,
  recordId,
  place,
  value,
  maxFiles,
  required,
  readOnly,
  onChange,
  beforeRemove,
  afterRemove,
  onClose,
}: AttachmentDialogProps) {
  return (
    <Modal title={`Attachments, cell ${place}`} onClose={onClose} testId="attachment-dialog">
      <div className="stack">
        <p className="hint">
          {readOnly
            ? "You can open and download the files of this cell. They cannot be changed here."
            : `Attach up to ${maxFiles} ${maxFiles === 1 ? "file" : "files"}.${required ? " At least one is needed before the fill-in is sent." : ""}`}
        </p>
        <FillAttachments
          object={object}
          recordId={recordId}
          value={value}
          maxFiles={maxFiles}
          disabled={readOnly}
          onChange={onChange}
          beforeRemove={beforeRemove}
          afterRemove={afterRemove}
        />
        <div className="split">
          <button type="button" className="button button-secondary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </Modal>
  );
}
