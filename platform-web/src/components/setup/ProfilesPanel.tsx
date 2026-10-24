"use client";

import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { AbilityPicker } from "./AbilityPicker";
import { DataAccessEditor } from "./DataAccessEditor";

type Profile = components["schemas"]["ProfileView"];
type Ability = components["schemas"]["AbilityInfo"];
type LicenceType = components["schemas"]["LicenceTypeItem"];

interface Loaded {
  profiles: Profile[];
  abilities: Ability[];
  licenceTypes: LicenceType[];
}

interface Draft {
  name: string;
  description: string;
  licenceType: string;
  abilities: string[];
}

const EMPTY: Draft = { name: "", description: "", licenceType: "user", abilities: [] };

const draftOf = (profile: Profile): Draft => ({
  name: profile.name,
  description: profile.description,
  licenceType: profile.licenceType,
  abilities: profile.abilities,
});

/**
 * The profiles of the organization the address names: the base set of abilities of a member, belonging to one licence
 * type. A list; a row opens the profile (its details, abilities and permissions on data); New opens the same page as a form.
 * Anyone who manages access creates, changes and removes them; the page shows what the API answers and its own words for
 * every refusal, and never decides who may do what or which licence is free.
 */
export function ProfilesPanel() {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const offersManage = useOffers("access.manage");

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

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  const openNew = () => {
    setDraft(EMPTY);
    setEditing(false);
    screen.create();
  };
  const openRow = (profile: Profile) => {
    setEditing(false);
    screen.open(profile.id);
  };

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const result = await api.POST("/api/v1/profiles", { body: draft });
      if (result.response.ok) {
        screen.back(true);
      }
      return { error: result.error, response: result.response, text: "The profile was created." };
    });
  }

  const save = (profile: Profile) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void act(async () => {
      const result = await api.PUT("/api/v1/profiles/{profileId}", {
        params: { path: { profileId: profile.id } },
        body: draft,
      });
      if (result.response.ok) {
        setEditing(false);
      }
      return { error: result.error, response: result.response, text: "The profile was saved." };
    });
  };

  const remove = (profile: Profile) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/profiles/{profileId}", {
        params: { path: { profileId: profile.id } },
      });
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The profile was removed." };
    });

  const makeDefault = (profile: Profile) =>
    act(async () => {
      const { error, response } = await api.POST("/api/v1/profiles/{profileId}/default", {
        params: { path: { profileId: profile.id } },
      });
      return { error, response, text: "New members now get this profile." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="profiles" />;

  if (screen.creating && loaded) {
    return (
      <ProfileForm
        title="New profile"
        loaded={loaded}
        draft={draft}
        onDraft={setDraft}
        onSubmit={create}
        submitLabel="Create the profile"
        onCancel={() => screen.back()}
        busy={busy}
        notices={messages}
      />
    );
  }

  const profile = loaded?.profiles.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && loaded && !profile) {
    return <MissingDetail listLabel="Profiles" onBack={() => screen.back()} what="This profile does not exist any more." />;
  }

  if (loaded && profile) {
    const nameOf = (key: string) => loaded.abilities.find((ability) => ability.key === key)?.name ?? key;
    const actions = editing ? (
      <>
        <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
          Cancel
        </button>
        <button type="submit" form="profile-form" className="button" disabled={busy}>
          Save the profile
        </button>
      </>
    ) : (
      <>
        {profile.system || profile.defaultProfile ? null : (
          <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(profile)}>
            Remove
          </button>
        )}
        {profile.fullAccess || profile.defaultProfile ? null : (
          <button type="button" className="button button-secondary" disabled={busy} onClick={() => void makeDefault(profile)}>
            Make default
          </button>
        )}
        {profile.fullAccess ? null : (
          <button
            type="button"
            className="button"
            disabled={busy}
            onClick={() => {
              setDraft(draftOf(profile));
              setEditing(true);
            }}
          >
            Change
          </button>
        )}
      </>
    );
    return (
      <DetailPage
        listLabel="Profiles"
        onBack={() => screen.back()}
        title={profile.name}
        actions={actions}
        notices={messages}
        testId="profile-detail"
      >
        <form id="profile-form" onSubmit={save(profile)} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              {editing ? (
                <ProfileFields loaded={loaded} draft={draft} onDraft={setDraft} />
              ) : (
                <>
                  <DetailItem label="Name">{profile.name}</DetailItem>
                  <DetailItem label="Licence type">{profile.licenceType}</DetailItem>
                  <DetailItem label="Description" wide tone={profile.description ? undefined : "muted"}>
                    {profile.description || "—"}
                  </DetailItem>
                  <DetailItem label="Members">{profile.members}</DetailItem>
                  <DetailItem label="Status">
                    {profile.system ? <Tag>System</Tag> : <Tag>Custom</Tag>}{" "}
                    {profile.defaultProfile ? <Tag tone="on">Default for new members</Tag> : null}
                  </DetailItem>
                </>
              )}
            </DetailGrid>
          </DetailSection>
          <DetailSection title="Abilities">
            {editing ? (
              <AbilityPicker
                legend="Abilities"
                abilities={loaded.abilities}
                selected={draft.abilities}
                onChange={(abilities) => setDraft({ ...draft, abilities })}
              />
            ) : (
              <p className="dl-value" data-testid="profile-abilities">
                {profile.abilities.length === 0 ? "none" : profile.abilities.map(nameOf).join(", ")}
              </p>
            )}
          </DetailSection>
        </form>
        <DetailSection title="Permissions on data">
          <DataAccessEditor
            key={profile.id}
            target={{ kind: "profile", id: profile.id }}
            title={`Permissions on data: ${profile.name}`}
          />
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<Profile>
      title="Profiles"
      items={loaded?.profiles}
      failure={failure}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.name}
      primaryExtra={(row) => row.description || null}
      columns={[
        { key: "licence", header: "Licence type", kind: "muted", cell: (row) => row.licenceType },
        { key: "members", header: "Members", kind: "muted", cell: (row) => row.members },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <>
              {row.defaultProfile ? <Tag tone="on">Default for new members</Tag> : null}{" "}
              <Tag>{row.system ? "System" : "Custom"}</Tag>
            </>
          ),
        },
      ]}
      onOpen={openRow}
      searchText={(row) => `${row.name} ${row.description} ${row.licenceType}`}
      onNew={offersManage ? openNew : undefined}
      newLabel="New profile"
      notices={messages}
      emptyText="There are no profiles yet."
      testId="profiles"
      rowTestId="profile-row"
    />
  );
}

/** The fields of a profile as a form: name, description, licence type. */
function ProfileFields({
  loaded,
  draft,
  onDraft,
}: {
  loaded: Loaded;
  draft: Draft;
  onDraft: (next: Draft) => void;
}) {
  return (
    <>
      <div className="field">
        <label htmlFor="profile-name">Name</label>
        <input
          id="profile-name"
          value={draft.name}
          maxLength={80}
          onChange={(event) => onDraft({ ...draft, name: event.target.value })}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="profile-licence">Licence type</label>
        <select
          id="profile-licence"
          value={draft.licenceType}
          onChange={(event) => onDraft({ ...draft, licenceType: event.target.value })}
        >
          {loaded.licenceTypes
            .filter((type) => type.kind === "SEAT")
            .map((type) => (
              <option key={type.key} value={type.key}>
                {type.name}
              </option>
            ))}
        </select>
      </div>
      <div className="field span-2">
        <label htmlFor="profile-description">Description</label>
        <input
          id="profile-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => onDraft({ ...draft, description: event.target.value })}
        />
      </div>
    </>
  );
}

/** A new profile: the same page as an existing one, as a form. */
function ProfileForm({
  title,
  loaded,
  draft,
  onDraft,
  onSubmit,
  submitLabel,
  onCancel,
  busy,
  notices,
}: {
  title: string;
  loaded: Loaded;
  draft: Draft;
  onDraft: (next: Draft) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  submitLabel: string;
  onCancel: () => void;
  busy: boolean;
  notices: ReactNode;
}) {
  return (
    <DetailPage
      listLabel="Profiles"
      onBack={onCancel}
      title={title}
      notices={notices}
      actions={
        <>
          <button type="button" className="button button-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" form="profile-form" className="button" disabled={busy}>
            {submitLabel}
          </button>
        </>
      }
    >
      <form id="profile-form" onSubmit={onSubmit} noValidate>
        <DetailSection title="Detail">
          <DetailGrid>
            <ProfileFields loaded={loaded} draft={draft} onDraft={onDraft} />
          </DetailGrid>
        </DetailSection>
        <DetailSection title="Abilities">
          <AbilityPicker
            legend="Abilities"
            abilities={loaded.abilities}
            selected={draft.abilities}
            onChange={(abilities) => onDraft({ ...draft, abilities })}
          />
        </DetailSection>
      </form>
    </DetailPage>
  );
}
