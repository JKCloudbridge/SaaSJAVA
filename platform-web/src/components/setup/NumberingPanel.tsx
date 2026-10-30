"use client";

import { useCallback, useEffect, useState } from "react";
import { DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";

type Series = components["schemas"]["NumberingSeriesView"];
type Setting = components["schemas"]["NumberingSettingView"];
type Preview = components["schemas"]["NumberingPreviewView"];
type Counters = components["schemas"]["NumberingCountersView"];
type Row = { series: Series; setting: Setting };
const keyOf = ({ series, setting }: Row) => `${series.key}:${setting.kind}:${setting.selectorValue}`;
const sourceWords: Record<string, string> = { SHIPPED: "Comes with the platform", ORGANIZATION: "Your format", DEFAULT: "Uses default" };

/** The server lists the categories, parts and formats. A preview never consumes a number. */
export function NumberingPanel() {
  const allowed = useOffers("numbering.manage");
  const [series, setSeries] = useState<Series[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [open, setOpen] = useState<string | null>(null);
  const load = useCallback(async () => {
    try {
      const result = await api.GET("/api/v1/numbering");
      if (result.data) {
        setSeries(result.data.data.series);
        setFailure(undefined);
      } else {
        setFailure(problemText(result.error, result.response));
      }
    } catch {
      setFailure("The page could not reach the platform. Try again.");
    }
  }, []);
  useEffect(() => {
    if (allowed) void Promise.resolve().then(load);
  }, [allowed, load]);

  if (!allowed) return <p role="status">You do not have access to numbering settings.</p>;
  const rows: Row[] | undefined = series?.flatMap((item) => item.settings.map((setting) => ({ series: item, setting })));
  const selected = rows?.find((row) => keyOf(row) === open);
  if (selected) {
    return <NumberingEditor key={open} row={selected} onBack={() => setOpen(null)} onSaved={load} />;
  }
  return (
    <>
    <ListPage<Row>
      title="Numbering" items={rows} failure={failure} rowKey={keyOf} testId="numbering-list"
      primaryHeader="Category or default" primary={({ setting }) => setting.label}
      primaryExtra={({ setting }) => !setting.active ? <span className="hint">Switched off</span> : null}
      columns={[
        { key: "object", header: "Object", cell: ({ series }) => series.label },
        { key: "format", header: "Format", cell: ({ setting }) => setting.format },
        { key: "source", header: "Source", cell: ({ setting }) => sourceWords[setting.source] ?? setting.source },
        { key: "width", header: "Count digits", cell: ({ setting }) => setting.width || "No count" },
      ]}
      onOpen={(row) => setOpen(keyOf(row))}
      searchText={({ series, setting }) => `${series.label} ${setting.label} ${setting.selectorValue}`}
      emptyText="No numbering series is available in this organization."
    />
    {series?.map((item) => <NumberingCounters key={item.key} series={item} />)}
    </>
  );
}

/**
 * The combinations of one series that already have numbers, read only (FA11B, ADR-0206): what was typed, the count the next
 * number gets and the count last given. Counters only; the page decides nothing and a failure is shown in the server's words.
 */
function NumberingCounters({ series }: { series: Series }) {
  const [rows, setRows] = useState<Counters["counters"] | undefined>();
  const [cursor, setCursor] = useState<string | null | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const load = useCallback(async (from?: string) => {
    setBusy(true);
    try {
      const result = await api.GET("/api/v1/numbering/{seriesKey}/counters", {
        params: { path: { seriesKey: series.key }, query: { cursor: from } },
      });
      if (result.data) {
        const page = result.data.data;
        setRows((old) => (from ? [...(old ?? []), ...page.counters] : page.counters));
        setCursor(page.nextCursor ?? null);
        setFailure(undefined);
      } else {
        setFailure(problemText(result.error, result.response));
      }
    } catch {
      setFailure("The page could not reach the platform. Try again.");
    } finally {
      setBusy(false);
    }
  }, [series.key]);
  useEffect(() => {
    void Promise.resolve().then(() => load());
  }, [load]);

  return (
    <section aria-label={`Combinations with numbers: ${series.label}`} className="stack" data-testid="numbering-counters">
      <h2>Combinations that already have numbers: {series.label}</h2>
      {failure ? <p role="alert" className="form-message">{failure}</p> : null}
      {rows && rows.length === 0 ? <p className="hint">No combination has a number yet.</p> : null}
      {rows && rows.length > 0 ? (
        <table className="table">
          <thead>
            <tr><th scope="col">Combination</th><th scope="col">Next count</th><th scope="col">Last count given</th></tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`${row.combination}-${index}`}>
                <td>{row.combination}</td><td>{row.nextValue}</td><td>{row.lastIssued}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {cursor ? (
        <button type="button" className="button button-secondary" disabled={busy} onClick={() => void load(cursor)}>Show more</button>
      ) : null}
    </section>
  );
}

function NumberingEditor({ row, onBack, onSaved }: { row: Row; onBack: () => void; onSaved: () => Promise<void> }) {
  const { series, setting } = row;
  const [format, setFormat] = useState(setting.format);
  const [values, setValues] = useState<Record<string, string>>(setting.kind === "CATEGORY" && series.selector
    ? { [series.selector]: setting.selectorValue } : {});
  const [preview, setPreview] = useState<Preview | undefined>();
  const [parts, setParts] = useState<string[]>([]);
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();

  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    try { await work(); } catch { setProblem("The page could not reach the platform. Try again."); }
    finally { setBusy(false); }
  };
  const changeValue = (name: string, value: string) => {
    setValues((old) => ({ ...old, [name]: value }));
    setPreview(undefined);
    setNext("");
  };
  const check = () => run(async () => {
    const result = await api.POST("/api/v1/numbering/{seriesKey}/preview", {
      params: { path: { seriesKey: series.key } },
      body: { kind: setting.kind, selectorValue: setting.selectorValue, format, values },
    });
    if (!result.data) { setPreview(undefined); setProblem(problemText(result.error, result.response)); return; }
    setPreview(result.data.data);
    setParts(result.data.data.fields);
    setNext(result.data.data.nextValue === null || result.data.data.nextValue === undefined ? "" : String(result.data.data.nextValue));
  });
  const save = () => run(async () => {
    const result = await api.PUT("/api/v1/numbering/{seriesKey}/formats", {
      params: { path: { seriesKey: series.key } },
      body: { kind: setting.kind, selectorValue: setting.selectorValue, format, version: setting.version },
    });
    if (!result.data) { setProblem(problemText(result.error, result.response)); return; }
    await onSaved();
    setPreview(undefined);
    setNotice("The format was saved. Numbers already given have not changed.");
  });
  const reset = () => run(async () => {
    const result = await api.POST("/api/v1/numbering/{seriesKey}/formats/reset", {
      params: { path: { seriesKey: series.key } },
      body: { kind: setting.kind, selectorValue: setting.selectorValue, version: setting.version },
    });
    if (!result.data) { setProblem(problemText(result.error, result.response)); return; }
    await onSaved();
    onBack();
  });
  const advance = () => run(async () => {
    const result = await api.POST("/api/v1/numbering/{seriesKey}/counter", {
      params: { path: { seriesKey: series.key } }, body: { values, nextValue: Number(next) },
    });
    if (!result.data) { setProblem(problemText(result.error, result.response)); return; }
    setPreview(result.data.data);
    setNotice("The next count was updated. Numbers already given have not changed.");
  });

  const fields = [...new Set([
    ...(setting.kind !== "CATEGORY" && series.selector ? [series.selector] : []),
    ...(setting.source === "DEFAULT" && series.optionalField ? [series.optionalField] : []), ...parts,
  ])].filter((name) => !(setting.kind === "CATEGORY" && name === series.selector));
  const messages = <>
    {problem ? <p role="alert" className="form-message">{problem}</p> : null}
    {notice ? <p role="status" className="hint">{notice}</p> : null}
  </>;
  return (
    <DetailPage title={setting.label} listLabel="Numbering" onBack={onBack} notices={messages}
      actions={<>
        {setting.source === "ORGANIZATION" ? (
          <button type="button" className="button button-secondary" disabled={busy} onClick={() => void reset()}>Use the platform&apos;s format</button>
        ) : null}
        <button type="button" className="button" disabled={busy || !preview?.example} onClick={() => void save()}>Save format</button>
      </>}>
      <DetailSection title="Format" stack>
        <p>Numbers already given are never changed. A format change affects only numbers made afterwards.</p>
        <p>Using the same fields continues the count, even if their order or digit width changes. Different fields use their own counter; returning to an earlier set of fields resumes its counter.</p>
        {!setting.active ? <p className="hint">This category is switched off. Its setting and earlier numbers are kept.</p> : null}
        <label className="field">Number format
          <input value={format} maxLength={100} onChange={(event) => {
            setFormat(event.target.value); setPreview(undefined); setParts([]); setNext("");
            const categoryValue = values[series.selector];
            setValues(series.selector && categoryValue ? { [series.selector]: categoryValue } : {});
          }} />
        </label>
        <p className="hint">Parts: letters, digits, / - _, {"{field:category}"} and a count {"{seq:1}"}. {setting.width ? `The saved count has at least ${setting.width} digit${setting.width === 1 ? "" : "s"}; a growing count is never cut.` : "The saved format has no count."}</p>
        <button type="button" className="button button-secondary" disabled={busy} onClick={() => void check()}>Preview</button>
      </DetailSection>
      <DetailSection title="Example" stack>
        {fields.map((name) => {
          const field = series.fields.find((item) => item.apiName === name);
          return <label className="field" key={name}>{field?.label ?? name}
            {field?.values.length ? (
              <select value={values[name] ?? ""} onChange={(event) => changeValue(name, event.target.value)}>
                <option value="">Choose</option>
                {field.values.map((option) => <option key={option.value} value={option.value} disabled={!option.active}>{option.label}{option.active ? "" : " (switched off)"}</option>)}
              </select>
            ) : <input value={values[name] ?? ""} maxLength={10} onChange={(event) => changeValue(name, event.target.value)} />}
          </label>;
        })}
        {preview ? <p role="status">{preview.example ? <strong>{preview.example}</strong> : null} {preview.message}</p> : null}
      </DetailSection>
      {setting.kind === "CATEGORY" && format === setting.format && preview?.nextValue !== null && preview?.nextValue !== undefined ? (
        <DetailSection title="Continue the count" stack>
          <p>Continue from an earlier system by raising this pair&apos;s next count. It can never be lowered. Save any format change and preview again first.</p>
          <label className="field">Next count<input type="number" min={1} max={999999999999} step={1} value={next} onChange={(event) => setNext(event.target.value)} /></label>
          <button type="button" className="button button-secondary" disabled={busy || !next} onClick={() => void advance()}>Raise next count</button>
        </DetailSection>
      ) : null}
    </DetailPage>
  );
}
