"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ApplicationView = components["schemas"]["ApplicationView"];
type ProfileView = components["schemas"]["ProfileView"];

/**
 * The applications of the organization: the list, and the way into the builder where an application is made and changed
 * (its settings and its navigation, built by dragging). Removing is done here, live or, when a change set is chosen, as a
 * draft. The page shows what the API answers.
 */
export function ApplicationsPanel() {
  const { id: workingSet } = useWorkingSet();
  const [applications, setApplications] = useState<ApplicationView[] | undefined>();
  const [profiles, setProfiles] = useState<ProfileView[]>([]);
  const [failure, setFailure] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const [list, profileList] = await Promise.all([
        api.GET("/api/v1/metadata/applications"),
        api.GET("/api/v1/profiles"),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setApplications(list.data.data);
      setProfiles(profileList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="applications-failed">
        {failure}
      </p>
    );
  }
  if (!applications) {
    return <p aria-live="polite">Loading…</p>;
  }

  function remove(application: ApplicationView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_APPLICATION", itemApiName: application.apiName })
        : await api.DELETE("/api/v1/metadata/applications/{applicationApiName}", {
            params: { path: { applicationApiName: application.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The application was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const profileName = (id: string) => profiles.find((profile) => profile.id === id)?.name ?? "A profile that was deleted";

  return (
    <section aria-labelledby="applications-heading" data-testid="applications">
      <div className="list-head">
        <h2 id="applications-heading">Applications</h2>
        <Link className="button" href="/setup/applications/new">
          New application
        </Link>
      </div>
      <p className="hint">
        An application is a menu of the organization: the objects, tabs, record pages and links its members see. Members see
        an application when it names their profile, or when it names no profile.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="applications" />
      {applications.length === 0 ? (
        <p>No applications yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Application</th>
              <th>API name</th>
              <th>Seen by</th>
              <th>Items</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {applications.map((application) => (
              <tr key={application.apiName} data-testid="application-row">
                <td>
                  <Link className="table-row-link" href={`/setup/applications/${encodeURIComponent(application.apiName)}`}>
                    {application.label}
                  </Link>
                  {application.description ? <div className="hint">{application.description}</div> : null}
                </td>
                <td>
                  <code>{application.apiName}</code>
                </td>
                <td>
                  {(application.definition.profiles ?? []).length === 0
                    ? "Every member"
                    : (application.definition.profiles ?? []).map(profileName).join(", ")}
                </td>
                <td>{application.definition.items.length}</td>
                <td>
                  <Link className="link-button" href={`/setup/applications/${encodeURIComponent(application.apiName)}`}>
                    Build
                  </Link>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(application)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
