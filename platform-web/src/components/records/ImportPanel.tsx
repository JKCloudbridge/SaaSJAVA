"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import {
  DELIMITER_CHARACTERS,
  guessDelimiter,
  importableFields,
  parseHeader,
  readText,
  suggestMapping,
  type Delimiter,
} from "@/lib/records/csv";
import type { ImportJob, ObjectForm } from "@/lib/records/types";

const JSON_HEADERS = { "Content-Type": "application/json" };
const WORKING = new Set(["AWAITING_FILE", "QUEUED", "RUNNING"]);
const HEADER_BYTES = 64 * 1024;

/**
 * Imports a CSV file into an object (Sprint 19, ADR-0129; the job API is Sprint 17, ADR-0123): choose the file, say which
 * column fills which field, start, watch it, and take the report of the rows that failed. The page reads only the first
 * line of the file, to offer the columns; the server reads the file for real, writes every row through the same steps as
 * a single save with this member's permissions, and says how it went. An import cannot be undone, so "check only" (a dry
 * run: every row judged by the real write, nothing saved) is offered first.
 *
 * <p>Not offered here: mapping a link by the other record's external identifier (the API supports it; the plain way,
 * a column of identifiers, is what this screen writes).
 */
export function ImportPanel({
  objectApiName,
  form,
  onFinished,
  onClose,
  pollMs = 1500,
}: {
  objectApiName: string;
  form: ObjectForm;
  /** Called when an import that made or changed records has ended, so the list can be read again. */
  onFinished: () => void;
  onClose: () => void;
  /** How often a running import is asked how far it has got; tests make it short. */
  pollMs?: number;
}) {
  const fields = importableFields(form.fields);
  const externalIdFields = fields.filter((field) => field.externalId);
  const [file, setFile] = useState<File | null>(null);
  const [columns, setColumns] = useState<string[]>([]);
  const [delimiter, setDelimiter] = useState<Delimiter>("COMMA");
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [operation, setOperation] = useState<"CREATE" | "UPSERT">("CREATE");
  const [externalId, setExternalId] = useState(externalIdFields[0]?.apiName ?? "");
  const [dryRun, setDryRun] = useState(true);
  const [job, setJob] = useState<ImportJob | undefined>();
  const [problem, setProblem] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  // Counts the answers of the watch, so that every answer, changed or not, schedules the next question.
  const [beat, setBeat] = useState(0);
  const mounted = useRef(true);
  const reported = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Watch a running import until it ends.
  const watching = job && WORKING.has(job.state) ? job.id : undefined;
  useEffect(() => {
    if (!watching) {
      return;
    }
    const timer = setTimeout(() => {
      void call(() =>
        api.GET("/api/v1/objects/{objectApiName}/imports/{importId}", {
          params: { path: { objectApiName, importId: watching } },
        }),
      ).then((outcome) => {
        if (!mounted.current) {
          return;
        }
        if (outcome.ok) {
          setJob(outcome.data.data);
          setBeat((count) => count + 1);
        } else {
          setProblem(outcome.failure.message);
        }
      });
    }, pollMs);
    return () => clearTimeout(timer);
  }, [watching, beat, objectApiName, pollMs]);

  useEffect(() => {
    if (job && !WORKING.has(job.state) && !job.dryRun && ((job.createdRows ?? 0) > 0 || (job.updatedRows ?? 0) > 0) && !reported.current) {
      reported.current = true;
      onFinished();
    }
  }, [job, onFinished]);

  async function choose(chosen: File | null) {
    setFile(chosen);
    setProblem(undefined);
    if (!chosen) {
      setColumns([]);
      setMapping({});
      return;
    }
    const head = await readText(chosen.slice(0, HEADER_BYTES));
    const first = head.split(/\r?\n/, 1)[0] ?? "";
    const guessed = guessDelimiter(first);
    const names = parseHeader(first, DELIMITER_CHARACTERS[guessed]);
    setDelimiter(guessed);
    setColumns(names);
    setMapping(suggestMapping(names, fields));
  }

  async function reread(next: Delimiter) {
    setDelimiter(next);
    if (!file) {
      return;
    }
    const head = await readText(file.slice(0, HEADER_BYTES));
    const names = parseHeader(head.split(/\r?\n/, 1)[0] ?? "", DELIMITER_CHARACTERS[next]);
    setColumns(names);
    setMapping(suggestMapping(names, fields));
  }

  async function start() {
    if (!file) {
      setProblem("Choose a file first.");
      return;
    }
    const mapped = Object.fromEntries(Object.entries(mapping).filter(([, field]) => field !== ""));
    if (Object.keys(mapped).length === 0) {
      setProblem("Say which column fills which field.");
      return;
    }
    if (operation === "UPSERT" && !Object.values(mapped).includes(externalId)) {
      setProblem("An update-or-create import needs the column of the external identifier to be mapped.");
      return;
    }
    setBusy(true);
    setProblem(undefined);
    reported.current = false;
    const started = await call(
      () =>
        api.POST("/api/v1/objects/{objectApiName}/imports", {
          params: { path: { objectApiName } },
          body: {
            operation,
            ...(operation === "UPSERT" ? { externalIdField: externalId } : {}),
            dryRun,
            delimiter,
            mapping: mapped,
          },
          headers: JSON_HEADERS,
        }),
    );
    if (!mounted.current) {
      return;
    }
    if (!started.ok) {
      setBusy(false);
      setProblem(started.failure.message);
      return;
    }
    const created = started.data.data;
    setJob(created);
    const sent = await call(
      () =>
        api.PUT("/api/v1/objects/{objectApiName}/imports/{importId}/file", {
          params: { path: { objectApiName, importId: created.id } },
          // The file as it is: a body of bytes whose length is announced, which the server requires.
          body: file as unknown as string,
          bodySerializer: (body: unknown) => body as BodyInit,
          headers: { "Content-Type": "text/csv" },
        }),
    );
    if (!mounted.current) {
      return;
    }
    setBusy(false);
    if (sent.ok) {
      setJob(sent.data.data);
    } else {
      setProblem(sent.failure.message);
    }
  }

  async function cancel() {
    if (!job) {
      return;
    }
    const outcome = await call(
      () =>
        api.POST("/api/v1/objects/{objectApiName}/imports/{importId}/cancel", {
          params: { path: { objectApiName, importId: job.id } },
        }),
    );
    if (mounted.current) {
      if (outcome.ok) {
        setJob(outcome.data.data);
      } else {
        setProblem(outcome.failure.message);
      }
    }
  }

  async function downloadReport() {
    if (!job) {
      return;
    }
    const outcome = await call(() =>
      api.GET("/api/v1/objects/{objectApiName}/imports/{importId}/errors", {
        params: { path: { objectApiName, importId: job.id } },
        parseAs: "blob",
      }),
    );
    if (!outcome.ok) {
      setProblem(outcome.failure.message);
      return;
    }
    const url = URL.createObjectURL(outcome.data as unknown as Blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "import-errors.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  const working = job !== undefined && WORKING.has(job.state);
  const ended = job !== undefined && !working;

  return (
    <section aria-labelledby="import-heading" data-testid="import-panel">
      <h2 id="import-heading">Import records from a CSV file</h2>
      {job === undefined ? (
        <div className="stack">
          <div className="field">
            <label htmlFor="import-file">File</label>
            <input
              id="import-file"
              type="file"
              accept=".csv,.txt,text/csv,text/plain"
              onChange={(event) => void choose(event.target.files?.[0] ?? null)}
            />
            <p className="hint">UTF-8 text with a header row, up to 20 MiB and 100,000 rows.</p>
          </div>
          <div className="field">
            <label htmlFor="import-delimiter">Columns are separated by</label>
            <select id="import-delimiter" value={delimiter} onChange={(event) => void reread(event.target.value as Delimiter)}>
              <option value="COMMA">a comma</option>
              <option value="SEMICOLON">a semicolon</option>
              <option value="TAB">a tab</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="import-operation">What to do with each row</label>
            <select id="import-operation" value={operation} onChange={(event) => setOperation(event.target.value as "CREATE" | "UPSERT")}>
              <option value="CREATE">Create a record</option>
              <option value="UPSERT" disabled={externalIdFields.length === 0}>
                Update the record with the same external identifier, or create it
              </option>
            </select>
          </div>
          {operation === "UPSERT" ? (
            <div className="field">
              <label htmlFor="import-external">Field that finds the record</label>
              <select id="import-external" value={externalId} onChange={(event) => setExternalId(event.target.value)}>
                {externalIdFields.map((field) => (
                  <option key={field.apiName} value={field.apiName}>
                    {field.label}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
          {columns.length > 0 ? (
            <table className="table" data-testid="import-mapping">
              <caption className="visually-hidden">Which column fills which field</caption>
              <thead>
                <tr>
                  <th scope="col">Column of the file</th>
                  <th scope="col">Fills the field</th>
                </tr>
              </thead>
              <tbody>
                {columns.map((column, index) => (
                  <tr key={`${column}-${index}`}>
                    <th scope="row">{column}</th>
                    <td>
                      <select
                        aria-label={`Field for the column ${column}`}
                        value={mapping[column] ?? ""}
                        onChange={(event) => setMapping((current) => ({ ...current, [column]: event.target.value }))}
                      >
                        <option value="">Do not import</option>
                        {fields.map((field) => (
                          <option key={field.apiName} value={field.apiName}>
                            {field.label}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <label>
            <input type="checkbox" checked={dryRun} onChange={(event) => setDryRun(event.target.checked)} />
            Check only: judge every row as a real save would, and save nothing
          </label>
        </div>
      ) : (
        <div data-testid="import-progress">
          <p role="status">
            {working
              ? job.state === "AWAITING_FILE"
                ? "Sending the file…"
                : `${job.processedRows} of ${job.totalRows || "…"} rows handled…`
              : job.state === "COMPLETED"
                ? job.dryRun
                  ? "The check finished. Nothing was saved."
                  : "The import finished."
                : job.state === "CANCELLED"
                  ? "The import was stopped. What it already wrote stays."
                  : "The import could not go on."}
          </p>
          {ended || working ? (
            <p>
              {job.dryRun ? "Would create" : "Created"} {job.createdRows}, {job.dryRun ? "would update" : "updated"}{" "}
              {job.updatedRows}, refused {job.failedRows}.
            </p>
          ) : null}
          {job.failure ? (
            <p className="form-message" role="alert">
              {job.failure}
            </p>
          ) : null}
        </div>
      )}
      {problem ? (
        <p className="form-message" role="alert" data-testid="import-problem">
          {problem}
        </p>
      ) : null}
      <div className="filters-actions">
        {job === undefined ? (
          <button type="button" className="button" disabled={busy || !file} onClick={() => void start()}>
            {busy ? "Starting…" : dryRun ? "Check the file" : "Start the import"}
          </button>
        ) : null}
        {working ? (
          <button type="button" className="button button-secondary" onClick={() => void cancel()}>
            Stop the import
          </button>
        ) : null}
        {ended && job.errorsAvailable ? (
          <button type="button" className="button button-secondary" onClick={() => void downloadReport()}>
            Download the report of refused rows
          </button>
        ) : null}
        {ended && job.dryRun && job.state === "COMPLETED" ? (
          <button
            type="button"
            className="button"
            onClick={() => {
              setJob(undefined);
              setDryRun(false);
            }}
          >
            Now import for real
          </button>
        ) : null}
        <button type="button" className="button button-secondary" onClick={onClose}>
          {ended ? "Close" : "Cancel"}
        </button>
      </div>
    </section>
  );
}
