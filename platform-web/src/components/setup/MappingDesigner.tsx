"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import { Palette, type PaletteItem } from "@/components/builder/Palette";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { Integration } from "@/lib/integrations/model";
import {
  addField,
  changeField,
  designerDraftOf,
  mappingPlaceWords,
  moveField,
  newField,
  removeField,
  sampleOf,
  specOf,
  unsaved,
  unusedFields,
  type DesignerDraft,
  type Delivery,
  type MappingCatalogue,
  type MappingChoice,
  type MappingView,
  type ObjectField,
} from "@/lib/integrations/mappingModel";
import { useOffers } from "@/lib/session/SessionProvider";

interface DryResult {
  direction: string;
  result: Record<string, string>;
  skipped: { field: string; reason: string; words: string }[];
  problems: Record<string, string[]>;
}

/** The API's words for a refusal, with every problem it lists under the screen's own place names. */
async function words(error: unknown, response: Response): Promise<string> {
  const failure = failureFromResponse(error, response);
  const lines = Object.entries(failure.fields ?? {}).flatMap(([field, problems]) =>
    problems.map((problem) => `${mappingPlaceWords(field)}: ${problem}`),
  );
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

const inputOf = (catalogue: MappingCatalogue, conversion: string) =>
  catalogue.conversions.find((one) => one.key === conversion)?.input;

const labelOf = (choices: MappingChoice[] | undefined, key: string) => choices?.find((one) => one.key === key)?.label ?? key;

/**
 * The designer of the mapping of one object of a connection (Sprint 28, ADR-0217, ADR-0218): choose the object, add the
 * fields it offers from the palette (or by dropping them: a drag is never the only way), say for each what it is called on
 * the other side, which way it goes, how it is converted and, if a conversion is not enough, an expression; try it on a
 * sample with the real mapper; save the draft; publish it; read the versions and what was sent.
 *
 * Presentation only. The choices come from the API's catalogue; the fields are those the API says the person may read;
 * the API judges every definition and its words are shown for every refusal. Nothing typed is kept anywhere but the
 * request, and a sample goes nowhere but the dry run.
 */
export function MappingDesigner({ connection }: { connection: Integration }) {
  const offersManage = useOffers("mappings.manage");
  const [catalogue, setCatalogue] = useState<MappingCatalogue | undefined>();
  const [mappings, setMappings] = useState<MappingView[] | undefined>();
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [object, setObject] = useState<string>(connection.scope[0]?.object ?? "");
  const [fields, setFields] = useState<ObjectField[]>([]);
  const [draft, setDraft] = useState<DesignerDraft | undefined>();
  const [sample, setSample] = useState<Record<string, string>>({});
  const [direction, setDirection] = useState<"OUTBOUND" | "INBOUND">("OUTBOUND");
  const [dry, setDry] = useState<DryResult | undefined>();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  const view = useMemo(() => mappings?.find((one) => one.object === object), [mappings, object]);

  const load = useCallback(async () => {
    try {
      const [kinds, list, sent] = await Promise.all([
        api.GET("/api/v1/integrations/mapping-catalogue"),
        api.GET("/api/v1/integrations/{name}/mappings", { params: { path: { name: connection.name } } }),
        api.GET("/api/v1/integrations/{name}/deliveries", { params: { path: { name: connection.name }, query: { limit: 20 } } }),
      ]);
      if (!list.data) {
        setProblem(await words(list.error, list.response));
        return;
      }
      setCatalogue(kinds.data?.data);
      setMappings(list.data.data);
      setDeliveries(sent.data?.data ?? []);
    } catch {
      setProblem(COMMON_TEXT.network);
    }
  }, [connection.name]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  // The object's fields, as the API says this person may read them.
  useEffect(() => {
    if (!object) {
      return;
    }
    let live = true;
    void api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName: object } } }).then((result) => {
      if (live) {
        setFields((result.data?.data.fields ?? []).filter((field) => !field.retired && field.type !== "MULTI_PICKLIST"));
      }
    });
    return () => {
      live = false;
    };
  }, [object]);

  // Start from what exists for the object whenever the object or the saved mapping changes.
  useEffect(() => {
    if (mappings && catalogue) {
      setDraft(designerDraftOf(view, catalogue));
      setDry(undefined);
    }
  }, [mappings, catalogue, view]);

  const run = async (work: () => Promise<{ error: unknown; response: Response; text: string }>) => {
    setBusy(true);
    setNotice(undefined);
    setProblem(undefined);
    try {
      const outcome = await work();
      if (outcome.response.ok) {
        setNotice(outcome.text);
        await load();
      } else {
        setProblem(await words(outcome.error, outcome.response));
      }
    } catch {
      setProblem(COMMON_TEXT.network);
    } finally {
      setBusy(false);
    }
  };

  if (connection.scope.length === 0) {
    return (
      <DetailSection title="Mapping">
        <p className="dl-value muted">Give the connection something to touch before mapping an object.</p>
      </DetailSection>
    );
  }
  if (!catalogue || !draft || !mappings) {
    return (
      <DetailSection title="Mapping">
        {problem ? (
          <p className="form-message" role="alert">
            {problem}
          </p>
        ) : (
          <p className="dl-value muted">Loading…</p>
        )}
      </DetailSection>
    );
  }

  const update = (next: DesignerDraft) => setDraft(next);
  const palette: PaletteItem[] = unusedFields(fields, draft).map((field) => ({
    key: field.apiName,
    label: field.label,
    title: field.apiName,
  }));
  const pick = (key: string) => {
    const field = fields.find((one) => one.apiName === key);
    if (field) {
      update(addField(draft, newField(field, catalogue, draft.fields.map((one) => one.external))));
    }
  };

  const save = () =>
    run(async () => {
      const result = await api.PUT("/api/v1/integrations/{name}/mappings/{object}", {
        params: { path: { name: connection.name, object } },
        body: { entity: draft.entity, definition: specOf(draft), version: draft.version },
      });
      return { error: result.error, response: result.response, text: "The draft was saved. It does nothing until it is published." };
    });
  const publish = () =>
    run(async () => {
      const result = await api.POST("/api/v1/integrations/{name}/mappings/{object}/publish", {
        params: { path: { name: connection.name, object } },
        body: { version: view?.draft?.version ?? 0 },
      });
      return { error: result.error, response: result.response, text: "The mapping is live." };
    });
  const stop = () =>
    run(async () => {
      const result = await api.POST("/api/v1/integrations/{name}/mappings/{object}/stop", {
        params: { path: { name: connection.name, object } },
      });
      return { error: result.error, response: result.response, text: "The mapping was stopped." };
    });
  const discard = () =>
    run(async () => {
      const result = await api.DELETE("/api/v1/integrations/{name}/mappings/{object}/draft", {
        params: { path: { name: connection.name, object }, query: { version: view?.draft?.version ?? 0 } },
      });
      return { error: result.error, response: result.response, text: "The draft was discarded." };
    });
  const tryIt = async () => {
    setBusy(true);
    setProblem(undefined);
    try {
      const result = await api.POST("/api/v1/integrations/{name}/mappings/{object}/dry-run", {
        params: { path: { name: connection.name, object } },
        body: { entity: draft.entity, definition: specOf(draft), direction, values: sampleOf(sample) },
      });
      if (result.data) {
        setDry(result.data.data);
      } else {
        setProblem(await words(result.error, result.response));
      }
    } catch {
      setProblem(COMMON_TEXT.network);
    } finally {
      setBusy(false);
    }
  };

  const dirty = unsaved(draft, view, catalogue);
  const sampleNames = draft.fields
    .filter((field) => (direction === "OUTBOUND" ? field.direction !== "INBOUND" : field.direction !== "OUTBOUND"))
    .map((field) => (direction === "OUTBOUND" ? field.platform : field.external));

  return (
    <DetailSection title="Mapping">
      <div className="stack" data-testid="mapping-designer">
        {notice ? <p role="status">{notice}</p> : null}
        {problem ? (
          <p className="form-message" role="alert">
            {problem}
          </p>
        ) : null}

        <div className="split">
          <label className="field">
            <span>Object</span>
            <select value={object} onChange={(event) => setObject(event.target.value)} aria-label="Object to map">
              {connection.scope.map((one) => (
                <option key={one.object} value={one.object}>
                  {one.object}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Entity on the other side</span>
            <input
              value={draft.entity}
              disabled={Boolean(view)}
              onChange={(event) => update({ ...draft, entity: event.target.value })}
              aria-label="Entity on the other side"
            />
          </label>
          <label className="field">
            <span>When both sides changed a field, the winner is</span>
            <select value={draft.conflict} onChange={(event) => update({ ...draft, conflict: event.target.value })}>
              {catalogue.conflicts.map((one) => (
                <option key={one.key} value={one.key} title={one.hint}>
                  {one.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Field that says when the entity last changed</span>
            <input
              value={draft.changedAtField}
              onChange={(event) => update({ ...draft, changedAtField: event.target.value })}
              aria-label="Changed-at field"
            />
          </label>
        </div>
        <div className="split">
          <label>
            <input
              type="checkbox"
              checked={draft.inboundCreate}
              onChange={(event) => update({ ...draft, inboundCreate: event.target.checked })}
            />{" "}
            A change for an unknown entity may make a record
          </label>
          <label>
            <input
              type="checkbox"
              checked={draft.inboundUpdate}
              onChange={(event) => update({ ...draft, inboundUpdate: event.target.checked })}
            />{" "}
            A change for a known entity may change its record
          </label>
        </div>

        <div className="split">
          <Palette label="Fields of the object" categories={[{ id: "fields", label: "Fields", items: palette, empty: "Every field is mapped." }]} onPick={(item) => pick(item.key)} searchable />
          <DropZone
            className="canvas"
            data-testid="mapping-canvas"
            onDropPayload={(payload) => {
              if (payload.origin === "palette") {
                pick(payload.key);
              }
            }}
          >
            {draft.fields.length === 0 ? <p className="hint">Add a field from the list, by dragging it here or pressing Enter on it.</p> : null}
            {draft.fields.map((field, index) => (
              <DropZone
                key={field.platform}
                className="item-chip"
                data-testid="mapping-field"
                onDropPayload={(payload) => {
                  if (payload.origin === "palette") {
                    pick(payload.key);
                  }
                }}
              >
                <strong>{fields.find((one) => one.apiName === field.platform)?.label ?? field.platform}</strong>
                <label className="field">
                  <span>External name</span>
                  <input value={field.external} onChange={(event) => update(changeField(draft, index, { external: event.target.value }))} aria-label={`External name of ${field.platform}`} />
                </label>
                <label className="field">
                  <span>Direction</span>
                  <select value={field.direction} onChange={(event) => update(changeField(draft, index, { direction: event.target.value }))} aria-label={`Direction of ${field.platform}`}>
                    {catalogue.directions.map((one) => (
                      <option key={one.key} value={one.key}>
                        {one.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Carries</span>
                  <select value={field.externalType} onChange={(event) => update(changeField(draft, index, { externalType: event.target.value }))} aria-label={`Type of ${field.platform}`}>
                    {catalogue.externalTypes.map((one) => (
                      <option key={one.key} value={one.key}>
                        {one.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Conversion</span>
                  <select value={field.conversion} onChange={(event) => update(changeField(draft, index, { conversion: event.target.value }))} aria-label={`Conversion of ${field.platform}`}>
                    {catalogue.conversions.map((one) => (
                      <option key={one.key} value={one.key} title={one.hint}>
                        {one.label}
                      </option>
                    ))}
                  </select>
                </label>
                {inputOf(catalogue, field.conversion) === "factor" ? (
                  <label className="field">
                    <span>Factor</span>
                    <input value={field.scale ?? ""} onChange={(event) => update(changeField(draft, index, { scale: event.target.value }))} aria-label={`Factor of ${field.platform}`} />
                  </label>
                ) : null}
                {inputOf(catalogue, field.conversion) === "pairs" ? (
                  <ValuePairs
                    name={field.platform}
                    pairs={field.valueMap ?? []}
                    onChange={(pairs) => update(changeField(draft, index, { valueMap: pairs }))}
                  />
                ) : null}
                <label className="field">
                  <span>Expression out (optional)</span>
                  <input value={field.outExpression ?? ""} onChange={(event) => update(changeField(draft, index, { outExpression: event.target.value }))} aria-label={`Expression out of ${field.platform}`} />
                </label>
                <label className="field">
                  <span>Expression in (optional)</span>
                  <input value={field.inExpression ?? ""} onChange={(event) => update(changeField(draft, index, { inExpression: event.target.value }))} aria-label={`Expression in of ${field.platform}`} />
                </label>
                <label className="field">
                  <span>Default</span>
                  <input value={field.defaultValue ?? ""} onChange={(event) => update(changeField(draft, index, { defaultValue: event.target.value }))} aria-label={`Default of ${field.platform}`} />
                </label>
                <label>
                  <input type="checkbox" checked={field.required} onChange={(event) => update(changeField(draft, index, { required: event.target.checked }))} /> Needs a value
                </label>
                <MoveButtons
                  name={field.platform}
                  onUp={index > 0 ? () => update(moveField(draft, index, -1)) : undefined}
                  onDown={index < draft.fields.length - 1 ? () => update(moveField(draft, index, 1)) : undefined}
                  onRemove={() => update(removeField(draft, index))}
                />
              </DropZone>
            ))}
          </DropZone>
        </div>

        <div className="stack">
          <h3>Try it</h3>
          <label className="field">
            <span>Direction to try</span>
            <select value={direction} onChange={(event) => setDirection(event.target.value as "OUTBOUND" | "INBOUND")}>
              {catalogue.directions
                .filter((one) => one.key !== "BOTH")
                .map((one) => (
                  <option key={one.key} value={one.key}>
                    {one.label}
                  </option>
                ))}
            </select>
          </label>
          {sampleNames.map((name) => (
            <label key={name} className="field">
              <span>{name}</span>
              <input value={sample[name] ?? ""} onChange={(event) => setSample({ ...sample, [name]: event.target.value })} aria-label={`Sample ${name}`} />
            </label>
          ))}
          <button type="button" className="button button-secondary" disabled={busy || draft.fields.length === 0} onClick={() => void tryIt()}>
            Try it
          </button>
          {dry ? (
            <div data-testid="mapping-dry-run">
              {Object.keys(dry.problems).length > 0 ? (
                <ul role="alert">
                  {Object.entries(dry.problems).flatMap(([place, lines]) =>
                    lines.map((line) => <li key={`${place}-${line}`}>{`${mappingPlaceWords(place)}: ${line}`}</li>),
                  )}
                </ul>
              ) : (
                <dl>
                  {Object.entries(dry.result).map(([name, value]) => (
                    <div key={name}>
                      <dt>{name}</dt>
                      <dd>{value}</dd>
                    </div>
                  ))}
                  {dry.skipped.map((one) => (
                    <div key={one.field}>
                      <dt>{one.field}</dt>
                      <dd>{one.words}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          ) : null}
        </div>

        {offersManage ? (
          <div className="actions">
            <button type="button" className="button" disabled={busy || draft.entity.trim() === "" || (!dirty && Boolean(view?.draft))} onClick={() => void save()}>
              Save draft
            </button>
            <button type="button" className="button" disabled={busy || !view?.canPublish || dirty} title={dirty ? "Save the draft first." : undefined} onClick={() => void publish()}>
              Publish
            </button>
            <button type="button" className="button button-secondary" disabled={busy || !view?.canPublish} onClick={() => void discard()}>
              Discard draft
            </button>
            <button type="button" className="button button-danger" disabled={busy || !view?.live} onClick={() => void stop()}>
              Stop
            </button>
            {dirty ? <Tag tone="quiet">Unsaved changes</Tag> : null}
          </div>
        ) : null}

        <h3>Versions</h3>
        <ul data-testid="mapping-versions">
          {[view?.live, view?.draft, ...(view?.history ?? [])]
            .filter((one) => one !== undefined && one !== null)
            .map((one) => (
              <li key={one.id}>
                Version {one.number}: {one.status === "LIVE" ? "live" : one.status === "DRAFT" ? "draft" : "earlier"}
                {one.publishedAt ? ` (${new Date(one.publishedAt).toLocaleString()})` : ""}
              </li>
            ))}
        </ul>
        {view?.live ? <p className="hint">Rule: {labelOf(catalogue.conflicts, view.live.definition.conflict)}.</p> : null}

        <h3>Sent and received</h3>
        {deliveries.length === 0 ? <p className="dl-value muted">Nothing yet.</p> : null}
        <ul data-testid="mapping-deliveries">
          {deliveries.map((one) => (
            <li key={one.id}>
              {one.direction === "OUT" ? "Sent" : "Received"} {one.kind.toLowerCase()} ({one.object}): {one.state.toLowerCase()}
              {one.words ? `, ${one.words}` : ""}
              {one.stale ? " (interrupted)" : ""} {new Date(one.createdAt).toLocaleString()}
            </li>
          ))}
        </ul>
      </div>
    </DetailSection>
  );
}

function ValuePairs({
  name,
  pairs,
  onChange,
}: {
  name: string;
  pairs: { platform: string; external: string }[];
  onChange: (pairs: { platform: string; external: string }[]) => void;
}) {
  return (
    <div className="stack">
      {pairs.map((pair, at) => (
        <div className="split" key={at}>
          <input
            value={pair.platform}
            aria-label={`Platform value ${at + 1} of ${name}`}
            onChange={(event) => onChange(pairs.map((one, i) => (i === at ? { ...one, platform: event.target.value } : one)))}
          />
          <input
            value={pair.external}
            aria-label={`External value ${at + 1} of ${name}`}
            onChange={(event) => onChange(pairs.map((one, i) => (i === at ? { ...one, external: event.target.value } : one)))}
          />
          <button type="button" aria-label={`Take out pair ${at + 1} of ${name}`} onClick={() => onChange(pairs.filter((_, i) => i !== at))}>
            ✕
          </button>
        </div>
      ))}
      <button type="button" className="link-button" onClick={() => onChange([...pairs, { platform: "", external: "" }])}>
        Add a pair
      </button>
    </div>
  );
}
