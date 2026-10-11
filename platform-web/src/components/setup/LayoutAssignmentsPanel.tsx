"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type LayoutView = components["schemas"]["LayoutView"];
type LayoutAssignmentView = components["schemas"]["LayoutAssignmentView"];
type LayoutResolutionView = components["schemas"]["LayoutResolutionView"];
type RecordTypeView = components["schemas"]["RecordTypeView"];
type ProfileView = components["schemas"]["ProfileView"];

/** The rules of the fixed order, in words. The order itself is the API's. */
const RULE_TEXT: Record<string, string> = {
  PROFILE_AND_RECORD_TYPE: "the choice for this profile and record type",
  RECORD_TYPE: "the layout of the record type",
  PROFILE: "the choice for this profile",
  DEFAULT: "the default layout of the object",
  NONE: "no layout is chosen yet",
};

interface Loaded {
  assignments: LayoutAssignmentView[];
  layouts: LayoutView[];
  recordTypes: RecordTypeView[];
  profiles: ProfileView[];
}

/**
 * Which page layout an object uses: a default for everyone, a layout for a profile and, for that profile, a layout for a
 * record type. The layout of a record type alone is set on the record type. The order the rules are tried in is the
 * API's (profile with record type, the record type, the profile, the default); the check at the end asks the API which
 * layout it would give a profile and says which rule decided. A profile that was deleted shows without a name and is
 * ignored.
 */
export function LayoutAssignmentsPanel({ object, refreshToken }: { object: ObjectView; refreshToken: number }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [profile, setProfile] = useState("");
  const [recordType, setRecordType] = useState("");
  const [layout, setLayout] = useState("");
  const [editing, setEditing] = useState<LayoutAssignmentView | undefined>();
  const [asking, setAsking] = useState({ profile: "", recordType: "" });
  const [answer, setAnswer] = useState<LayoutResolutionView | undefined>();

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [assignments, layouts, types, profiles] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/layout-assignments", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-types", { params: { path } }),
        api.GET("/api/v1/profiles"),
      ]);
      const first = [assignments, layouts, types, profiles].find((result) => !result.data);
      if (first || !assignments.data || !layouts.data || !types.data || !profiles.data) {
        setFailure(first ? await failureText(first.error, first.response) : COMMON_TEXT.network);
        return;
      }
      setFailure(undefined);
      setLoaded({
        assignments: assignments.data.data,
        layouts: layouts.data.data,
        recordTypes: types.data.data,
        profiles: profiles.data.data,
      });
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
      <p className="form-message" role="alert" data-testid="layout-assignments-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const nameOf = (assignment: LayoutAssignmentView) =>
    assignment.profileId == null
      ? "Everyone (the default)"
      : (assignment.profileName ?? "A profile that was deleted (ignored)");

  function reset() {
    setProfile("");
    setRecordType("");
    setLayout("");
    setEditing(undefined);
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      if (editing) {
        const update = { layout, version: editing.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_LAYOUT_ASSIGNMENT",
              objectApiName,
              itemApiName: editing.key,
              updateLayoutAssignment: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/layout-assignments/{key}", {
              params: { path: { objectApiName, key: editing.key } },
              body: update,
            });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The choice was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = {
        profileId: profile || undefined,
        recordType: profile && recordType ? recordType : undefined,
        layout,
      };
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "CREATE_LAYOUT_ASSIGNMENT",
            objectApiName,
            createLayoutAssignment: request,
          })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/layout-assignments", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The choice was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(assignment: LayoutAssignmentView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_LAYOUT_ASSIGNMENT",
            objectApiName,
            itemApiName: assignment.key,
          })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/layout-assignments/{key}", {
            params: { path: { objectApiName, key: assignment.key } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The choice was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  async function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}/layout-resolution", {
        params: {
          path: { objectApiName },
          query: { profileId: asking.profile || undefined, recordType: asking.recordType || undefined },
        },
      });
      setAnswer(data?.data);
    } catch {
      setAnswer(undefined);
    }
  }

  return (
    <section aria-labelledby="layout-assignments-heading" data-testid="layout-assignments">
      <h3 id="layout-assignments-heading">Which layout is used</h3>
      <p className="hint">
        The layout of a person is chosen in this order: the choice for their profile and the record type, the layout of the
        record type, the choice for their profile, the default layout of the object.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="layout-assignments" />
      {loaded.assignments.length === 0 ? (
        <p>No choices yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>For</th>
              <th>Record type</th>
              <th>Page layout</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loaded.assignments.map((assignment) => (
              <tr key={assignment.key} data-testid="layout-assignment-row">
                <td>{nameOf(assignment)}</td>
                <td>{assignment.recordType ? <code>{assignment.recordType}</code> : "Any"}</td>
                <td>
                  <code>{assignment.layout}</code>
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(assignment);
                      setLayout(assignment.layout);
                    }}
                  >
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(assignment)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>{editing ? "Change the choice" : "Choose a layout"}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={editing ? "Change the choice" : "Choose a layout"}>
        {editing ? (
          <p>
            For {nameOf(editing)}
            {editing.recordType ? <> and record type {editing.recordType}</> : null}. Who it is for never changes.
          </p>
        ) : (
          <>
            <div className="field">
              <label htmlFor="assignment-profile">For</label>
              <select id="assignment-profile" value={profile} onChange={(event) => setProfile(event.target.value)}>
                <option value="">Everyone (the default layout)</option>
                {loaded.profiles.map((one) => (
                  <option key={one.id} value={one.id}>
                    Profile {one.name}
                  </option>
                ))}
              </select>
            </div>
            {profile ? (
              <div className="field">
                <label htmlFor="assignment-record-type">Record type</label>
                <select
                  id="assignment-record-type"
                  value={recordType}
                  onChange={(event) => setRecordType(event.target.value)}
                >
                  <option value="">Any record type</option>
                  {loaded.recordTypes.map((type) => (
                    <option key={type.apiName} value={type.apiName}>
                      {type.label}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
          </>
        )}
        <div className="field">
          <label htmlFor="assignment-layout">Page layout</label>
          <select id="assignment-layout" value={layout} onChange={(event) => setLayout(event.target.value)} required>
            <option value="">Choose a page layout</option>
            {loaded.layouts.map((one) => (
              <option key={one.apiName} value={one.apiName}>
                {one.label}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button" disabled={busy || layout === ""}>
          {editing ? "Save the choice" : "Add the choice"}
        </button>{" "}
        {editing ? (
          <button type="button" className="link-button" onClick={reset}>
            Cancel
          </button>
        ) : null}
      </form>

      <h4>Which layout would a person get?</h4>
      <form className="form" onSubmit={ask} noValidate aria-label="Which layout would a person get">
        <div className="field">
          <label htmlFor="ask-profile">Profile</label>
          <select
            id="ask-profile"
            value={asking.profile}
            onChange={(event) => setAsking({ ...asking, profile: event.target.value })}
          >
            <option value="">Any profile</option>
            {loaded.profiles.map((one) => (
              <option key={one.id} value={one.id}>
                {one.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ask-record-type">Record type</label>
          <select
            id="ask-record-type"
            value={asking.recordType}
            onChange={(event) => setAsking({ ...asking, recordType: event.target.value })}
          >
            <option value="">No record type</option>
            {loaded.recordTypes.map((type) => (
              <option key={type.apiName} value={type.apiName}>
                {type.label}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button">
          Find out
        </button>
        {answer ? (
          <p role="status" data-testid="layout-answer">
            {answer.layout ? (
              <>
                The layout is <code>{answer.layout}</code>: {RULE_TEXT[answer.rule] ?? answer.rule}.
              </>
            ) : (
              <>There is no layout yet: {RULE_TEXT[answer.rule] ?? answer.rule}.</>
            )}
          </p>
        ) : null}
      </form>
    </section>
  );
}
