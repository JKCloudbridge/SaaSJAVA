import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { sizeTheGrid } from "@/test/virtualDom";
import { TemplatesPage } from "./TemplatesPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";
const RECORDS = "/api/v1/objects/InspectionTemplate/records";

function row(id: string, fields: Record<string, unknown>) {
  return {
    linked: {},
    record: {
      id,
      objectApiName: "InspectionTemplate",
      ownerId: "0198f5c0-0000-7000-8000-000000000001",
      fields,
      version: 0,
      createdAt: "2026-10-22T08:00:00Z",
      updatedAt: "2026-10-22T08:00:00Z",
    },
  };
}

function form(create: boolean) {
  return () =>
    json({
      data: {
        objectApiName: "InspectionTemplate",
        label: "Template",
        pluralLabel: "Templates",
        fields: [],
        sections: [],
        recordTypes: [],
        actions: { create, update: create, delete: false },
      },
    });
}

function answers(create = true) {
  return {
    "GET /api/v1/objects/InspectionTemplate/form": form(create),
    [`POST ${RECORDS}/query`]: () =>
      json({
        data: [
          row(ID, { name: "Fire exit check", category: "WI", subCategory: "PRD", number: "WI/PRD/0001" }),
          row("0198f5c0-0000-7000-8000-000000000043", { name: "Stores audit", category: "SOP", retired: true }),
        ],
        pagination: { limit: 200, hasMore: false },
      }),
    [`GET ${RECORDS}/${ID}`]: () => json({ data: row(ID, { name: "Fire exit check", category: "WI", subCategory: "PRD" }).record }),
    [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
      json({ data: { versions: [], canChange: true, limits: LIMITS } }),
  };
}

beforeEach(() => {
  setUrl("/apps/inspection/templates");
});

describe("the Templates page", () => {
  it("lists the templates the member may read, with their category, number and whether they are retired", async () => {
    fakeApi(answers());

    render(<TemplatesPage />);

    expect(await screen.findByText("Fire exit check")).toBeInTheDocument();
    expect(screen.getByText("WI/PRD/0001")).toBeInTheDocument();
    expect(screen.getByText("Not numbered yet")).toBeInTheDocument();
    expect(screen.getByText("Retired")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Templates" })).toBeInTheDocument();
  });

  it("offers New only when the API says the member may create a template", async () => {
    fakeApi(answers(true));
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByRole("button", { name: "New template" })).toBeInTheDocument();
    unmount();

    fakeApi(answers(false));
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(screen.queryByRole("button", { name: "New template" })).toBeNull();
  });

  it("says in the API's words when the list cannot be read", async () => {
    fakeApi({
      ...answers(),
      [`POST ${RECORDS}/query`]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText("You are not allowed to perform this action.")).toBeInTheDocument();
  });

  it("filters the loaded rows with the search box", async () => {
    fakeApi(answers());
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search templates" }), { target: { value: "stores" } });

    expect(screen.queryByText("Fire exit check")).toBeNull();
    expect(screen.getByText("Stores audit")).toBeInTheDocument();
  });

  it("opens a template in the same pane: its facts and its sheet, and goes back", async () => {
    fakeApi(answers());
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Fire exit check" }));

    expect(currentUrl()).toBe(`/apps/inspection/templates?open=${ID}`);
    const detail = await screen.findByTestId("template-detail");
    expect(within(detail).getByRole("heading", { name: "Fire exit check" })).toBeInTheDocument();
    expect(within(detail).getByText("WI")).toBeInTheDocument();
    expect(within(detail).getByText("Not numbered yet")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Start the first version" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Edit the details" })).toHaveAttribute("href", `/records/InspectionTemplate/${ID}`);
    fireEvent.click(within(detail).getByRole("button", { name: "Templates" }));
    await waitFor(() => expect(currentUrl()).toBe("/apps/inspection/templates"));
  });

  it("shows a template the API does not give as not found, in one set of words", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({
      ...answers(),
      [`GET ${RECORDS}/${ID}`]: () => refusal("NOT_FOUND", "The requested resource was not found.", 404),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText(/This template is not available/)).toBeInTheDocument();
    expect(screen.queryByTestId("sheet-workspace")).toBeNull();
  });

  it("gives the sheet only the record it belongs to, never an organization", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    const calls = fakeApi({
      ...answers(),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
        json({
          data: {
            versions: [
              {
                versionNo: 1,
                status: "ACTIVE",
                dimension: 2,
                rowsMode: "FIXED",
                gridBytes: 10,
                gridHash: "a".repeat(64),
                version: 0,
                createdAt: "2026-10-22T08:00:00Z",
                updatedAt: "2026-10-22T08:00:00Z",
              },
            ],
            canChange: false,
            limits: LIMITS,
          },
        }),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions/1`]: () =>
        json({
          data: {
            version: {
              versionNo: 1,
              status: "ACTIVE",
              dimension: 2,
              rowsMode: "FIXED",
              gridBytes: 10,
              gridHash: "a".repeat(64),
              version: 0,
              createdAt: "2026-10-22T08:00:00Z",
              updatedAt: "2026-10-22T08:00:00Z",
            },
            grid: sampleDoc(),
          },
        }),
    });

    render(<TemplatesPage />);

    expect(await screen.findByRole("grid", { name: "Fire exit check, version 1" })).toHaveAttribute("aria-readonly", "true");
    const asked = calls.map((c) => c.key).filter((k) => k.includes("/sheets/"));
    expect(asked).toEqual([`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`, `GET /api/v1/sheets/InspectionTemplate/${ID}/versions/1`]);
  });
});
