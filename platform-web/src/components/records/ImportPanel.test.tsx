import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call, type Handler } from "@/test/fakeApi";
import { DEAL_FORM, field } from "@/test/records";
import type { ImportJob, ObjectForm } from "@/lib/records/types";
import { ImportPanel } from "./ImportPanel";

const BASE = "/api/v1/objects/Deal__c/imports";
const START = `POST ${BASE}`;
const FILE = `PUT ${BASE}/j1/file`;
const JOB = `GET ${BASE}/j1`;
const ERRORS = `GET ${BASE}/j1/errors`;
const CANCEL = `POST ${BASE}/j1/cancel`;

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

function job(state: string, extra: Partial<ImportJob> = {}): { data: ImportJob } {
  return {
    data: {
      id: "j1",
      object: "Deal__c",
      operation: "CREATE",
      dryRun: false,
      state,
      totalRows: 2,
      processedRows: 0,
      createdRows: 0,
      updatedRows: 0,
      failedRows: 0,
      errorsAvailable: false,
      createdAt: "2030-01-01T00:00:00Z",
      ...extra,
    },
  };
}

function open(more: Record<string, Handler> = {}, form: ObjectForm = DEAL_FORM) {
  const calls = fakeApi({ [START]: () => json(job("AWAITING_FILE"), 201), [FILE]: () => json(job("QUEUED"), 202), ...more });
  const onFinished = vi.fn();
  const onClose = vi.fn();
  render(<ImportPanel objectApiName="Deal__c" form={form} onFinished={onFinished} onClose={onClose} pollMs={5} />);
  return { calls, onFinished, onClose };
}

async function choose(content: string) {
  const file = new File([content], "deals.csv", { type: "text/csv" });
  fireEvent.change(screen.getByLabelText("File"), { target: { files: [file] } });
  await screen.findByTestId("import-mapping");
  return file;
}

const lastBody = (calls: Call[], key: string) => JSON.parse(calls.filter((call) => call.key === key).at(-1)?.body ?? "null") as unknown;

describe("choosing the file", () => {
  it("reads the header row, guesses the delimiter and says which column fills which field", async () => {
    open();

    await choose("Name;Amount;Stage;Whatever\nAcme;5;OPEN;x\n");

    expect(screen.getByLabelText("Columns are separated by")).toHaveValue("SEMICOLON");
    expect(screen.getByLabelText("Field for the column Name")).toHaveValue("name__c");
    expect(screen.getByLabelText("Field for the column Amount")).toHaveValue("amount__c");
    expect(screen.getByLabelText("Field for the column Stage")).toHaveValue("stage__c");
    expect(screen.getByLabelText("Field for the column Whatever")).toHaveValue("");
  });

  it("offers only what a person can type into as a field to fill", async () => {
    open();
    await choose("Name\nAcme\n");

    const options = within(screen.getByLabelText("Field for the column Name")).getAllByRole("option").map((option) => option.textContent);
    expect(options).toContain("Amount");
    expect(options).not.toContain("Margin");
    expect(options).not.toContain("Owner");
  });

  it("reads the columns again when the delimiter is changed", async () => {
    open();
    await choose("Name,Amount\nAcme,5\n");

    fireEvent.change(screen.getByLabelText("Columns are separated by"), { target: { value: "SEMICOLON" } });

    await waitFor(() => expect(screen.getByLabelText("Field for the column Name,Amount")).toHaveValue(""));
  });

  it("starts nothing without a file", () => {
    open();

    expect(screen.getByRole("button", { name: "Check the file" })).toBeDisabled();
  });
});

describe("running an import", () => {
  it("starts with the mapping that was set, sends the file, watches it, and reports what it did", async () => {
    let polls = 0;
    const { calls, onFinished } = open({
      [JOB]: () => (++polls < 3 ? json(job("RUNNING", { processedRows: 1 })) : json(job("COMPLETED", { processedRows: 2, createdRows: 2 }))),
    });
    await choose("Name,Amount\nAcme,5\nGlobex,6\n");
    fireEvent.click(screen.getByLabelText(/Check only/));
    fireEvent.click(screen.getByRole("button", { name: "Start the import" }));

    expect(await screen.findByText("The import finished.")).toBeInTheDocument();
    expect(lastBody(calls, START)).toEqual({
      operation: "CREATE",
      dryRun: false,
      delimiter: "COMMA",
      mapping: { Name: "name__c", Amount: "amount__c" },
    });
    expect(screen.getByTestId("import-progress")).toHaveTextContent("Created 2, updated 0, refused 0.");
    expect(calls.filter((call) => call.key === FILE)).toHaveLength(1);
    await waitFor(() => expect(onFinished).toHaveBeenCalledTimes(1));
  });

  it("checks first by default: nothing is saved, and the list is not told to reload", async () => {
    const { calls, onFinished } = open({ [JOB]: () => json(job("COMPLETED", { processedRows: 2, createdRows: 2, dryRun: true })) });
    await choose("Name\nAcme\nGlobex\n");

    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    expect(await screen.findByText("The check finished. Nothing was saved.")).toBeInTheDocument();
    expect(lastBody(calls, START)).toMatchObject({ dryRun: true });
    expect(screen.getByTestId("import-progress")).toHaveTextContent("Would create 2, would update 0, refused 0.");
    expect(onFinished).not.toHaveBeenCalled();
  });

  it("offers to import for real after a check that passed", async () => {
    open({ [JOB]: () => json(job("COMPLETED", { createdRows: 2, dryRun: true })) });
    await choose("Name\nAcme\n");
    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    fireEvent.click(await screen.findByRole("button", { name: "Now import for real" }));

    expect(screen.getByRole("button", { name: "Start the import" })).toBeInTheDocument();
  });

  it("sends the file as it is, as text, with its length announced by the browser", async () => {
    const seen: string[] = [];
    open({
      [FILE]: (request) => {
        seen.push(request.headers.get("Content-Type") ?? "");
        return json(job("QUEUED", { dryRun: true }), 202);
      },
      [JOB]: () => json(job("COMPLETED", { dryRun: true })),
    });
    await choose("Name\nAcme\n");

    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));
    await screen.findByText("The check finished. Nothing was saved.");

    expect(seen).toEqual(["text/csv"]);
  });

  it("says what is missing before it asks the server anything", async () => {
    const { calls } = open();
    await choose("Unknown\nx\n");

    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    expect(await screen.findByTestId("import-problem")).toHaveTextContent("Say which column fills which field.");
    expect(calls.some((call) => call.key === START)).toBe(false);
  });

  it("shows the API's words when it refuses to start, and does not send the file", async () => {
    const { calls } = open({ [START]: () => refusal("CONFLICT", "Too many imports are running.", 409) });
    await choose("Name\nAcme\n");

    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    expect(await screen.findByTestId("import-problem")).toHaveTextContent("Too many imports are running.");
    expect(calls.some((call) => call.key === FILE)).toBe(false);
  });

  it("shows why an import could not go on", async () => {
    open({ [JOB]: () => json(job("FAILED", { failure: "The file is not text." })) });
    await choose("Name\nAcme\n");

    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    expect(await screen.findByText("The import could not go on.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("The file is not text.");
  });

  it("can be stopped while it runs", async () => {
    const { calls } = open({
      [JOB]: () => json(job("RUNNING", { processedRows: 1 })),
      [CANCEL]: () => json(job("CANCELLED", { processedRows: 1, createdRows: 1 })),
    });
    await choose("Name\nAcme\n");
    fireEvent.click(screen.getByLabelText(/Check only/));
    fireEvent.click(screen.getByRole("button", { name: "Start the import" }));

    fireEvent.click(await screen.findByRole("button", { name: "Stop the import" }));

    expect(await screen.findByText("The import was stopped. What it already wrote stays.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === CANCEL)).toBe(true);
  });
});

describe("the report of refused rows", () => {
  it("is offered when there is one, and downloaded through the API", async () => {
    const created = vi.fn(() => "blob:report");
    const revoked = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked }));
    const clicked: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this.download);
    });
    const { calls } = open({
      [JOB]: () => json(job("COMPLETED", { processedRows: 2, createdRows: 1, failedRows: 1, errorsAvailable: true, dryRun: false })),
      [ERRORS]: () => new Response("row,reason\n2,Nope\n", { status: 200, headers: { "Content-Type": "text/csv" } }),
    });
    await choose("Name\nAcme\n");
    fireEvent.click(screen.getByLabelText(/Check only/));
    fireEvent.click(screen.getByRole("button", { name: "Start the import" }));

    fireEvent.click(await screen.findByRole("button", { name: "Download the report of refused rows" }));

    await waitFor(() => expect(clicked).toEqual(["import-errors.csv"]));
    expect(calls.some((call) => call.key === ERRORS)).toBe(true);
    expect(created).toHaveBeenCalled();
    expect(revoked).toHaveBeenCalledWith("blob:report");
  });

  it("is not offered when there is none", async () => {
    open({ [JOB]: () => json(job("COMPLETED", { errorsAvailable: false, dryRun: true })) });
    await choose("Name\nAcme\n");
    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));
    await screen.findByText("The check finished. Nothing was saved.");

    expect(screen.queryByRole("button", { name: "Download the report of refused rows" })).toBeNull();
  });
});

describe("updating by an external identifier", () => {
  const withExternal: ObjectForm = {
    ...DEAL_FORM,
    fields: [...DEAL_FORM.fields, field("ref__c", "TEXT", { label: "Reference", externalId: true, unique: true })],
  };

  it("is offered only for an object that has an external identifier field, and needs that column mapped", async () => {
    const { calls } = open({}, withExternal);
    await choose("Name,Amount\nAcme,5\n");

    fireEvent.change(screen.getByLabelText("What to do with each row"), { target: { value: "UPSERT" } });
    expect(screen.getByLabelText("Field that finds the record")).toHaveValue("ref__c");
    fireEvent.click(screen.getByRole("button", { name: "Check the file" }));

    expect(await screen.findByTestId("import-problem")).toHaveTextContent("needs the column of the external identifier");
    expect(calls.some((call) => call.key === START)).toBe(false);
  });

  it("is not offered for an object that has none", () => {
    open();

    expect(within(screen.getByLabelText("What to do with each row")).getByRole("option", { name: /Update the record/ })).toBeDisabled();
  });
});
