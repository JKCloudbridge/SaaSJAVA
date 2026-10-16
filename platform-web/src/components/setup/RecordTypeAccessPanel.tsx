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
  types: RecordTypeView[];
  assignments: RecordTypeAssignmentView[];
  profiles: ProfileView[];
}

/**
 * Which profiles may use which record type: one grid, a row per profile and a column per record type. A record type that
 * no profile is ticked for is open to every profile; once one is ticked it is open to those profiles only (the
 * administrators' profile included). A ticked profile can make the type its default, which is what a new record starts
 * with. An assignment of a profile that was deleted still restricts until it is removed, so it is listed with a way out.
 * The page shows what the API answers; whether a change is allowed is the API's to decide.
 */
export function RecordTypeAccessPanel({ object, refreshToken }: { object: ObjectView; refreshToken: number }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [types, assignments, profiles] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-types", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-type-assignments", { params: { path } }),
        api.GET("/api/v1/profiles"),
      ]);
      const first = [types, assignments, profiles].find((result) => !result.data);
      if (first || !types.data || !assignments.data || !profiles.data) {
        setFailure(first ? await failureText(first.error, first.response) : COMMON_TEXT.network);
        return;
      }
      setFailure(undefined);
      setLoaded({ types: types.data.data, assignments: assignments.data.data, profiles: profiles.data.data });
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(reload);
    }
  }, [reload, managed, refreshToken]);

  const { busy, notice, problem, act } = useAction(reload);

  if (object.managedBy) {
    return null;
  }
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
  if (loaded.types.length === 0) {
    return null;
  }

  const cell = (profileId: string, recordType: string) =>
    loaded.assignments.find((one) => one.profileId === profileId && one.recordType === recordType);
  const known = new Set(loaded.profiles.map((profile) => profile.id));
  const orphans = loaded.assignments.filter((one) => !known.has(one.profileId));
  const restricted = (recordType: string) => loaded.assignments.some((one) => one.recordType === recordType);

  function allow(profile: ProfileView, type: RecordTypeView) {
    void act(async () => {
      const request = { profileId: profile.id, recordType: type.apiName };
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "CREATE_RECORD_TYPE_ASSIGNMENT",
            objectApiName,
            createRecordTypeAssignment: request,
          })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-types/{recordType}/profiles", {
            params: { path: { objectApiName, recordType: type.apiName } },
            body: request,
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : `Profile ${profile.name} may use ${type.label}.`,
        problem: problemText(result.error, result.response),
      };
    });
  }

  function disallow(assignment: RecordTypeAssignmentView, name: string, label: string) {
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

  function makeDefault(assignment: RecordTypeAssignmentView, name: string, label: string) {
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
    <section aria-labelledby="record-type-access-heading" data-testid="record-type-access">
      <h3 id="record-type-access-heading">Which profiles use which record type</h3>
      <p className="hint">
        A record type that no profile is ticked for is open to every profile. Tick a profile and the record type is open to
        the ticked profiles only. The default is the record type a new record of that profile starts with.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-type-access" />
      <table className="table">
        <thead>
          <tr>
            <th>Profile</th>
            {loaded.types.map((type) => (
              <th key={type.apiName}>
                {type.label}
                <div className="hint">{restricted(type.apiName) ? "Ticked profiles only" : "Open to every profile"}</div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loaded.profiles.map((profile) => (
            <tr key={profile.id} data-testid="record-type-access-row">
              <th scope="row">{profile.name}</th>
              {loaded.types.map((type) => {
                const assignment = cell(profile.id, type.apiName);
                return (
                  <td key={type.apiName}>
                    <label>
                      <input
                        type="checkbox"
                        checked={assignment !== undefined}
                        disabled={busy}
                        aria-label={`${profile.name} may use ${type.label}`}
                        onChange={(event) =>
                          event.target.checked ? allow(profile, type) : assignment && disallow(assignment, profile.name, type.label)
                        }
                      />
                    </label>{" "}
                    {assignment ? (
                      assignment.defaultForProfile ? (
                        <span className="badge badge-on">Default</span>
                      ) : (
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => makeDefault(assignment, profile.name, type.label)}
                          aria-label={`Make ${type.label} the default of ${profile.name}`}
                        >
                          Make default
                        </button>
                      )
                    ) : null}
                  </td>
                );
              })}
            </tr>
          ))}
          {orphans.map((assignment) => (
            <tr key={assignment.key} data-testid="record-type-access-orphan">
              <th scope="row">A profile that was deleted</th>
              <td colSpan={loaded.types.length}>
                {loaded.types.find((type) => type.apiName === assignment.recordType)?.label ?? assignment.recordType} still
                counts as a restriction until it is removed.{" "}
                <button
                  type="button"
                  className="link-button"
                  disabled={busy}
                  onClick={() => disallow(assignment, "A deleted profile", assignment.recordType)}
                >
                  Remove it
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
