"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ChangeSetReport, itemText } from "./ChangeSetReport";
import { problemText, RELEASE_KIND_TEXT } from "./objectText";

type Release = components["schemas"]["ReleaseView"];
type Report = components["schemas"]["ChangeSetReportView"];

/**
 * The history of the organization's published metadata: every publication numbered, what it added, changed or removed,
 * and a rollback of the latest one. Only the latest can be rolled back, and the API checks what the rollback would break
 * (what depends on what, and records or values that would be lost) and says so in its own words.
 */
export function ReleasesPanel() {
  const [releases, setReleases] = useState<Release[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [report, setReport] = useState<Report | undefined>();
  const [confirm, setConfirm] = useState(false);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/releases");
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setReleases(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function check() {
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/metadata/releases/latest/rollback-check");
      setReport(data?.data);
      return { error, response, text: "Here is what a rollback would do.", problem: problemText(error, response) };
    });
  }

  function rollBack() {
    setConfirm(false);
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/metadata/releases/latest/rollback");
      if (response.ok) {
        setReport(undefined);
      }
      return { error, response, text: "The latest release was rolled back.", problem: problemText(error, response) };
    });
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="releases-failed">
        {failure}
      </p>
    );
  }
  if (!releases) {
    return <p aria-live="polite">Loading…</p>;
  }

  return (
    <div className="stack">
      <p>
        Every publication of objects, fields and record types is numbered here, whether it was a change set or a single
        change made at once. The latest one can be rolled back; that is a new publication that undoes it. A field that was
        removed comes back as a definition, but the permissions on it ended when it was removed and do not come back.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="releases" />
      {releases.length === 0 ? (
        <p>Nothing has been published yet.</p>
      ) : (
        <ol reversed className="stack" data-testid="releases">
          {releases.map((release) => (
            <li key={release.number} data-testid="release-row" value={release.number}>
              <strong>Release {release.number}</strong>:{" "}
              {RELEASE_KIND_TEXT[release.kind] ?? release.kind}
              {release.changeSetName ? ` “${release.changeSetName}”` : ""}
              {release.undoesRelease != null ? ` (undoes release ${release.undoesRelease})` : ""}
              {release.rolledBackBy != null ? ` — rolled back by release ${release.rolledBackBy}` : ""}
              <div className="hint">{new Date(release.createdAt).toLocaleString()}</div>
              <ul>
                {release.items.map((item, index) => (
                  <li key={index}>{itemText(item)}</li>
                ))}
              </ul>
              {release.latest ? (
                <p>
                  <button type="button" className="button" disabled={busy} onClick={check}>
                    Check what a rollback would do
                  </button>{" "}
                  {confirm ? (
                    <>
                      <button type="button" className="button" disabled={busy} onClick={rollBack}>
                        Yes, roll back release {release.number}
                      </button>{" "}
                      <button type="button" className="link-button" onClick={() => setConfirm(false)}>
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button type="button" className="link-button" disabled={busy} onClick={() => setConfirm(true)}>
                      Roll back this release
                    </button>
                  )}
                </p>
              ) : null}
            </li>
          ))}
        </ol>
      )}
      {report ? <ChangeSetReport report={report} testId="rollback-report" /> : null}
    </div>
  );
}
