"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type RecordTypeView = components["schemas"]["RecordTypeView"];
type RecordTypeAssignmentView = components["schemas"]["RecordTypeAssignmentView"];
type ProfileView = components["schemas"]["ProfileView"];

interface Loaded {
  assignments: RecordTypeAssignmentView[];
  profiles: ProfileView[];
}

/**
 * Which profiles may use this record type (a section of the record type's page, UA3, ADR-0098): a row per profile. A record
 * type that no profile is ticked for is open to every profile; once one is ticked it is open to those profiles only (the
 * administrators' profile included). A ticked profile can make the type its default, which is what a new record starts
 * with. An assignment of a profile that was deleted still restricts until it is removed, so it is listed with a way out.
 * The page shows what the API answers; whether a change is allowed is the API's to decide.
 */
export function RecordTypeAccessPanel({
  object,
  recordType,
  refreshToken,
}: {
  object: ObjectView;
  recordType: RecordTypeView;
  refreshToken: number;
}) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [assignments, profiles] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-type-assignments", { params: { path } }),
        api.GET("/api/v1/profiles"),
      ]);
      const first = [assignments, profiles].find((result) => !result.data);
      if (first || !assignments.data || !profiles.data) {
        setFailure(first ? await failureText(first.error, first.response) : COMMON_TEXT.network);
        return;
      }
      setFailure(undefined);
      setLoaded({ assignments: assignments.data.data, profiles: profiles.data.data });
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload, refreshToken]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="record-type-access-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const mine = loaded.assignments.filter((one) => one.recordType === recordType.apiName);
  const cell = (profileId: string) => mine.find((one) => one.profileId === profileId);
  const known = new Set(loaded.profiles.map((profile) => profile.id));
  const orphans = mine.filter((one) => !known.has(one.profileId));
  const label = recordType.label;

  function allow(profile: ProfileView) {
    void act(async () => {
      const request = { profileId: profile.id, recordType: recordType.apiName };
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "CREATE_RECORD_TYPE_ASSIGNMENT",
            objectApiName,
            createRecordTypeAssignment: request,
          })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles", {
            params: { path: { objectApiName, recordType: recordType.apiName } },
            body: request,
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : `Profile ${profile.name} may use ${label}.`,
        problem: problemText(result.error, result.response),
      };
    });
  }

  function disallow(assignment: RecordTypeAssignmentView, name: string) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_RECORD_TYPE_ASSIGNMENT",
            objectApiName,
            itemApiName: assignment.key,
          })
        : await api.DELETE(
            "/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles/{profileId}",
            { params: { path: { objectApiName, recordType: assignment.recordType, profileId: assignment.profileId } } },
          );
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : `${name} may no longer use ${label}.`,
        problem: problemText(result.error, result.response),
      };
    });
  }

  function makeDefault(assignment: RecordTypeAssignmentView, name: string) {
    void act(async () => {
      const update = { defaultForProfile: true, version: assignment.version };
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "UPDATE_RECORD_TYPE_ASSIGNMENT",
            objectApiName,
            itemApiName: assignment.key,
            updateRecordTypeAssignment: update,
          })
        : await api.PUT(
            "/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles/{profileId}",
            {
              params: { path: { objectApiName, recordType: assignment.recordType, profileId: assignment.profileId } },
              body: update,
            },
          );
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : `${label} is now the default of ${name}.`,
        problem: problemText(result.error, result.response),
      };
    });
  }

  return (
    <div className="stack" data-testid="record-type-access">
      <p className="hint">
        {mine.length === 0
          ? "No profile is ticked, so every profile may use this record type. Tick a profile and it is open to the ticked profiles only."
          : "Open to the ticked profiles only. The default is the record type a new record of that profile starts with."}
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-type-access" />
      <table className="table table-list">
        <thead>
          <tr>
            <th scope="col">Profile</th>
            <th scope="col">May use {label}</th>
            <th scope="col">
              <span className="visually-hidden">Default</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {loaded.profiles.map((profile) => {
            const assignment = cell(profile.id);
            return (
              <tr key={profile.id} data-testid="record-type-access-row">
                <th scope="row">{profile.name}</th>
                <td>
                  <input
                    type="checkbox"
                    checked={assignment !== undefined}
                    disabled={busy}
                    aria-label={`${profile.name} may use ${label}`}
                    onChange={(event) =>
                      event.target.checked ? allow(profile) : assignment && disallow(assignment, profile.name)
                    }
                  />
                </td>
                <td className="cell-tag">
                  {assignment ? (
                    assignment.defaultForProfile ? (
                      <span className="badge badge-on">Default</span>
                    ) : (
                      <button
                        type="button"
                        className="link-button"
                        disabled={busy}
                        onClick={() => makeDefault(assignment, profile.name)}
                        aria-label={`Make ${label} the default of ${profile.name}`}
                      >
                        Make default
                      </button>
                    )
                  ) : null}
                </td>
              </tr>
            );
          })}
          {orphans.map((assignment) => (
            <tr key={assignment.key} data-testid="record-type-access-orphan">
              <th scope="row">A profile that was deleted</th>
              <td colSpan={2}>
                {label} still counts as a restriction until it is removed.{" "}
                <button
                  type="button"
                  className="link-button"
                  disabled={busy}
                  onClick={() => disallow(assignment, "A deleted profile")}
                >
                  Remove it
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
