import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { FillInReportPage } from "./FillInReportPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

configure({ asyncUtilTimeout: 5000 });

const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";
const FILL = "0198f5c0-0000-7000-8000-000000000051";
const LIFECYCLE = { state: "ACTIVE", activeVersion: 3 };

function row(id: string, extra: Record<string, unknown> = {}) {
  return {
    fillId: id,
    templateId: TEMPLATE,
    name: "Fire exit check",
    status: "APPROVED",
    versionNo: 3,
    versionInUse: true,
    rowCount: 2,
    createdAt: "2026-10-23T08:00:00Z",
    updatedAt: "2026-10-23T09:00:00Z",
    ...extra,
  };
}

function answers(extra: Record<string, Handler> = {}): Record<string, Handler> {
  return {
    "GET /api/v1/templates": () =>
      json({
        data: {
          rows: [{ id: TEMPLATE, name: "Fire exit check", retired: false, lifecycle: LIFECYCLE }],
          canCreate: false,
          filtered: false,
        },
      }),
    "GET /api/v1/fill-ins/report/columns": () =>
      json({
        data: {
          versionNo: 3,
          columns: [
            { id: "c1", label: "Safety status", type: "picklist", values: ["Pass", "Fail"] },
            { id: "c2", label: "Amount", type: "number", values: [] },
          ],
        },
      }),
    "POST /api/v1/fill-ins/report": () => json({ data: { rows: [row(FILL)] } }),
    ...extra,
  };
}

beforeEach(() => {
  setUrl("/apps/inspection/s-fill-in-report");
});

describe("the report on fill-ins", () => {
  it("offers only the columns the author made reportable, and asks before anything is chosen", async () => {
    fakeApi(answers());
    render(<FillInReportPage />);

    expect(screen.getByText("Choose a template, a column and a value, then show the fill-ins.")).toBeInTheDocument();
    await screen.findByRole("option", { name: "Fire exit check" });
    fireEvent.change(screen.getByLabelText("Template"), { target: { value: TEMPLATE } });

    const column = await screen.findByLabelText("Column");
    expect(within(column).getByRole("option", { name: "Safety status" })).toBeInTheDocument();
    expect(within(column).getByRole("option", { name: "Amount" })).toBeInTheDocument();
  });

  it("sends the value in a body, never in the address, and lists what the API answers", async () => {
    const calls = fakeApi(answers());
    render(<FillInReportPage />);
    await screen.findByRole("option", { name: "Fire exit check" });
    fireEvent.change(screen.getByLabelText("Template"), { target: { value: TEMPLATE } });
    fireEvent.change(await screen.findByLabelText("Column"), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "Fail" } });
    fireEvent.click(screen.getByRole("button", { name: "Show fill-ins" }));

    expect(await screen.findByText("Fire exit check", { selector: "a, button, span, td, th" })).toBeTruthy();
    const sent = calls.find((call) => call.key === "POST /api/v1/fill-ins/report");
    expect(sent).toBeDefined();
    expect(JSON.parse(sent?.body ?? "{}")).toMatchObject({
      templateId: TEMPLATE,
      versionNo: 3,
      columnId: "c1",
      operator: "EQUALS",
      values: ["Fail"],
    });
    expect(calls.every((call) => !call.key.includes("Fail"))).toBe(true);
  });

  it("says what is missing instead of asking", async () => {
    const calls = fakeApi(answers());
    render(<FillInReportPage />);
    await screen.findByRole("option", { name: "Fire exit check" });
    fireEvent.change(screen.getByLabelText("Template"), { target: { value: TEMPLATE } });
    fireEvent.change(await screen.findByLabelText("Column"), { target: { value: "c2" } });
    fireEvent.click(screen.getByRole("button", { name: "Show fill-ins" }));

    expect(await screen.findByText("Give the value to look for.")).toBeInTheDocument();
    expect(calls.some((call) => call.key === "POST /api/v1/fill-ins/report")).toBe(false);
  });

  it("shows the API's own words when it refuses", async () => {
    fakeApi(
      answers({
        "POST /api/v1/fill-ins/report": () => refusal("VALIDATION_ERROR", "The condition does not fit the column.", 400),
      }),
    );
    render(<FillInReportPage />);
    await screen.findByRole("option", { name: "Fire exit check" });
    fireEvent.change(screen.getByLabelText("Template"), { target: { value: TEMPLATE } });
    fireEvent.change(await screen.findByLabelText("Column"), { target: { value: "c2" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "abc" } });
    fireEvent.click(screen.getByRole("button", { name: "Show fill-ins" }));

    expect(await screen.findByText("The condition does not fit the column.")).toBeInTheDocument();
  });

  it("keeps 'Show more' while the API sends a cursor, and opens a fill-in from a row", async () => {
    fakeApi(
      answers({
        "POST /api/v1/fill-ins/report": () => json({ data: { rows: [row(FILL, { archivedAt: "2026-10-23T10:00:00Z" })], nextCursor: FILL } }),
      }),
    );
    render(<FillInReportPage />);
    await screen.findByRole("option", { name: "Fire exit check" });
    fireEvent.change(screen.getByLabelText("Template"), { target: { value: TEMPLATE } });
    fireEvent.change(await screen.findByLabelText("Column"), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "Fail" } });
    fireEvent.click(screen.getByRole("button", { name: "Show fill-ins" }));

    expect(await screen.findByRole("button", { name: "Show more" })).toBeInTheDocument();
    expect(screen.getByText("Archived")).toBeInTheDocument();
    fireEvent.click(screen.getAllByText("Fire exit check").find((one) => one.closest("tr")) as HTMLElement);
    await waitFor(() => expect(currentUrl()).toContain(`open=${FILL}`));
  });
});
