"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { AbilityPicker } from "./AbilityPicker";

type Profile = components["schemas"]["ProfileView"];
type Ability = components["schemas"]["AbilityInfo"];
type LicenceType = components["schemas"]["CatalogueItem"];

interface Loaded {
  profiles: Profile[];
  abilities: Ability[];
  licenceTypes: LicenceType[];
}

interface Draft {
  id?: string;
  name: string;
  description: string;
  licenceType: string;
  abilities: string[];
}

const EMPTY: Draft = { name: "", description: "", licenceType: "user", abilities: [] };

/**
 * The profiles of the organization the address names: the base set of abilities of a member, belonging to one licence
 * type. Anyone who manages access creates, changes and removes them; the page shows what the API answers and its own words
 * for every refusal, and never decides who may do what or which licence is free.
 */
export function ProfilesPanel() {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);

  const reload = useCallback(async () => {
    try {
      const [profiles, abilities, types] = await Promise.all([
        api.GET("/api/v1/profiles"),
        api.GET("/api/v1/abilities"),
        api.GET("/api/v1/licence-types"),
      ]);
      if (!profiles.data) {
        setFailure(await failureText(profiles.error, profiles.response));
        return;
      }
      setFailure(undefined);
      setLoaded({
        profiles: profiles.data.data,
        abilities: abilities.data?.data ?? [],
        licenceTypes: types.data?.data ?? [],
      });
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = {
      name: draft.name,
      description: draft.description,
      licenceType: draft.licenceType,
      abilities: draft.abilities,
    };
    void act(async () => {
      const result = draft.id
        ? await api.PUT("/api/v1/profiles/{profileId}", { params: { path: { profileId: draft.id } }, body })
        : await api.POST("/api/v1/profiles", { body });
      if (result.response.ok) {
        setDraft(EMPTY);
      }
      return { error: result.error, response: result.response, text: draft.id ? "The profile was saved." : "The profile was created." };
    });
  }

  const remove = (profile: Profile) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/profiles/{profileId}", {
        params: { path: { profileId: profile.id } },
      });
      return { error, response, text: "The profile was removed." };
    });

  const makeDefault = (profile: Profile) =>
    act(async () => {
      const { error, response } = await api.POST("/api/v1/profiles/{profileId}/default", {
        params: { path: { profileId: profile.id } },
      });
      return { error, response, text: "New members now get this profile." };
    });

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="profiles-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const nameOf = (key: string) => loaded.abilities.find((ability) => ability.key === key)?.name ?? key;

  return (
    <div className="stack">
      <p>
        A profile is the base set of abilities of a member and belongs to one licence type: giving a member a profile uses one
        licence of that type. A member has exactly one profile.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="profiles" />

      <section aria-labelledby="profile-form-heading">
        <h2 id="profile-form-heading">{draft.id ? "Change the profile" : "Create a profile"}</h2>
        <form className="form" onSubmit={save} noValidate>
          <div className="field">
            <label htmlFor="profile-name">Name</label>
            <input
              id="profile-name"
              value={draft.name}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="profile-description">Description</label>
            <input
              id="profile-description"
              value={draft.description}
              maxLength={500}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="profile-licence">Licence type</label>
            <select
              id="profile-licence"
              value={draft.licenceType}
              onChange={(event) => setDraft({ ...draft, licenceType: event.target.value })}
            >
              {loaded.licenceTypes.map((type) => (
                <option key={type.key} value={type.key}>
                  {type.name}
                </option>
              ))}
            </select>
          </div>
          <AbilityPicker
            legend="Abilities"
            abilities={loaded.abilities}
            selected={draft.abilities}
            onChange={(abilities) => setDraft({ ...draft, abilities })}
          />
          <button type="submit" className="button" disabled={busy}>
            {draft.id ? "Save the profile" : "Create the profile"}
          </button>{" "}
          {draft.id ? (
            <button type="button" className="link-button" onClick={() => setDraft(EMPTY)}>
              Cancel
            </button>
          ) : null}
        </form>
      </section>

      <section aria-labelledby="profiles-heading">
        <h2 id="profiles-heading">Profiles</h2>
        <table className="table" data-testid="profiles">
          <thead>
            <tr>
              <th>Name</th>
              <th>Licence type</th>
              <th>Abilities</th>
              <th>Members</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loaded.profiles.map((profile) => (
              <tr key={profile.id} data-testid="profile-row">
                <td>
                  {profile.name}
                  {profile.system ? " (system)" : ""}
                  {profile.defaultProfile ? " (default for new members)" : ""}
                  {profile.description ? <div className="hint">{profile.description}</div> : null}
                </td>
                <td>{profile.licenceType}</td>
                <td>{profile.abilities.length === 0 ? "none" : profile.abilities.map(nameOf).join(", ")}</td>
                <td>{profile.members}</td>
                <td>
                  {profile.fullAccess ? null : (
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() =>
                        setDraft({
                          id: profile.id,
                          name: profile.name,
                          description: profile.description,
                          licenceType: profile.licenceType,
                          abilities: profile.abilities,
                        })
                      }
                    >
                      Change
                    </button>
                  )}{" "}
                  {profile.fullAccess || profile.defaultProfile ? null : (
                    <button type="button" className="link-button" disabled={busy} onClick={() => void makeDefault(profile)}>
                      Make default
                    </button>
                  )}{" "}
                  {profile.system || profile.defaultProfile ? null : (
                    <button type="button" className="link-button" disabled={busy} onClick={() => void remove(profile)}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
