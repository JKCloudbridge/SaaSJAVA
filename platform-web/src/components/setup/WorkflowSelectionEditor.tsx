"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { FilterBuilder } from "@/components/records/FilterBuilder";
import { useObjectForm } from "@/lib/records/hooks";
import { filterNodeOf, queryOfView, type ListQuery } from "@/lib/records/queryModel";
import type { QueryFilterNode } from "@/lib/records/types";

/**
 * The records a timed workflow looks at: the same filter control as the list screen (Sprint 19), built from the
 * description of the object the API gives the person, so it offers only the fields and comparisons the query engine takes.
 * It holds no rule: the selection is sent with the workflow and the API judges it. The control's own buttons are not needed
 * here (the selection is kept as it is edited), so Apply only says so and Clear empties it.
 */
export function WorkflowSelectionEditor({
  objectApiName,
  selection,
  onChange,
}: {
  objectApiName: string;
  selection: QueryFilterNode | undefined;
  onChange: (selection: QueryFilterNode | undefined) => void;
}) {
  const { state } = useObjectForm(objectApiName);
  const form = state.status === "ready" ? state.form : undefined;
  const [draft, setDraft] = useState<ListQuery | undefined>();
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [applied, setApplied] = useState(false);
  const loaded = useRef(false);

  // The saved selection becomes rows once, when the description of the object arrives.
  useEffect(() => {
    if (form && !loaded.current) {
      loaded.current = true;
      void Promise.resolve().then(() => setDraft(queryOfView(selection ? { filter: selection } : {}, form)));
    }
  }, [form, selection]);

  const fields = useMemo(() => new Map((form?.fields ?? []).map((field) => [field.apiName, field])), [form]);

  if (state.status === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="selection-failed">
        {state.message}
      </p>
    );
  }
  if (!form || !draft) {
    return <p aria-live="polite">Loading the fields…</p>;
  }

  function update(next: ListQuery) {
    setApplied(false);
    setDraft(next);
    onChange(filterNodeOf(next.filters, fields, next.raw));
  }

  return (
    <div className="stack" data-testid="selection-editor">
      <FilterBuilder
        objectApiName={objectApiName}
        form={form}
        draft={draft}
        chosen={chosen}
        onChosen={(key, label) => setChosen({ ...chosen, [key]: label })}
        onChange={update}
        onApply={() => setApplied(true)}
        onClear={() => update({ ...draft, filters: [], raw: undefined })}
      />
      {applied ? (
        <p role="status" data-testid="selection-kept">
          The selection is kept with the workflow. Save the workflow to store it.
        </p>
      ) : null}
    </div>
  );
}
