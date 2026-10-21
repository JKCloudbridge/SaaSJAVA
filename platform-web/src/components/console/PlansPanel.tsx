"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ActionMessages, failureText } from "./useAction";

type Plan = components["schemas"]["PlanInfo"];
type Item = components["schemas"]["CatalogueItem"];
type LicenceType = components["schemas"]["LicenceTypeItem"];

type Loaded =
  | { kind: "loading" }
  | { kind: "ok"; plans: Plan[]; licenceTypes: LicenceType[]; features: Item[] }
  | { kind: "failed"; message: string };

/**
 * The catalogue the platform sells from, as a list of plans with their default licence quantities and features; a row opens
 * the plan, New makes one. Below the list: the licence types and the feature keys, each with the form that adds one.
 * Changing is for platform administrators and billing; the API refuses anybody else and the page shows its words.
 */
export function PlansPanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [trialDays, setTrialDays] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [chosen, setChosen] = useState<Record<string, boolean>>({});
  const [editing, setEditing] = useState(false);
  const [newType, setNewType] = useState({ key: "", name: "", kind: "SEAT" });
  const [newFeature, setNewFeature] = useState({ key: "", name: "" });

  const reload = useCallback(async () => {
    try {
      const [p, t, f] = await Promise.all([
        api.GET("/api/v1/platform/plans"),
        api.GET("/api/v1/platform/licence-types"),
        api.GET("/api/v1/platform/features"),
      ]);
      if (p.data && t.data && f.data) {
        setLoaded({ kind: "ok", plans: p.data.data, licenceTypes: t.data.data, features: f.data.data });
      } else {
        const bad = !p.data ? p : !t.data ? t : f;
        setLoaded({ kind: "failed", message: await failureText(bad.error, bad.response) });
      }
    } catch {
      setLoaded({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const ok = loaded.kind === "ok" ? loaded : undefined;

  function fill(plan: Plan | undefined) {
    setKey(plan?.key ?? "");
    setName(plan?.name ?? "");
    setTrialDays(plan?.trialDays ? String(plan.trialDays) : "");
    setQuantities(Object.fromEntries(Object.entries(plan?.licences ?? {}).map(([type, count]) => [type, String(count)])));
    setChosen(Object.fromEntries((plan?.features ?? []).map((feature) => [feature, true])));
  }

  function savePlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ok) {
      return;
    }
    const licences: Record<string, number> = {};
    for (const type of ok.licenceTypes) {
      const quantity = quantities[type.key];
      if (quantity !== undefined && quantity !== "") {
        licences[type.key] = Number(quantity);
      }
    }
    void act(async () => {
      const { error, response } = await api.PUT("/api/v1/platform/plans/{key}", {
        params: { path: { key } },
        body: {
          name,
          trialDays: trialDays ? Number(trialDays) : undefined,
          licences,
          features: ok.features.filter((feature) => chosen[feature.key]).map((feature) => feature.key),
        },
      });
      if (response.ok) {
        if (screen.creating) {
          screen.back(true);
        } else {
          setEditing(false);
        }
      }
      return { error, response, text: "The plan was saved." };
    });
  }

  const addItem = (kind: "licence-types" | "features", item: { key: string; name: string; kind?: string }) =>
    act(async () => {
      const { error, response } =
        kind === "licence-types"
          ? await api.POST("/api/v1/platform/licence-types", { body: item })
          : await api.POST("/api/v1/platform/features", { body: item });
      return { error, response, text: kind === "licence-types" ? "The licence type was added." : "The feature was added." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="plans" />;

  const planFields = (existing: Plan | undefined) =>
    ok ? (
      <>
        <div className="field">
          <label htmlFor="plan-key">Key (lower-case letters, digits, hyphens)</label>
          <input
            id="plan-key"
            type="text"
            value={key}
            disabled={Boolean(existing)}
            onChange={(event) => setKey(event.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="plan-name">Name</label>
          <input id="plan-name" type="text" value={name} onChange={(event) => setName(event.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="plan-trial">Trial days (empty: not a trial)</label>
          <input
            id="plan-trial"
            type="number"
            min={1}
            max={365}
            value={trialDays}
            onChange={(event) => setTrialDays(event.target.value)}
          />
        </div>
        {ok.licenceTypes.map((type) => (
          <div className="field" key={type.key}>
            <label htmlFor={`plan-licence-${type.key}`}>{type.name} licences</label>
            <input
              id={`plan-licence-${type.key}`}
              type="number"
              min={0}
              value={quantities[type.key] ?? ""}
              onChange={(event) => setQuantities({ ...quantities, [type.key]: event.target.value })}
            />
          </div>
        ))}
        <div className="field span-2">
          <span className="field-label">Features</span>
          {ok.features.map((feature) => (
            <label className="check" key={feature.key}>
              <input
                type="checkbox"
                checked={chosen[feature.key] ?? false}
                onChange={(event) => setChosen({ ...chosen, [feature.key]: event.target.checked })}
              />{" "}
              {feature.name}
              {/* The platform's own words, from the API, shown when a feature the plan has is being taken away. */}
              {existing?.features.includes(feature.key) && !(chosen[feature.key] ?? false) && feature.removalNote ? (
                <span className="hint" role="status" data-testid={`removal-note-${feature.key}`}>
                  {" "}
                  {feature.removalNote}
                </span>
              ) : null}
            </label>
          ))}
        </div>
      </>
    ) : null;

  if (screen.creating && ok) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Plans"
        onBack={cancel}
        title="New plan"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="plan-form" className="button" disabled={busy}>
              Save the plan
            </button>
          </>
        }
      >
        <form id="plan-form" onSubmit={savePlan} noValidate aria-label="Create or replace a plan">
          <DetailSection title="Detail">
            <DetailGrid>{planFields(undefined)}</DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const plan = ok?.plans.find((candidate) => candidate.key === screen.openId);
  if (screen.openId && ok && !plan) {
    return <MissingDetail listLabel="Plans" onBack={() => screen.back()} what="This plan does not exist." />;
  }

  if (ok && plan) {
    const featureNames = plan.features.map((feature) => ok.features.find((item) => item.key === feature)?.name ?? feature);
    return (
      <DetailPage
        listLabel="Plans"
        onBack={() => screen.back()}
        title={plan.name}
        notices={messages}
        testId="plan-detail"
        actions={
          editing ? (
            <>
              <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
                Cancel
              </button>
              <button type="submit" form="plan-form" className="button" disabled={busy}>
                Save the plan
              </button>
            </>
          ) : (
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={() => {
                fill(plan);
                setEditing(true);
              }}
            >
              Change
            </button>
          )
        }
      >
        <form id="plan-form" onSubmit={savePlan} noValidate aria-label="Create or replace a plan">
          <DetailSection title="Detail">
            <DetailGrid>
              {editing ? (
                planFields(plan)
              ) : (
                <>
                  <DetailItem label="Name">{plan.name}</DetailItem>
                  <DetailItem label="Key" tone="mono">
                    {plan.key}
                  </DetailItem>
                  <DetailItem label="Trial">{plan.trialDays ? `${plan.trialDays} days` : "Not a trial"}</DetailItem>
                  <DetailItem label="Licences" tone={Object.keys(plan.licences).length === 0 ? "muted" : undefined}>
                    {Object.keys(plan.licences).length === 0
                      ? "none"
                      : Object.entries(plan.licences)
                          .map(([type, quantity]) => `${type}: ${quantity}`)
                          .join(", ")}
                  </DetailItem>
                  <DetailItem label="Features" wide tone={featureNames.length === 0 ? "muted" : undefined}>
                    {featureNames.length === 0 ? "none" : featureNames.join(", ")}
                  </DetailItem>
                </>
              )}
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const catalogue = ok ? (
    <>
      <DetailSection title="Licence types" stack>
        <p>{ok.licenceTypes.map((type) => `${type.name} (${type.kind === "ADD_ON" ? "add-on" : "seat"})`).join(", ")}</p>
        <form
          className="form"
          noValidate
          aria-label="Add a licence type"
          onSubmit={(event) => {
            event.preventDefault();
            void addItem("licence-types", newType);
            setNewType({ key: "", name: "", kind: "SEAT" });
          }}
        >
          <div className="field">
            <label htmlFor="type-key">Key</label>
            <input
              id="type-key"
              type="text"
              value={newType.key}
              onChange={(event) => setNewType({ ...newType, key: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="type-name">Name</label>
            <input
              id="type-name"
              type="text"
              value={newType.name}
              onChange={(event) => setNewType({ ...newType, name: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="type-kind">Kind</label>
            <select
              id="type-kind"
              value={newType.kind}
              onChange={(event) => setNewType({ ...newType, kind: event.target.value })}
            >
              <option value="SEAT">Seat (a profile can belong to it)</option>
              <option value="ADD_ON">Add-on (sold on top, for example for an access policy)</option>
            </select>
          </div>
          <div>
            <button type="submit" className="button" disabled={busy}>
              Add the licence type
            </button>
          </div>
        </form>
      </DetailSection>

      <DetailSection title="Features" stack>
        <p>{ok.features.map((feature) => feature.name).join(", ")}</p>
        <form
          className="form"
          noValidate
          aria-label="Add a feature"
          onSubmit={(event) => {
            event.preventDefault();
            void addItem("features", newFeature);
            setNewFeature({ key: "", name: "" });
          }}
        >
          <div className="field">
            <label htmlFor="feature-key">Key</label>
            <input
              id="feature-key"
              type="text"
              value={newFeature.key}
              onChange={(event) => setNewFeature({ ...newFeature, key: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="feature-name">Name</label>
            <input
              id="feature-name"
              type="text"
              value={newFeature.name}
              onChange={(event) => setNewFeature({ ...newFeature, name: event.target.value })}
            />
          </div>
          <div>
            <button type="submit" className="button" disabled={busy}>
              Add the feature
            </button>
          </div>
        </form>
      </DetailSection>
    </>
  ) : null;

  return (
    <ListPage<Plan>
      title="Plans"
      intro="The plans the platform sells, with their default licences and features. Open a plan to change it; below are the licence types and the feature keys."
      items={ok?.plans}
      failure={loaded.kind === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.key}
      primaryHeader="Plan"
      primary={(row) => row.name}
      primaryExtra={(row) => <span className="mono">{row.key}</span>}
      onOpen={(row) => {
        setEditing(false);
        screen.open(row.key);
      }}
      columns={[
        {
          key: "licences",
          header: "Licences",
          kind: "muted",
          cell: (row) =>
            Object.entries(row.licences)
              .map(([type, quantity]) => `${type}: ${quantity}`)
              .join(", "),
        },
        { key: "features", header: "Features", kind: "muted", cell: (row) => row.features.join(", ") },
        {
          key: "trial",
          header: "Trial",
          kind: "tag",
          cell: (row) => (row.trialDays ? <Tag tone="on">{row.trialDays} days</Tag> : null),
        },
      ]}
      searchText={(row) => `${row.name} ${row.key}`}
      onNew={() => {
        fill(undefined);
        setEditing(false);
        screen.create();
      }}
      newLabel="New plan"
      notices={messages}
      below={catalogue}
      emptyText="There are no plans yet."
      testId="plans"
      rowTestId="plan-row"
    />
  );
}
