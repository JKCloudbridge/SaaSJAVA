"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Access = components["schemas"]["MemberAccessView"];
type Profile = components["schemas"]["ProfileView"];
type Role = components["schemas"]["RoleView"];
type Policy = components["schemas"]["AccessPolicyView"];
type Ability = components["schemas"]["AbilityInfo"];

interface Choices {
  access: Access;
  profiles: Profile[];
  roles: Role[];
  policies: Policy[];
  abilities: Ability[];
}

/**
 * Everything that decides what one member may do: their profile, role, access policies, individual grants and the resulting
 * abilities, with the actions of someone who manages access. Opened from the members list. The page shows what the API
 * answers and the API's own words for every refusal (no licence free, the last member who can manage access, ...); it never
 * decides who may do what.
 */
export function MemberAccessPanel({
  membershipId,
  name,
  onChanged,
}: {
  membershipId: string;
  name: string;
  onChanged: () => Promise<void>;
}) {
  const [choices, setChoices] = useState<Choices | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [profileId, setProfileId] = useState("");
  const [roleId, setRoleId] = useState("");
  const [policyId, setPolicyId] = useState("");
  const [grantAbility, setGrantAbility] = useState("");
  const [grantReason, setGrantReason] = useState("");

  const reload = useCallback(async () => {
    try {
      const path = { params: { path: { membershipId } } };
      const [access, profiles, roles, policies, abilities] = await Promise.all([
        api.GET("/api/v1/members/{membershipId}/access", path),
        api.GET("/api/v1/profiles"),
        api.GET("/api/v1/roles"),
        api.GET("/api/v1/access-policies"),
        api.GET("/api/v1/abilities"),
      ]);
      if (!access.data) {
        setFailure(await failureText(access.error, access.response));
        return;
      }
      setFailure(undefined);
      setChoices({
        access: access.data.data,
        profiles: profiles.data?.data ?? [],
        roles: roles.data?.data ?? [],
        policies: policies.data?.data ?? [],
        abilities: abilities.data?.data ?? [],
      });
      setProfileId(access.data.data.profileId ?? "");
      setRoleId(access.data.data.roleId ?? "");
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [membershipId]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(async () => {
    await reload();
    await onChanged();
  });

  const path = { params: { path: { membershipId } } };

  const setProfile = (event: FormEvent) => {
    event.preventDefault();
    if (!profileId) {
      return;
    }
    void act(async () => {
      const { error, response } = await api.PUT("/api/v1/members/{membershipId}/profile", { ...path, body: { profileId } });
      return { error, response, text: "The profile was given." };
    });
  };

  const setRole = (event: FormEvent) => {
    event.preventDefault();
    void act(async () => {
      const { error, response } = await api.PUT("/api/v1/members/{membershipId}/role", {
        ...path,
        body: { roleId: roleId || undefined },
      });
      return { error, response, text: roleId ? "The role was given." : "The role was taken away." };
    });
  };

  const assignPolicy = (event: FormEvent) => {
    event.preventDefault();
    if (!policyId) {
      return;
    }
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/members/{membershipId}/policies", { ...path, body: { policyId } });
      if (response.ok) {
        setPolicyId("");
      }
      return { error, response, text: "The access policy was given." };
    });
  };

  const unassignPolicy = (policy: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/members/{membershipId}/policies/{policyId}", {
        params: { path: { membershipId, policyId: policy } },
      });
      return { error, response, text: "The access policy was taken away." };
    });

  const grant = (event: FormEvent) => {
    event.preventDefault();
    if (!grantAbility) {
      return;
    }
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/members/{membershipId}/grants", {
        ...path,
        body: { ability: grantAbility, reason: grantReason || undefined },
      });
      if (response.ok) {
        setGrantAbility("");
        setGrantReason("");
      }
      return { error, response, text: "The ability was given." };
    });
  };

  const revoke = (ability: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/members/{membershipId}/grants/{ability}", {
        params: { path: { membershipId, ability } },
      });
      return { error, response, text: "The ability was taken back." };
    });

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="member-access-failed">
        {failure}
      </p>
    );
  }
  if (!choices) {
    return <p aria-live="polite">Loading…</p>;
  }

  const { access } = choices;
  const nameOf = (key: string) => choices.abilities.find((ability) => ability.key === key)?.name ?? key;
  const heldPolicies = new Set(access.policies.map((policy) => policy.id));

  return (
    <div className="stack" data-testid="member-access">
      <h3>Access of {name}</h3>
      <ActionMessages notice={notice} problem={problem} testId="member-access" />

      <p data-testid="member-abilities">
        <strong>What they may do now:</strong>{" "}
        {access.abilities.length === 0 ? "nothing yet" : access.abilities.map(nameOf).join(", ")}
      </p>
      {access.profileId && !access.licenceHeld ? (
        <p className="hint" data-testid="member-unlicensed">
          This member does not hold the {access.profileLicenceType} licence their profile needs, so the profile gives no
          abilities until one is free.
        </p>
      ) : null}

      <form className="form" onSubmit={setProfile}>
        <div className="field">
          <label htmlFor={`profile-${membershipId}`}>Profile</label>
          <select id={`profile-${membershipId}`} value={profileId} onChange={(event) => setProfileId(event.target.value)}>
            <option value="">Choose a profile</option>
            {choices.profiles.map((profile) => (
              <option key={profile.id} value={profile.id}>
                {profile.name} ({profile.licenceType})
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button" disabled={busy || !profileId || profileId === access.profileId}>
          Give this profile
        </button>
      </form>

      <form className="form" onSubmit={setRole}>
        <div className="field">
          <label htmlFor={`role-${membershipId}`}>Role</label>
          <select id={`role-${membershipId}`} value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            <option value="">No role</option>
            {choices.roles.map((role) => (
              <option key={role.id} value={role.id}>
                {role.name}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button" disabled={busy || roleId === (access.roleId ?? "")}>
          Save the role
        </button>
      </form>

      <section aria-label="Access policies of this member">
        <h4>Access policies</h4>
        {access.policies.length === 0 ? <p>None.</p> : null}
        <ul data-testid="member-policies">
          {access.policies.map((policy) => (
            <li key={policy.id}>
              {policy.name}
              {policy.licenceType ? ` (uses a ${policy.licenceType} licence)` : ""}{" "}
              <button type="button" className="link-button" disabled={busy} onClick={() => void unassignPolicy(policy.id)}>
                Take away
              </button>
            </li>
          ))}
        </ul>
        <form className="form" onSubmit={assignPolicy}>
          <div className="field">
            <label htmlFor={`policy-${membershipId}`}>Give an access policy</label>
            <select id={`policy-${membershipId}`} value={policyId} onChange={(event) => setPolicyId(event.target.value)}>
              <option value="">Choose an access policy</option>
              {choices.policies
                .filter((policy) => !heldPolicies.has(policy.id))
                .map((policy) => (
                  <option key={policy.id} value={policy.id}>
                    {policy.name}
                  </option>
                ))}
            </select>
          </div>
          <button type="submit" className="button" disabled={busy || !policyId}>
            Give the access policy
          </button>
        </form>
      </section>

      <section aria-label="Abilities given to this member directly">
        <h4>Abilities given directly</h4>
        {access.grants.length === 0 ? <p>None.</p> : null}
        <ul data-testid="member-grants">
          {access.grants.map((given) => (
            <li key={given.ability}>
              {nameOf(given.ability)}
              {given.reason ? <span className="hint"> — {given.reason}</span> : null}{" "}
              <button type="button" className="link-button" disabled={busy} onClick={() => void revoke(given.ability)}>
                Take back
              </button>
            </li>
          ))}
        </ul>
        <form className="form" onSubmit={grant}>
          <div className="field">
            <label htmlFor={`grant-${membershipId}`}>Give one ability</label>
            <select id={`grant-${membershipId}`} value={grantAbility} onChange={(event) => setGrantAbility(event.target.value)}>
              <option value="">Choose an ability</option>
              {choices.abilities.map((ability) => (
                <option key={ability.key} value={ability.key}>
                  {ability.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`reason-${membershipId}`}>Why (a short note, optional)</label>
            <input
              id={`reason-${membershipId}`}
              value={grantReason}
              maxLength={200}
              onChange={(event) => setGrantReason(event.target.value)}
            />
          </div>
          <button type="submit" className="button" disabled={busy || !grantAbility}>
            Give the ability
          </button>
        </form>
      </section>
    </div>
  );
}
