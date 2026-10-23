"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { fillStatusTag } from "@/lib/sheets/fillTags";
import { listTemplates, NO_FILTERS, type TemplateRow } from "./api";
import { fillInsHref, getReportColumns, runReport, type FillInRow, type ReportColumn } from "./fillIns";
import { OPERATOR_WORDS, operatorsFor, toRequest, type ColumnType, type Operator } from "./report";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

const STATUSES: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "SUBMITTED", label: "Sent for review" },
  { value: "RETURNED", label: "Returned" },
  { value: "APPROVED", label: "Approved" },
];

type Found =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; rows: FillInRow[]; next?: string }
  | { status: "failed"; message: string };

function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "";
}

/**
 * The page of the "Report" entry (Functional track FF2, ADR-0193): choose a template, one of the columns its author flagged
 * reportable, a comparison and a value, and list the fill-ins that hold it. The fill-ins are the ones the member may see
 * (the same as the list: the server decides, by the same code) and the list never carries the cells.
 *
 * <p>A page can be short and still have "Show more": the search looks through more of the matches for a member who sees only
 * some of them, and only the end of the matches takes the button away. It draws what the API answers and decides nothing;
 * the value typed goes to the server in a body, never into the address.
 */
export function FillInReportPage() {
  const router = useRouter();
  const [templates, setTemplates] = useState<TemplateRow[] | undefined>();
  const [problem, setProblem] = useState<string | undefined>();
  const [templateId, setTemplateId] = useState("");
  const [columns, setColumns] = useState<{ templateId: string; versionNo: number; list: ReportColumn[] } | undefined>();
  const [columnId, setColumnId] = useState("");
  const [operator, setOperator] = useState<Operator>("EQUALS");
  const [value, setValue] = useState("");
  const [many, setMany] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [status, setStatus] = useState("");
  const [found, setFound] = useState<Found>({ status: "idle" });
  const [asked, setAsked] = useState<ReturnType<typeof toRequest> | undefined>();

  useEffect(() => {
    let live = true;
    void listTemplates(NO_FILTERS).then((outcome) => {
      if (!live) {
        return;
      }
      if (outcome.ok) {
        setTemplates(outcome.data.rows);
      } else {
        setProblem(outcome.failure.message);
      }
    });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (templateId === "") {
      return;
    }
    let live = true;
    void getReportColumns(templateId).then((outcome) => {
      if (!live) {
        return;
      }
      if (outcome.ok) {
        setColumns({ templateId, versionNo: outcome.data.versionNo, list: outcome.data.columns });
        setProblem(undefined);
      } else {
        setColumns(undefined);
        setProblem(outcome.failure.message);
      }
    });
    return () => {
      live = false;
    };
  }, [templateId]);

  const choices = columns && columns.templateId === templateId ? columns : undefined;
  const column = choices?.list.find((one) => one.id === columnId);
  const type = (column?.type ?? "text") as ColumnType;
  const operators = operatorsFor(type);

  const ask = async (cursor?: string) => {
    if (!choices || !column) {
      return;
    }
    // "Show more" continues the question that was asked, not whatever the form says by now.
    const built =
      cursor && asked && asked.ok
        ? asked
        : toRequest({
            templateId,
            versionNo: choices.versionNo,
            columnId,
            type,
            operator: operators.includes(operator) ? operator : operators[0],
            value,
            many,
            from,
            to,
            status,
          });
    setAsked(built);
    if (!built.ok) {
      setFound({ status: "idle" });
      return;
    }
    const previous = found.status === "ready" ? found : undefined;
    if (!cursor) {
      setFound({ status: "loading" });
    }
    const outcome = await runReport({ ...built.request, cursor });
    if (!outcome.ok) {
      setFound({ status: "failed", message: outcome.failure.message });
      return;
    }
    setFound({
      status: "ready",
      rows: cursor && previous ? [...previous.rows, ...outcome.data.rows] : outcome.data.rows,
      next: outcome.data.nextCursor ?? undefined,
    });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void ask();
  };

  const valueInput = () => {
    const effective = operators.includes(operator) ? operator : operators[0];
    if (effective === "ONE_OF") {
      return (
        <label>
          Values, one on each line
          <textarea value={many} rows={4} onChange={(event) => setMany(event.target.value)} />
        </label>
      );
    }
    if (effective === "BETWEEN") {
      const kind = type === "date" ? "date" : "text";
      return (
        <>
          <label>
            From
            <input type={kind} inputMode={type === "number" ? "decimal" : undefined} value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <label>
            To
            <input type={kind} inputMode={type === "number" ? "decimal" : undefined} value={to} onChange={(event) => setTo(event.target.value)} />
          </label>
        </>
      );
    }
    if (type === "boolean") {
      return (
        <label>
          Value
          <select value={value} onChange={(event) => setValue(event.target.value)}>
            <option value="">Choose</option>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </label>
      );
    }
    if (type === "picklist" && column) {
      return (
        <label>
          Value
          <select value={value} onChange={(event) => setValue(event.target.value)}>
            <option value="">Choose</option>
            {column.values.map((one) => (
              <option key={one} value={one}>
                {one}
              </option>
            ))}
          </select>
        </label>
      );
    }
    return (
      <label>
        Value
        <input
          type={type === "date" ? "date" : "text"}
          inputMode={type === "number" ? "decimal" : undefined}
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
      </label>
    );
  };

  const form = (
    <form onSubmit={submit} aria-label="Report on fill-ins" className="filters" data-testid="fill-in-report-form">
      <label>
        Template
        <select
          value={templateId}
          onChange={(event) => {
            setTemplateId(event.target.value);
            setColumnId("");
            setFound({ status: "idle" });
          }}
        >
          <option value="">Choose a template</option>
          {(templates ?? []).map((one) => (
            <option key={one.id} value={one.id}>
              {one.name || "Template"}
            </option>
          ))}
        </select>
      </label>
      {choices && choices.list.length === 0 ? (
        <p className="hint" role="note">
          No column of this template can be used in a report yet. An author marks a column “Can be used in a report” in a new
          version.
        </p>
      ) : null}
      {choices && choices.list.length > 0 ? (
        <>
          <label>
            Column
            <select
              value={columnId}
              onChange={(event) => {
                setColumnId(event.target.value);
                setValue("");
                setMany("");
                setFrom("");
                setTo("");
                setFound({ status: "idle" });
              }}
            >
              <option value="">Choose a column</option>
              {choices.list.map((one) => (
                <option key={one.id} value={one.id}>
                  {one.label || "Column"}
                </option>
              ))}
            </select>
          </label>
          {column ? (
            <>
              <label>
                Comparison
                <select value={operators.includes(operator) ? operator : operators[0]} onChange={(event) => setOperator(event.target.value as Operator)}>
                  {operators.map((one) => (
                    <option key={one} value={one}>
                      {OPERATOR_WORDS[one]}
                    </option>
                  ))}
                </select>
              </label>
              {valueInput()}
              <label>
                Status
                <select value={status} onChange={(event) => setStatus(event.target.value)}>
                  {STATUSES.map((choice) => (
                    <option key={choice.value} value={choice.value}>
                      {choice.label}
                    </option>
                  ))}
                </select>
              </label>
              <div className="filters-actions">
                <button type="submit" className="button">
                  Show fill-ins
                </button>
              </div>
            </>
          ) : null}
        </>
      ) : null}
      {asked && !asked.ok ? (
        <p className="form-message" role="alert">
          {asked.error}
        </p>
      ) : null}
      {problem ? (
        <p className="form-message" role="alert">
          {problem}
        </p>
      ) : null}
    </form>
  );

  const ready = found.status === "ready" ? found : undefined;
  return (
    <ListPage<FillInRow>
      title="Report"
      intro="Find the fill-ins of a template where one of its columns holds a value. You see the fill-ins you are allowed to see."
      items={found.status === "idle" ? [] : ready?.rows}
      failure={found.status === "failed" ? found.message : undefined}
      rowKey={(row) => row.fillId}
      primaryHeader="Name"
      primary={(row) => row.name || "Fill-in"}
      primaryExtra={(row) => (row.filledBy ? <span className="hint">{row.filledBy}</span> : null)}
      columns={[
        { key: "version", header: "Version", cell: (row) => `v${row.versionNo}`, kind: "muted" },
        { key: "started", header: "Started", cell: (row) => when(row.createdAt), kind: "muted" },
        {
          key: "status",
          header: "Status",
          cell: (row) => {
            const tag = fillStatusTag(row.status);
            return (
              <>
                <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>
                {row.archivedAt ? <Tag tone="quiet">Archived</Tag> : null}
              </>
            );
          },
          kind: "tag",
        },
      ]}
      onOpen={(row) => router.push(fillInsHref(row.fillId))}
      above={form}
      below={
        ready?.next ? (
          <div>
            <button type="button" className="button button-secondary" onClick={() => void ask(ready.next)}>
              Show more
            </button>
          </div>
        ) : undefined
      }
      emptyText={
        found.status === "idle"
          ? "Choose a template, a column and a value, then show the fill-ins."
          : found.status === "loading"
            ? "Looking…"
            : "No fill-in you can see holds this value."
      }
      testId="fill-in-report"
    />
  );
}
