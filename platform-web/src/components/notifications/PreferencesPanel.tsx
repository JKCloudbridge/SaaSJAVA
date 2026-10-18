"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { call } from "@/lib/records/call";

type Choice = components["schemas"]["PreferenceChoiceView"];

interface Group {
  category: string;
  label: string;
  description: string;
  rows: Choice[];
}

/** The categories in the order the API lists them, each with its switches. */
function grouped(choices: readonly Choice[]): Group[] {
  const groups: Group[] = [];
  for (const choice of choices) {
    let group = groups.find((one) => one.category === choice.category);
    if (group === undefined) {
      group = { category: choice.category, label: choice.categoryLabel, description: choice.description, rows: [] };
      groups.push(group);
    }
    group.rows.push(choice);
  }
  return groups;
}

/**
 * The signed-in member's choices of which notices and mails they want (Sprint 25, ADR-0156). Every switch, its words and
 * whether the platform fixes it on come from the API; a fixed switch is shown on and cannot be changed, and says why. The
 * screen sends only the switches the person changed and shows the API's words for a refusal.
 */
export function PreferencesPanel() {
  const [choices, setChoices] = useState<Choice[] | undefined>();
  const [edited, setEdited] = useState<Record<string, boolean>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const outcome = await call(() => api.GET("/api/v1/notification-preferences"));
    if (!outcome.ok) {
      setFailure(outcome.failure.message);
      return;
    }
    setFailure(undefined);
    setChoices(outcome.data.data.choices);
    setEdited({});
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const keyOf = (choice: Choice) => `${choice.category}/${choice.channel}`;

  async function save() {
    if (!choices) {
      return;
    }
    setBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    const changed = choices
      .filter((choice) => edited[keyOf(choice)] !== undefined && edited[keyOf(choice)] !== choice.enabled)
      .map((choice) => ({ category: choice.category, channel: choice.channel, enabled: edited[keyOf(choice)] ?? true }));
    if (changed.length === 0) {
      setBusy(false);
      setNotice("Nothing to save.");
      return;
    }
    const outcome = await call(() => api.PUT("/api/v1/notification-preferences", { body: { choices: changed } }));
    setBusy(false);
    if (!outcome.ok) {
      setProblem(outcome.failure.message);
      return;
    }
    setChoices(outcome.data.data.choices);
    setEdited({});
    setNotice("Your choices were saved.");
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="preferences-failed">
        {failure}
      </p>
    );
  }
  if (!choices) {
    return <p aria-live="polite">Loading…</p>;
  }
  return (
    <section aria-labelledby="preferences-heading" className="stack" data-testid="notification-preferences">
      <div className="list-head">
        <h1 id="preferences-heading">Notification preferences</h1>
        <Link className="button button-secondary" href="/notifications">
          Back to notifications
        </Link>
      </div>
      <p className="hint">
        Choose what reaches you, and how. What concerns your account and your organization always reaches you.
      </p>
      {grouped(choices).map((group) => (
        <fieldset key={group.category} className="field card">
          <legend>{group.label}</legend>
          <p className="hint">{group.description}</p>
          {group.rows.map((choice) => (
            <label key={keyOf(choice)}>
              <input
                type="checkbox"
                checked={edited[keyOf(choice)] ?? choice.enabled}
                disabled={choice.locked || busy}
                onChange={(event) => setEdited((known) => ({ ...known, [keyOf(choice)]: event.target.checked }))}
              />{" "}
              {choice.channelLabel}
              {choice.locked ? <span className="hint"> (always on)</span> : null}
            </label>
          ))}
        </fieldset>
      ))}
      {problem ? (
        <p className="form-message" role="alert" data-testid="preferences-problem">
          {problem}
        </p>
      ) : null}
      {notice ? (
        <p role="status" data-testid="preferences-notice">
          {notice}
        </p>
      ) : null}
      <p>
        <button type="button" className="button" disabled={busy} onClick={() => void save()}>
          Save
        </button>
      </p>
    </section>
  );
}
