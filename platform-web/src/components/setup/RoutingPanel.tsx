"use client";

import { useCallback, useEffect, useState } from "react";
import { DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";

type Routing = components["schemas"]["RoutingView"];
type Rule = components["schemas"]["RoutingRuleView"];

/** A rule is chosen in the list by its combination; a new one has no identifier yet. */
const NEW = "new";

/**
 * Which application a document also belongs to, by its number (MC1.1, ADR-0259). A rule names an Identification, an
 * optional Part A and an application the API offers; the platform writes the application on a document when the
 * document's number is made. The page offers what the API answers (the applications, the Identification values) and
 * shows its words for every refusal; it holds no list of applications, identifications or parts and decides nothing.
 */
export function RoutingPanel({ view, onChanged }: { view: Routing; onChanged: (next: Routing) => void }) {
  const [open, setOpen] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | undefined>();
  const selected = open && open !== NEW ? view.rules.find((rule) => rule.id === open) : undefined;
  const identificationLabel = useCallback(
    (code: string) => view.identifications.find((one) => one.value === code)?.label ?? code,
    [view.identifications],
  );
  if (open === NEW || selected) {
    return (
      <RuleEditor
        key={open}
        view={view}
        rule={selected}
        identificationLabel={identificationLabel}
        onBack={() => setOpen(null)}
        onDone={(next, words) => {
          onChanged(next);
          setNotice(words);
          setOpen(null);
        }}
      />
    );
  }
  return (
    <ListPage<Rule>
      embedded
      title="Application routing"
      items={view.rules}
      rowKey={(rule) => rule.id}
      testId="routing-list"
      primaryHeader="Identification"
      primary={(rule) => identificationLabel(rule.identification)}
      columns={[
        { key: "part", header: "Part A", cell: (rule) => rule.partA || "Every Part A" },
        {
          key: "application",
          header: "Belongs also to",
          cell: (rule) => rule.applicationLabel ?? "Not available in this organization",
        },
      ]}
      onOpen={(rule) => setOpen(rule.id)}
      onNew={view.applications.length > 0 ? () => setOpen(NEW) : undefined}
      newLabel="New rule"
      notices={notice ? <p role="status">{notice}</p> : undefined}
      searchText={(rule) => `${identificationLabel(rule.identification)} ${rule.partA} ${rule.applicationLabel ?? ""}`}
      emptyText="No rule yet. A document belongs to Document Management alone until a rule sends it to another application."
    />
  );
}

function RuleEditor({
  view,
  rule,
  identificationLabel,
  onBack,
  onDone,
}: {
  view: Routing;
  rule: Rule | undefined;
  identificationLabel: (code: string) => string;
  onBack: () => void;
  onDone: (next: Routing, words: string) => void;
}) {
  const [identification, setIdentification] = useState(rule?.identification ?? "");
  const [partA, setPartA] = useState(rule?.partA ?? "");
  const [application, setApplication] = useState(rule?.available ? rule.application : (view.applications[0]?.application ?? ""));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();

  const run = async (work: () => Promise<{ next?: Routing; words: string; failure?: string }>) => {
    setBusy(true);
    setProblem(undefined);
    try {
      const result = await work();
      if (result.next) onDone(result.next, result.words);
      else setProblem(result.failure);
    } catch {
      setProblem("The page could not reach the platform. Try again.");
    } finally {
      setBusy(false);
    }
  };
  const save = () =>
    run(async () => {
      const result = await api.PUT("/api/v1/document-routing", {
        body: { identification, partA: partA.trim(), application, version: rule ? rule.version : undefined },
      });
      return result.data
        ? { next: result.data.data, words: "The rule was saved. Documents that already have their number are not moved." }
        : { words: "", failure: problemText(result.error, result.response) };
    });
  const remove = () =>
    run(async () => {
      if (!rule) return { words: "" };
      const result = await api.DELETE("/api/v1/document-routing/{ruleId}", {
        params: { path: { ruleId: rule.id }, query: { version: rule.version } },
      });
      return result.data
        ? { next: result.data.data, words: "The rule was removed. Documents that already have their number are not moved." }
        : { words: "", failure: problemText(result.error, result.response) };
    });

  const title = rule ? `${identificationLabel(rule.identification)} ${rule.partA}`.trim() : "New rule";
  return (
    <DetailPage
      title={title}
      listLabel="Application routing"
      onBack={onBack}
      notices={problem ? <p role="alert" className="form-message">{problem}</p> : undefined}
      actions={
        <>
          {rule ? (
            <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove()}>
              Remove rule
            </button>
          ) : null}
          <button type="button" className="button" disabled={busy || !identification || !application} onClick={() => void save()}>
            Save rule
          </button>
        </>
      }
    >
      <DetailSection title="Rule" stack>
        <p>
          A document whose number is made with this identification and Part A also belongs to the application you choose.
          The platform decides this once, when the number is made; a rule you change or remove later moves no document that
          already has its number.
        </p>
        <label className="field">
          Identification
          <select value={identification} disabled={Boolean(rule)} onChange={(event) => setIdentification(event.target.value)}>
            <option value="">Choose</option>
            {view.identifications.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
                {option.active ? "" : " (switched off)"}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Part A
          <input
            value={partA}
            maxLength={10}
            disabled={Boolean(rule)}
            onChange={(event) => setPartA(event.target.value.toUpperCase())}
          />
        </label>
        <p className="hint">
          Leave Part A empty for every Part A. When a document matches a rule for its exact Part A and another for the
          identification alone, the exact one is used.
        </p>
        <label className="field">
          Also belongs to
          <select value={application} onChange={(event) => setApplication(event.target.value)}>
            {view.applications.map((option) => (
              <option key={option.application} value={option.application}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        {rule && !rule.available ? (
          <p className="hint">
            This organization does not have the application of this rule now, so the rule does nothing until it is given again.
            You can choose another application or remove the rule.
          </p>
        ) : null}
      </DetailSection>
    </DetailPage>
  );
}

/** Loads the rules; says nothing when the organization has nothing to route to or the person may not manage them. */
export function useRouting() {
  const [view, setView] = useState<Routing | null | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const load = useCallback(async () => {
    try {
      const result = await api.GET("/api/v1/document-routing");
      if (result.data) {
        setView(result.data.data);
        setFailure(undefined);
      } else if (result.response.status === 403 || result.response.status === 404) {
        setView(null);
      } else {
        setFailure(problemText(result.error, result.response));
      }
    } catch {
      setFailure("The page could not reach the platform. Try again.");
    }
  }, []);
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);
  return { view, setView, failure };
}
