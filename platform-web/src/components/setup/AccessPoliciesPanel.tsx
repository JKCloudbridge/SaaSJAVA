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
  name: string;
  description: string;
  abilities: string[];
  requiredLicenceType: string;
}

const EMPTY: Draft = { name: "", description: "", abilities: [], requiredLicenceType: "" };

const draftOf = (policy: Policy): Draft => ({
  name: policy.name,
  description: policy.description,
  abilities: policy.abilities,
  requiredLicenceType: policy.requiredLicenceType ?? "",
});

const bodyOf = (draft: Draft) => ({
  name: draft.name,
  description: draft.description,
  abilities: draft.abilities,
  requiredLicenceType: draft.requiredLicenceType || undefined,
});

/**
 * The access policies of the organization the address names: abilities added to the members they are given to, optionally
 * using one licence of a type each. A list; a row opens the policy (details, abilities, permissions on data); New opens the
 * same page as a form. The page shows what the API answers (and the numbers of the licence pools when the caller may see
 * them) and its own words for every refusal; it never decides who may do what or whether a licence is free.
 */
export function AccessPoliciesPanel() {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const offersManage = useOffers("access.manage");

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

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const result = await api.POST("/api/v1/access-policies", { body: bodyOf(draft) });
      if (result.response.ok) {
        screen.back(true);
      }
      return { error: result.error, response: result.response, text: "The access policy was created." };
    });
  }

  const save = (policy: Policy) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void act(async () => {
      const result = await api.PUT("/api/v1/access-policies/{policyId}", {
        params: { path: { policyId: policy.id } },
        body: bodyOf(draft),
      });
      if (result.response.ok) {
        setEditing(false);
      }
      return { error: result.error, response: result.response, text: "The access policy was saved." };
    });
  };

  const remove = (policy: Policy) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/access-policies/{policyId}", {
        params: { path: { policyId: policy.id } },
      });
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The access policy was removed." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="policies" />;

  if (screen.creating && loaded) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Access policies"
        onBack={cancel}
        title="New access policy"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="policy-form" className="button" disabled={busy}>
              Create the access policy
            </button>
          </>
        }
      >
        <form id="policy-form" onSubmit={create} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              <PolicyFields loaded={loaded} draft={draft} onDraft={setDraft} />
            </DetailGrid>
          </DetailSection>
          <DetailSection title="Abilities it adds">
            <AbilityPicker
              legend="Abilities it adds"
              abilities={loaded.abilities}
              selected={draft.abilities}
              onChange={(abilities) => setDraft({ ...draft, abilities })}
            />
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const policy = loaded?.policies.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && loaded && !policy) {
    return (
      <MissingDetail
        listLabel="Access policies"
        onBack={() => screen.back()}
        what="This access policy does not exist any more."
      />
    );
  }

  const poolOf = (type: string | undefined | null) => loaded?.pools.find((pool) => pool.licenceType === type);

  if (loaded && policy) {
    const nameOf = (key: string) => loaded.abilities.find((ability) => ability.key === key)?.name ?? key;
    const pool = poolOf(policy.requiredLicenceType);
    const groups = policy.groups ?? 0;
    return (
      <DetailPage
        listLabel="Access policies"
        onBack={() => screen.back()}
        title={policy.name}
        notices={messages}
        testId="policy-detail"
        actions={
          editing ? (
            <>
              <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
                Cancel
              </button>
              <button type="submit" form="policy-form" className="button" disabled={busy}>
                Save the access policy
              </button>
            </>
          ) : policy.managed ? null : (
            <>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(policy)}>
                Remove
              </button>
              <button
                type="button"
                className="button"
                disabled={busy}
                onClick={() => {
                  setDraft(draftOf(policy));
                  setEditing(true);
                }}
              >
                Change
              </button>
            </>
          )
        }
      >
        <form id="policy-form" onSubmit={save(policy)} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              {editing ? (
                <PolicyFields loaded={loaded} draft={draft} onDraft={setDraft} />
              ) : (
                <>
                  <DetailItem label="Name">{policy.name}</DetailItem>
                  {policy.managed ? (
                    <DetailItem label="Comes with" wide>
                      <span data-testid="policy-shipped">
                        The platform. You can give it to members and groups; it cannot be changed or removed.
                      </span>
                    </DetailItem>
                  ) : null}
                  <DetailItem label="Licence">
                    <span data-testid="policy-licence">
                      {policy.requiredLicenceType ?? "none needed"}
                      {pool ? ` (${pool.available} of ${pool.quantity} free)` : ""}
                    </span>
                  </DetailItem>
                  <DetailItem label="Description" wide tone={policy.description ? undefined : "muted"}>
                    {policy.description || "—"}
                  </DetailItem>
                  <DetailItem label="Members">
                    {policy.members}
                    {groups > 0 ? ` (and ${groups} group${groups === 1 ? "" : "s"})` : ""}
                  </DetailItem>
                </>
              )}
            </DetailGrid>
          </DetailSection>
          <DetailSection title="Abilities it adds">
            {editing ? (
              <AbilityPicker
                legend="Abilities it adds"
                abilities={loaded.abilities}
                selected={draft.abilities}
                onChange={(abilities) => setDraft({ ...draft, abilities })}
              />
            ) : (
              <p className="dl-value" data-testid="policy-abilities">
                {policy.abilities.length === 0 ? "none" : policy.abilities.map(nameOf).join(", ")}
              </p>
            )}
          </DetailSection>
        </form>
        <DetailSection title="Permissions on data">
          <DataAccessEditor
            key={policy.id}
            target={{ kind: "policy", id: policy.id }}
            title={`Permissions on data: ${policy.name}`}
            readOnly={policy.managed}
          />
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<Policy>
      title="Access policies"
      items={loaded?.policies}
      failure={failure}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.name}
      primaryExtra={(row) => row.description || null}
      columns={[
        { key: "abilities", header: "Abilities", kind: "muted", cell: (row) => row.abilities.length },
        { key: "members", header: "Members", kind: "muted", cell: (row) => row.members },
        {
          key: "licence",
          header: "Licence",
          kind: "tag",
          cell: (row) =>
            row.requiredLicenceType ? <Tag tone="on">Needs a {row.requiredLicenceType} licence</Tag> : <Tag>No licence</Tag>,
        },
        {
          key: "shipped",
          header: "Kind",
          kind: "tag",
          cell: (row) => (row.managed ? <Tag tone="quiet">Comes with the platform</Tag> : <Tag>Your own</Tag>),
        },
      ]}
      onOpen={(row) => {
        setEditing(false);
        screen.open(row.id);
      }}
      searchText={(row) => `${row.name} ${row.description} ${row.requiredLicenceType ?? ""}`}
      onNew={
        offersManage
          ? () => {
              setDraft(EMPTY);
              screen.create();
            }
          : undefined
      }
      newLabel="New access policy"
      notices={messages}
      emptyText="There are no access policies yet."
      testId="policies"
      rowTestId="policy-row"
    />
  );
}

/** The fields of an access policy as a form: name, description, the licence it needs. */
function PolicyFields({
  loaded,
  draft,
  onDraft,
}: {
  loaded: Loaded;
  draft: Draft;
  onDraft: (next: Draft) => void;
}): ReactNode {
  return (
    <>
      <div className="field">
        <label htmlFor="policy-name">Name</label>
        <input
          id="policy-name"
          value={draft.name}
          maxLength={80}
          onChange={(event) => onDraft({ ...draft, name: event.target.value })}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="policy-licence">Needs a licence of type</label>
        <select
          id="policy-licence"
          value={draft.requiredLicenceType}
          onChange={(event) => onDraft({ ...draft, requiredLicenceType: event.target.value })}
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
      <div className="field span-2">
        <label htmlFor="policy-description">Description</label>
        <input
          id="policy-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => onDraft({ ...draft, description: event.target.value })}
        />
      </div>
    </>
  );
}
