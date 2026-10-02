"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { AbilityPicker } from "./AbilityPicker";
import { DataAccessEditor } from "./DataAccessEditor";

type Policy = components["schemas"]["AccessPolicyView"];
type Ability = components["schemas"]["AbilityInfo"];
type LicenceType = components["schemas"]["LicenceTypeItem"];
type Pool = components["schemas"]["LicencePoolView"];

interface Loaded {
  policies: Policy[];
  abilities: Ability[];
  licenceTypes: LicenceType[];
  pools: Pool[];
}

interface Draft {
  id?: string;
  name: string;
  description: string;
  abilities: string[];
  requiredLicenceType: string;
}

const EMPTY: Draft = { name: "", description: "", abilities: [], requiredLicenceType: "" };

/**
 * The access policies of the organization the address names: abilities added to the members they are given to, optionally
 * using one licence of a type each. The page shows what the API answers (and the numbers of the licence pools when the caller
 * may see them) and its own words for every refusal; it never decides who may do what or whether a licence is free.
 */
export function AccessPoliciesPanel() {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [permissionsOf, setPermissionsOf] = useState<Policy | undefined>();

  const reload = useCallback(async () => {
    try {
      const [policies, abilities, types, pools] = await Promise.all([
        api.GET("/api/v1/access-policies"),
        api.GET("/api/v1/abilities"),
        api.GET("/api/v1/licence-types"),
        api.GET("/api/v1/licences"),
      ]);
      if (!policies.data) {
        setFailure(await failureText(policies.error, policies.response));
        return;
      }
      setFailure(undefined);
      setLoaded({
        policies: policies.data.data,
        abilities: abilities.data?.data ?? [],
        licenceTypes: types.data?.data ?? [],
        pools: pools.data?.data ?? [],
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
      abilities: draft.abilities,
      requiredLicenceType: draft.requiredLicenceType || undefined,
    };
    void act(async () => {
      const result = draft.id
        ? await api.PUT("/api/v1/access-policies/{policyId}", { params: { path: { policyId: draft.id } }, body })
        : await api.POST("/api/v1/access-policies", { body });
      if (result.response.ok) {
        setDraft(EMPTY);
      }
      return { error: result.error, response: result.response, text: draft.id ? "The access policy was saved." : "The access policy was created." };
    });
  }

  const remove = (policy: Policy) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/access-policies/{policyId}", {
        params: { path: { policyId: policy.id } },
      });
      return { error, response, text: "The access policy was removed." };
    });

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="policies-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const nameOf = (key: string) => loaded.abilities.find((ability) => ability.key === key)?.name ?? key;
  const poolOf = (type: string | undefined) => loaded.pools.find((pool) => pool.licenceType === type);

  return (
    <div className="stack">
      <p>
        An access policy adds abilities to the members it is given to; it never takes any away. A policy can need a licence:
        giving it to a member then uses one licence of that type, and taking it back or deactivating the member returns it.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="policies" />

      <section aria-labelledby="policy-form-heading">
        <h2 id="policy-form-heading">{draft.id ? "Change the access policy" : "Create an access policy"}</h2>
        <form className="form" onSubmit={save} noValidate>
          <div className="field">
            <label htmlFor="policy-name">Name</label>
            <input
              id="policy-name"
              value={draft.name}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="policy-description">Description</label>
            <input
              id="policy-description"
              value={draft.description}
              maxLength={500}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="policy-licence">Needs a licence of type</label>
            <select
              id="policy-licence"
              value={draft.requiredLicenceType}
              onChange={(event) => setDraft({ ...draft, requiredLicenceType: event.target.value })}
            >
              <option value="">No licence needed</option>
              {loaded.licenceTypes.map((type) => (
                <option key={type.key} value={type.key}>
                  {type.name}
                  {type.kind === "ADD_ON" ? " (add-on)" : ""}
                </option>
              ))}
            </select>
          </div>
          <AbilityPicker
            legend="Abilities it adds"
            abilities={loaded.abilities}
            selected={draft.abilities}
            onChange={(abilities) => setDraft({ ...draft, abilities })}
          />
          <button type="submit" className="button" disabled={busy}>
            {draft.id ? "Save the access policy" : "Create the access policy"}
          </button>{" "}
          {draft.id ? (
            <button type="button" className="link-button" onClick={() => setDraft(EMPTY)}>
              Cancel
            </button>
          ) : null}
        </form>
      </section>

      <section aria-labelledby="policies-heading">
        <h2 id="policies-heading">Access policies</h2>
        {loaded.policies.length === 0 ? <p>There are no access policies yet.</p> : null}
        {loaded.policies.length > 0 ? (
          <table className="table" data-testid="policies">
            <thead>
              <tr>
                <th>Name</th>
                <th>Abilities</th>
                <th>Licence</th>
                <th>Members</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {loaded.policies.map((policy) => {
                const pool = poolOf(policy.requiredLicenceType);
                return (
                  <tr key={policy.id} data-testid="policy-row">
                    <td>
                      {policy.name}
                      {policy.description ? <div className="hint">{policy.description}</div> : null}
                    </td>
                    <td>{policy.abilities.length === 0 ? "none" : policy.abilities.map(nameOf).join(", ")}</td>
                    <td data-testid="policy-licence">
                      {policy.requiredLicenceType ?? "none needed"}
                      {pool ? ` (${pool.available} of ${pool.quantity} free)` : ""}
                    </td>
                    <td>
                      {policy.members}
                      {(policy.groups ?? 0) > 0 ? ` (and ${policy.groups} group${policy.groups === 1 ? "" : "s"})` : ""}
                    </td>
                    <td>
                      <button type="button" className="link-button" onClick={() => setPermissionsOf(policy)}>
                        Permissions on data
                      </button>{" "}
                      <button
                        type="button"
                        className="link-button"
                        disabled={busy}
                        onClick={() =>
                          setDraft({
                            id: policy.id,
                            name: policy.name,
                            description: policy.description,
                            abilities: policy.abilities,
                            requiredLicenceType: policy.requiredLicenceType ?? "",
                          })
                        }
                      >
                        Change
                      </button>{" "}
                      <button type="button" className="link-button" disabled={busy} onClick={() => void remove(policy)}>
                        Remove
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : null}
      </section>

      {permissionsOf ? (
        <DataAccessEditor
          key={permissionsOf.id}
          target={{ kind: "policy", id: permissionsOf.id }}
          title={`Permissions on data: ${permissionsOf.name}`}
        />
      ) : null}
    </div>
  );
}
