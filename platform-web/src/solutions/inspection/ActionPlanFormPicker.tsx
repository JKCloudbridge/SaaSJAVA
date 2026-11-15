"use client";

import { useEffect, useId, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { getActionPlanForm, setActionPlanForm, type ActionPlanFormState } from "./plantPublication";

/**
 * Which document is the Action plan form of this document (MC2B, ADR-0261): the form a plant head fills when a fill-in
 * of this document scores below the limit. The server lists the documents of the application the person can read and
 * decides who may choose; this control only carries the choice, and saves it on its own (it is not part of the plants).
 */
export function ActionPlanFormPicker({ templateId }: { templateId: string }) {
  const selectId = useId();
  const [state, setState] = useState<ActionPlanFormState | undefined>();
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let live = true;
    void getActionPlanForm(templateId).then((outcome) => {
      if (live && outcome.ok) {
        setState(outcome.data);
        setValue(outcome.data.actionTemplateId ?? "");
      }
    });
    return () => {
      live = false;
    };
  }, [templateId]);

  if (!state?.available || !state.canChange) return null;

  const named = state.actionTemplateId ?? "";
  const choices = state.choices;
  const save = async () => {
    setBusy(true);
    setRefusal(undefined);
    setSaved(false);
    const outcome = await setActionPlanForm(templateId, value === "" ? null : value);
    setBusy(false);
    if (outcome.ok) {
      setState(outcome.data);
      setSaved(true);
    } else {
      setRefusal(outcome.failure);
    }
  };

  return (
    <fieldset className="stack" data-testid="action-plan-form">
      <legend>Action plan form</legend>
      <p className="hint">
        When a fill-in of this document scores below the limit, its plant head fills this form. It need not be published
        to the plants.
      </p>
      <label htmlFor={selectId}>
        The form to fill
        <select id={selectId} value={value} onChange={(event) => { setValue(event.target.value); setSaved(false); }}>
          <option value="">None</option>
          {named !== "" && !choices.some((choice) => choice.id === named) ? (
            <option value={named}>{state.actionName ?? "The form that is named now"}</option>
          ) : null}
          {choices.map((choice) => (
            <option key={choice.id} value={choice.id}>
              {choice.name ?? "Untitled"}{choice.number ? ` (${choice.number})` : ""}
            </option>
          ))}
        </select>
      </label>
      <div>
        <button type="button" className="button button-secondary" disabled={busy || value === named} onClick={() => void save()}>
          Save the form
        </button>
      </div>
      {saved ? <p role="status" className="hint">The Action plan form was saved.</p> : null}
      {refusal ? <p role="alert" className="form-message">{refusal.message}</p> : null}
    </fieldset>
  );
}
