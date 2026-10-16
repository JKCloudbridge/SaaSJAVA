"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type RecordPageView = components["schemas"]["RecordPageView"];
type RecordPageAssignmentView = components["schemas"]["RecordPageAssignmentView"];
type RecordPageResolutionView = components["schemas"]["RecordPageResolutionView"];
type RecordTypeView = components["schemas"]["RecordTypeView"];
type ProfileView = components["schemas"]["ProfileView"];

/** The rules of the fixed order, in words. The order itself is the API's. */
const RULE_TEXT: Record<string, string> = {
  PROFILE_AND_RECORD_TYPE: "the choice for this profile and record type",
  RECORD_TYPE: "the choice for this record type",
  PROFILE: "the choice for this profile",
  DEFAULT: "the default record page of the object",
  NONE: "no record page is chosen yet",
};

interface Loaded {
  assignments: RecordPageAssignmentView[];
  pages: RecordPageView[];
  recordTypes: RecordTypeView[];
  profiles: ProfileView[];
}

/**
 * Which record page an object uses: a default for everyone, a page for a profile, for a record type, or for a profile
 * together with a record type. Unlike a page layout, a record type has no page of its own, so a record type alone is a
 * choice made here. The order the rules are tried in is the API's (profile with record type, the record type, the
 * profile, the default); the check at the end asks the API which page it would give a person and says which rule decided.
 * A profile that was deleted shows without a name and is ignored.
 */
export function RecordPageAssignmentsPanel({ object, refreshToken }: { object: ObjectView; refreshToken: number }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [profile, setProfile] = useState("");
  const [recordType, setRecordType] = useState("");
  const [recordPage, setRecordPage] = useState("");
  const [editing, setEditing] = useState<RecordPageAssignmentView | undefined>();
  const [asking, setAsking] = useState({ profile: "", recordType: "" });
  const [answer, setAnswer] = useState<RecordPageResolutionView | undefined>();

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [assignments, pages, types, profiles] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-page-assignments", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-types", { params: { path } }),
        api.GET("/api/v1/profiles"),
      ]);
      const first = [assignments, pages, types, profiles].find((result) => !result.data);
      if (first || !assignments.data || !pages.data || !types.data || !profiles.data) {
        setFailure(first ? await failureText(first.error, first.response) : COMMON_TEXT.network);
        return;
      }
      setFailure(undefined);
      setLoaded({
        assignments: assignments.data.data,
        pages: pages.data.data,
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
      <p className="form-message" role="alert" data-testid="record-page-assignments-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const nameOf = (assignment: RecordPageAssignmentView) =>
    assignment.profileId == null
      ? assignment.recordType
        ? "Everyone"
        : "Everyone (the default)"
      : (assignment.profileName ?? "A profile that was deleted (ignored)");

  function reset() {
    setProfile("");
    setRecordType("");
    setRecordPage("");
    setEditing(undefined);
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      if (editing) {
        const update = { recordPage, version: editing.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_RECORD_PAGE_ASSIGNMENT",
              objectApiName,
              itemApiName: editing.key,
              updateRecordPageAssignment: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/record-page-assignments/{key}", {
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
        recordType: recordType || undefined,
        recordPage,
      };
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "CREATE_RECORD_PAGE_ASSIGNMENT",
            objectApiName,
            createRecordPageAssignment: request,
          })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-page-assignments", {
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

  function remove(assignment: RecordPageAssignmentView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_RECORD_PAGE_ASSIGNMENT",
            objectApiName,
            itemApiName: assignment.key,
          })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-page-assignments/{key}", {
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
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}/record-page-resolution", {
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

  const formName = editing ? "Change the choice" : "Choose a record page";

  return (
    <section aria-labelledby="record-page-assignments-heading" data-testid="record-page-assignments">
      <h3 id="record-page-assignments-heading">Which record page is used</h3>
      <p className="hint">
        The page a person gets is chosen in this order: the choice for their profile and the record type, the choice for the
        record type, the choice for their profile, the default page of the object.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-page-assignments" />
      {loaded.assignments.length === 0 ? (
        <p>No choices yet. Without a choice the platform shows its own page: the details and the related records.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>For</th>
              <th>Record type</th>
              <th>Record page</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loaded.assignments.map((assignment) => (
              <tr key={assignment.key} data-testid="record-page-assignment-row">
                <td>{nameOf(assignment)}</td>
                <td>{assignment.recordType ? <code>{assignment.recordType}</code> : "Any"}</td>
                <td>
                  <code>{assignment.recordPage}</code>
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(assignment);
                      setRecordPage(assignment.recordPage);
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

      <h4>{formName}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            For {nameOf(editing)}
            {editing.recordType ? <> and record type {editing.recordType}</> : null}. Who it is for never changes.
          </p>
        ) : (
          <>
            <div className="field">
              <label htmlFor="page-assignment-profile">For</label>
              <select id="page-assignment-profile" value={profile} onChange={(event) => setProfile(event.target.value)}>
                <option value="">Everyone</option>
                {loaded.profiles.map((one) => (
                  <option key={one.id} value={one.id}>
                    Profile {one.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="page-assignment-record-type">Record type</label>
              <select
                id="page-assignment-record-type"
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
              <p className="hint">Everyone and any record type together make the default page of the object.</p>
            </div>
          </>
        )}
        <div className="field">
          <label htmlFor="page-assignment-page">Record page</label>
          <select id="page-assignment-page" value={recordPage} onChange={(event) => setRecordPage(event.target.value)} required>
            <option value="">Choose a record page</option>
            {loaded.pages.map((one) => (
              <option key={one.apiName} value={one.apiName}>
                {one.label}
              </option>
            ))}
          </select>
        </div>
        <div className="form-actions">
          <button type="submit" className="button" disabled={busy || recordPage === ""}>
            {editing ? "Save the choice" : "Add the choice"}
          </button>
          {editing ? (
            <button type="button" className="link-button" onClick={reset}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>

      <h4>Which record page would a person get?</h4>
      <form className="form" onSubmit={ask} noValidate aria-label="Which record page would a person get">
        <div className="field">
          <label htmlFor="page-ask-profile">Profile</label>
          <select
            id="page-ask-profile"
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
          <label htmlFor="page-ask-record-type">Record type</label>
          <select
            id="page-ask-record-type"
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
          <p role="status" data-testid="record-page-answer">
            {answer.recordPage ? (
              <>
                The record page is <code>{answer.recordPage}</code>: {RULE_TEXT[answer.rule] ?? answer.rule}.
              </>
            ) : (
              <>There is no record page yet: {RULE_TEXT[answer.rule] ?? answer.rule}.</>
            )}
          </p>
        ) : null}
      </form>
    </section>
  );
}
