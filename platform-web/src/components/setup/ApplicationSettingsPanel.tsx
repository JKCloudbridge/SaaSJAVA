"use client";

import { useCallback, useEffect, useId, useState } from "react";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";

type Group = components["schemas"]["ApplicationSettingsGroupView"];
type Setting = components["schemas"]["ApplicationSettingView"];

/**
 * Setup, Application settings (MC2B, ADR-0261): what each application the organization has lets it choose. The list, the
 * words, the range and the default all come from the API (the application's own file declares them); this page holds no
 * setting of its own, checks nothing the server does not check again, and shows the server's words for a refusal.
 */
export function ApplicationSettingsPanel() {
  const allowed = useOffers("application-settings.manage");
  const [groups, setGroups] = useState<Group[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const load = useCallback(async () => {
    try {
      const result = await api.GET("/api/v1/application-settings");
      if (result.data) {
        setGroups(result.data.data.applications);
        setFailure(undefined);
      } else {
        setFailure(problemText(result.error, result.response));
      }
    } catch {
      setFailure("The page could not reach the platform. Try again.");
    }
  }, []);
  useEffect(() => {
    if (allowed) void Promise.resolve().then(load);
  }, [allowed, load]);

  if (!allowed) return <p role="status">You do not have access to application settings.</p>;
  return (
    <PageFrame title="Application settings" testId="application-settings">
      {failure ? <p role="alert" className="form-message">{failure}</p> : null}
      {notice ? <p role="status" className="hint">{notice}</p> : null}
      {groups && groups.length === 0 ? <p className="hint">No application of this organization offers a setting.</p> : null}
      {groups?.map((group) => (
        <section key={group.application} className="stack" aria-label={group.label}>
          <h2>{group.label}</h2>
          {group.settings.map((setting) => (
            <SettingForm
              key={`${group.application}:${setting.key}:${setting.version ?? "none"}`}
              application={group.application}
              setting={setting}
              onChanged={async (words) => {
                await load();
                setNotice(words);
              }}
            />
          ))}
        </section>
      ))}
    </PageFrame>
  );
}

function SettingForm({ application, setting, onChanged }: {
  application: string;
  setting: Setting;
  onChanged: (notice: string) => Promise<void>;
}) {
  const hintId = useId();
  const [value, setValue] = useState(String(setting.value));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();
  const percent = setting.kind === "PERCENT";
  const run = async (work: () => Promise<string | undefined>) => {
    setBusy(true);
    setProblem(undefined);
    try {
      const words = await work();
      if (words) await onChanged(words);
    } catch {
      setProblem("The page could not reach the platform. Try again.");
    } finally {
      setBusy(false);
    }
  };
  const save = () => run(async () => {
    const result = await api.PUT("/api/v1/application-settings", {
      body: { application, key: setting.key, value: Number(value), version: setting.version ?? undefined },
    });
    if (!result.data) {
      setProblem(problemText(result.error, result.response));
      return undefined;
    }
    return "The setting was saved. It applies to reviews that end from now on.";
  });
  const reset = () => run(async () => {
    const result = await api.DELETE("/api/v1/application-settings", {
      params: { query: { application, key: setting.key, version: setting.version ?? 0 } },
    });
    if (!result.data) {
      setProblem(problemText(result.error, result.response));
      return undefined;
    }
    return "The setting uses the application's own value again.";
  });
  const wholeNumber = /^\d+$/.test(value) && Number(value) >= setting.least && Number(value) <= setting.most;
  return (
    <form className="form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
      <label className="field">{setting.label}{percent ? " (percent)" : ""}
        <input
          type="number" inputMode="numeric" min={setting.least} max={setting.most} step={1} value={value}
          aria-describedby={hintId} aria-invalid={problem ? true : undefined}
          onChange={(event) => setValue(event.target.value)}
        />
      </label>
      <p id={hintId} className="hint">
        {setting.description} Allowed: {setting.least} to {setting.most}. The application starts with {setting.defaultValue}.
        {setting.changed ? " This organization chose its own value." : ""}
      </p>
      {problem ? <p role="alert" className="form-message">{problem}</p> : null}
      <div className="form-actions">
        <button type="submit" className="button" disabled={busy || !wholeNumber || Number(value) === setting.value}>Save</button>
        {setting.changed ? (
          <button type="button" className="button button-secondary" disabled={busy} onClick={() => void reset()}>
            Use the application&apos;s value
          </button>
        ) : null}
      </div>
    </form>
  );
}
