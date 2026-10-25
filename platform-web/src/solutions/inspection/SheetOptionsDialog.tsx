"use client";

import { useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { setSheetOptions, type TemplateLifecycleView } from "./api";

/**
 * The two options of a template's sheet, changed without publishing again (FA7, ADR-0201): View only (nobody can start a
 * fill-in from the template) and Allow download (everybody who can read the template can download its sheet as an Excel
 * file). The people who hold the right to publish change them; the server asks that on every change and a refusal is
 * shown in its words. Nothing here decides a permission.
 */
export function SheetOptionsDialog({
  templateId,
  lifecycle,
  onClose,
  onDone,
}: {
  templateId: string;
  lifecycle: TemplateLifecycleView;
  onClose: () => void;
  onDone: (view: TemplateLifecycleView) => void;
}) {
  const [viewOnly, setViewOnly] = useState(lifecycle.viewOnly);
  const [allowDownload, setAllowDownload] = useState(lifecycle.allowDownload);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();

  const save = async () => {
    setBusy(true);
    setRefusal(undefined);
    const outcome = await setSheetOptions(templateId, { viewOnly, allowDownload });
    setBusy(false);
    if (outcome.ok) {
      onDone(outcome.data);
      return;
    }
    setRefusal(outcome.failure);
  };

  return (
    <div className="stack" data-testid="sheet-options-dialog">
      <div className="sheet-options-row" role="group" aria-label="Sheet options">
        <label>
          <input type="checkbox" checked={viewOnly} onChange={(event) => setViewOnly(event.target.checked)} /> View only
        </label>
        <label>
          <input type="checkbox" checked={allowDownload} onChange={(event) => setAllowDownload(event.target.checked)} /> Allow
          download
        </label>
      </div>
      <p className="hint">
        View only: the template is a reference and nobody can start a fill-in from it. It can still be read, and fill-ins
        already started can be finished. Allow download: everybody who can read the template can download its sheet as an
        Excel file; the people who publish can always download.
      </p>
      {refusal ? (
        <p role="alert" className="form-message" data-testid="sheet-options-refused">
          {refusal.message}
        </p>
      ) : null}
      <div className="split">
        <button type="button" className="button" onClick={() => void save()} disabled={busy}>
          {busy ? "Saving…" : "Save"}
        </button>
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}
