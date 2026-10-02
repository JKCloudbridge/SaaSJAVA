"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ActionMessages, failureText, useAction } from "./useAction";

type Plan = components["schemas"]["PlanInfo"];
type Item = components["schemas"]["CatalogueItem"];
type LicenceType = components["schemas"]["LicenceTypeItem"];

type Loaded =
  | { kind: "loading" }
  | { kind: "ok"; plans: Plan[]; licenceTypes: LicenceType[]; features: Item[] }
  | { kind: "failed"; message: string };

/**
 * The catalogue the platform sells from: plans with their default licence quantities and features, the licence types
 * and the feature keys. Changing is for platform administrators and billing; the API refuses anybody else and the page
 * shows its words.
 */
export function PlansPanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [trialDays, setTrialDays] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [chosen, setChosen] = useState<Record<string, boolean>>({});
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

  const { busy, notice, problem, act } = useAction(reload);

  if (loaded.kind === "loading") {
    return <p aria-live="polite">Loading…</p>;
  }
  if (loaded.kind === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="plans-failed">
        {loaded.message}
      </p>
    );
  }
  const { plans, licenceTypes, features } = loaded;

  function savePlan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const licences: Record<string, number> = {};
    for (const type of licenceTypes) {
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
          features: features.filter((feature) => chosen[feature.key]).map((feature) => feature.key),
        },
      });
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

  return (
    <div className="stack">
      <h2>Plans</h2>
      <ActionMessages notice={notice} problem={problem} testId="plans" />
      <table className="table" data-testid="plans">
        <thead>
          <tr>
            <th>Plan</th>
            <th>Trial</th>
            <th>Licences</th>
            <th>Features</th>
          </tr>
        </thead>
        <tbody>
          {plans.map((plan) => (
            <tr key={plan.key} data-testid="plan-row">
              <td>
                {plan.name} <span className="mono">({plan.key})</span>
              </td>
              <td>{plan.trialDays ? `${plan.trialDays} days` : ""}</td>
              <td>
                {Object.entries(plan.licences)
                  .map(([type, quantity]) => `${type}: ${quantity}`)
                  .join(", ")}
              </td>
              <td>{plan.features.join(", ")}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <form className="form" onSubmit={savePlan} noValidate aria-label="Create or replace a plan">
        <h3>Create or replace a plan</h3>
        <div className="field">
          <label htmlFor="plan-key">Key (lower-case letters, digits, hyphens)</label>
          <input id="plan-key" type="text" value={key} onChange={(event) => setKey(event.target.value)} required />
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
        {licenceTypes.map((type) => (
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
        {features.map((feature) => (
          <label className="check" key={feature.key}>
            <input
              type="checkbox"
              checked={chosen[feature.key] ?? false}
              onChange={(event) => setChosen({ ...chosen, [feature.key]: event.target.checked })}
            />{" "}
            {feature.name}
          </label>
        ))}
        <button type="submit" className="button" disabled={busy}>
          Save the plan
        </button>
      </form>

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
        <h3>Licence types</h3>
        <p>{licenceTypes.map((type) => `${type.name} (${type.kind === "ADD_ON" ? "add-on" : "seat"})`).join(", ")}</p>
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
        <button type="submit" className="button" disabled={busy}>
          Add the licence type
        </button>
      </form>

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
        <h3>Features</h3>
        <p>{features.map((feature) => feature.name).join(", ")}</p>
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
        <button type="submit" className="button" disabled={busy}>
          Add the feature
        </button>
      </form>
    </div>
  );
}
