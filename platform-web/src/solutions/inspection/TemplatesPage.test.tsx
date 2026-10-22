import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { sizeTheGrid } from "@/test/virtualDom";
import { TemplatesPage } from "./TemplatesPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";
const OTHER = "0198f5c0-0000-7000-8000-000000000043";
const RECORDS = "/api/v1/objects/InspectionTemplate/records";

const NONE = {
  state: "NONE",
  activeVersion: 0,
  approvedVersion: 0,
  inReviewVersion: 0,
  draftVersion: 0,
  retiredVersion: 0,
  highestVersion: 0,
};

function templateRow(id: string, name: string, extra: Record<string, unknown> = {}, lifecycle = NONE) {
  return { id, name, retired: false, lifecycle, ...extra };
}

function pageOf(rows: unknown[], extra: Record<string, unknown> = {}) {
  return { data: { rows, canCreate: true, filtered: false, ...extra } };
}

function formWith(create: boolean) {
  const picks = (values: string[]) => values.map((value) => ({ value, label: `${value} label`, active: true }));
  return () =>
    json({
      data: {
        objectApiName: "InspectionTemplate",
        label: "Template",
        pluralLabel: "Templates",
        fields: [
          { apiName: "category", label: "Category", type: "PICKLIST", values: picks(["WI", "SOP", "CSF"]) },
          {
            apiName: "subCategory",
            label: "Sub-category",
            type: "PICKLIST",
            values: picks(["PRD", "QLT", "CUSA"]),
            dependency: { controllingField: "category", allowed: { WI: ["PRD", "QLT"], SOP: ["PRD"], CSF: ["CUSA"] } },
          },
        ],
        sections: [],
        recordTypes: [],
        actions: { create, update: create, delete: false },
      },
    });
}

const lifecycleOf = (extra: Record<string, unknown> = {}, numbers = NONE) => ({
  data: {
    id: ID,
    retired: false,
    lifecycle: numbers,
    audience: [],
    canPublish: false,
    canUnpublish: false,
    canRetire: false,
    numberPreviewComplete: false,
    ...extra,
  },
});

const NO_APPROVAL = {
  data: {
    available: false,
    locked: false,
    processes: [],
    actions: {
      canApprove: false,
      canAssign: false,
      canCancel: false,
      canReapply: false,
      canReassign: false,
      canRecall: false,
      canReject: false,
      canSubmit: false,
    },
  },
};

function listAnswers(create = true) {
  const rows = [
    templateRow(ID, "Fire exit check", { number: "WI/PRD/01", category: "WI", subCategory: "PRD" }, {
      ...NONE,
      state: "ACTIVE",
      activeVersion: 3,
      inReviewVersion: 4,
      highestVersion: 4,
    }),
    templateRow(OTHER, "Stores audit", { category: "SOP", retired: true }, { ...NONE, state: "RETIRED", retiredVersion: 2 }),
  ];
  return {
    "GET /api/v1/objects/InspectionTemplate/form": formWith(create),
    "GET /api/v1/templates": () => json(pageOf(rows, { canCreate: create })),
    [`GET ${RECORDS}/${ID}`]: () =>
      json({
        data: {
          id: ID,
          objectApiName: "InspectionTemplate",
          ownerId: "0198f5c0-0000-7000-8000-000000000001",
          fields: { name: "Fire exit check", category: "WI", subCategory: "PRD" },
          version: 0,
          createdAt: "2026-10-22T08:00:00Z",
          updatedAt: "2026-10-22T08:00:00Z",
        },
      }),
    [`GET ${RECORDS}/${ID}/approval`]: () => json(NO_APPROVAL),
    [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf()),
    [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
      json({ data: { versions: [], canChange: true, limits: LIMITS } }),
  } as Record<string, Handler>;
}

beforeEach(() => {
  setUrl("/apps/inspection/templates");
});

describe("the master list", () => {
  it("lists the templates with their number, category, versions and status", async () => {
    fakeApi(listAnswers());

    render(<TemplatesPage />);

    expect(await screen.findByText("Fire exit check")).toBeInTheDocument();
    expect(screen.getByText("WI/PRD/01")).toBeInTheDocument();
    expect(screen.getByText("Active v3, v4 in review")).toBeInTheDocument();
    expect(within(screen.getByTestId("templates-list")).getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Not numbered yet")).toBeInTheDocument();
    expect(screen.getByText("Retired, v2 was the last in use")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Templates" })).toBeInTheDocument();
  });

  it("offers New only when the API says the member may create a template", async () => {
    fakeApi(listAnswers(true));
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByRole("button", { name: "New template" })).toBeInTheDocument();
    unmount();

    fakeApi(listAnswers(false));
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(screen.queryByRole("button", { name: "New template" })).toBeNull();
  });

  it("says in the API's words when the list cannot be read", async () => {
    fakeApi({
      ...listAnswers(),
      "GET /api/v1/templates": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText("You are not allowed to perform this action.")).toBeInTheDocument();
  });

  it("asks the API for the filters chosen, narrows the sub-categories to the category, and clears them", async () => {
    const asked: string[] = [];
    fakeApi({
      ...listAnswers(),
      "GET /api/v1/templates": (request) => {
        asked.push(new URL(request.url).search);
        return json(pageOf([templateRow(ID, "Fire exit check")]));
      },
    });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(asked[0]).toContain("sort=name");

    fireEvent.change(screen.getByLabelText("Category"), { target: { value: "WI" } });
    const sub = screen.getByLabelText("Sub-category");
    expect(within(sub).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Any sub-category",
      "PRD label",
      "QLT label",
    ]);
    fireEvent.change(sub, { target: { value: "QLT" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "IN_REVIEW" } });
    fireEvent.change(screen.getByLabelText("Number or name"), { target: { value: " WI/ " } });
    fireEvent.click(screen.getByRole("button", { name: "Show" }));

    await waitFor(() => expect(asked.at(-1)).toContain("category=WI"));
    const last = new URLSearchParams(asked.at(-1));
    expect(last.get("subCategory")).toBe("QLT");
    expect(last.get("state")).toBe("IN_REVIEW");
    expect(last.get("search")).toBe("WI/");

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    await waitFor(() => expect(new URLSearchParams(asked.at(-1)).get("category")).toBeNull());
    expect(screen.getByLabelText("Category")).toHaveValue("");
  });

  it("shows more with the cursor of the page, and says that a status filter can make a short page", async () => {
    const cursors: Array<string | null> = [];
    fakeApi({
      ...listAnswers(),
      "GET /api/v1/templates": (request) => {
        const cursor = new URL(request.url).searchParams.get("cursor");
        cursors.push(cursor);
        return json(
          cursor === null
            ? pageOf([templateRow(ID, "Fire exit check")], { nextCursor: "next-1", filtered: true })
            : pageOf([templateRow(OTHER, "Stores audit")], { filtered: true }),
        );
      },
    });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(screen.getByText(/applied to each page after it is read/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    expect(await screen.findByText("Stores audit")).toBeInTheDocument();
    expect(screen.getByText("Fire exit check")).toBeInTheDocument();
    expect(cursors).toEqual([null, "next-1"]);
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("says plainly when nothing matches", async () => {
    fakeApi({ ...listAnswers(), "GET /api/v1/templates": () => json(pageOf([])) });
    render(<TemplatesPage />);
    expect(await screen.findByText("There are no templates you can see yet.")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "DRAFT" } });
    fireEvent.click(screen.getByRole("button", { name: "Show" }));
    expect(await screen.findByText("No template matches.")).toBeInTheDocument();
  });
});

describe("one template", () => {
  it("opens in the same pane with its facts, its lifecycle and its sheet, and goes back", async () => {
    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({}, { ...NONE, state: "ACTIVE", activeVersion: 3, inReviewVersion: 4, highestVersion: 4 })),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Fire exit check" }));

    expect(currentUrl()).toBe(`/apps/inspection/templates?open=${ID}`);
    const detail = await screen.findByTestId("template-detail");
    expect(within(detail).getByRole("heading", { name: "Fire exit check" })).toBeInTheDocument();
    expect(within(detail).getByText("WI")).toBeInTheDocument();
    expect(within(detail).getByText("Not numbered yet")).toBeInTheDocument();
    expect(await within(detail).findByTestId("lifecycle-text")).toHaveTextContent("Active v3, v4 in review");
    expect(await screen.findByRole("button", { name: "Start the first version" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Edit the details" })).toHaveAttribute("href", `/records/InspectionTemplate/${ID}`);
    fireEvent.click(within(detail).getByRole("button", { name: "Templates" }));
    await waitFor(() => expect(currentUrl()).toBe("/apps/inspection/templates"));
  });

  it("shows a template the API does not give as not found, in one set of words", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}`]: () => refusal("NOT_FOUND", "The requested resource was not found.", 404),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText(/This template is not available/)).toBeInTheDocument();
    expect(screen.queryByTestId("sheet-workspace")).toBeNull();
  });

  it("gives the sheet only the record it belongs to, never an organization, and draws a version in use read-only", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    const summary = {
      versionNo: 1,
      status: "ACTIVE",
      dimension: 2,
      rowsMode: "FIXED",
      gridBytes: 10,
      gridHash: "a".repeat(64),
      version: 0,
      createdAt: "2026-10-22T08:00:00Z",
      updatedAt: "2026-10-22T08:00:00Z",
      reviewEdits: 0,
    };
    const calls = fakeApi({
      ...listAnswers(),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
        json({ data: { versions: [summary], canChange: false, limits: LIMITS } }),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions/1`]: () => json({ data: { version: summary, grid: sampleDoc() } }),
    });

    render(<TemplatesPage />);

    expect(await screen.findByRole("grid", { name: "Fire exit check, version 1" })).toHaveAttribute("aria-readonly", "true");
    const asked = calls.map((c) => c.key).filter((k) => k.includes("/sheets/"));
    expect(asked).toEqual([`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`, `GET /api/v1/sheets/InspectionTemplate/${ID}/versions/1`]);
  });

  it("offers Publish, Unpublish and Retire only as the lifecycle's flags say, and nothing when it says none", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({ ...listAnswers(), [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf()) });
    const { unmount } = render(<TemplatesPage />);
    await screen.findByTestId("template-detail");
    await screen.findByTestId("audience-none");
    expect(screen.queryByRole("button", { name: "Publish" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Unpublish" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Retire" })).toBeNull();
    unmount();

    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({ canPublish: true, canUnpublish: true, canRetire: true })),
    });
    render(<TemplatesPage />);
    expect(await screen.findByRole("button", { name: "Publish" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Unpublish" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retire" })).toBeInTheDocument();
  });

  it("lists who it is published to, and says when a group no longer exists", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(
          lifecycleOf({
            audience: [
              {
                location: "PLANT1",
                department: "PRD",
                locationLabel: "Plant 1",
                departmentLabel: "Production",
                versionNo: 3,
                publishedAt: "2026-10-22T09:00:00Z",
                groupAvailable: true,
              },
              {
                location: "HEAD",
                department: "HR",
                locationLabel: "Head office",
                departmentLabel: "Human resources",
                versionNo: 3,
                publishedAt: "2026-10-22T09:00:00Z",
                groupAvailable: false,
              },
            ],
          }),
        ),
    });

    render(<TemplatesPage />);

    const audience = await screen.findByTestId("audience");
    expect(within(audience).getByText(/Plant 1 · Production/)).toBeInTheDocument();
    expect(within(audience).getByText(/Head office · Human resources/)).toBeInTheDocument();
    expect(within(audience).getByText(/its group no longer exists/)).toBeInTheDocument();
  });

  it("takes the template back, and shows the API's words when it refuses", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    let attempts = 0;
    const calls = fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ canUnpublish: true })),
      [`POST /api/v1/templates/${ID}/unpublish`]: () =>
        ++attempts === 1 ? refusal("CONFLICT", "This template is not published.", 409) : json(lifecycleOf()),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Unpublish" }));
    expect(await screen.findByText("This template is not published.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Unpublish" }));
    expect(await screen.findByText(/was taken back from the people/)).toBeInTheDocument();
    expect(calls.filter((call) => call.key === `POST /api/v1/templates/${ID}/unpublish`)).toHaveLength(2);
  });

  it("asks once before it retires a template, and then retires it", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    const calls = fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ canRetire: true })),
      [`POST /api/v1/templates/${ID}/retire`]: () => json(lifecycleOf({ retired: true })),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Retire" }));
    expect(calls.some((call) => call.key.endsWith("/retire"))).toBe(false);
    expect(screen.getByRole("alertdialog", { name: "Retire this template" })).toHaveTextContent(/never given to another template/);
    fireEvent.click(screen.getByRole("button", { name: "Retire it" }));

    expect(await screen.findByText("The template is retired.")).toBeInTheDocument();
    expect(calls.filter((call) => call.key === `POST /api/v1/templates/${ID}/retire`)).toHaveLength(1);
  });

  it("says what an approval gives only while the approval is the member's to give, and shows the start of the number", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    const approving = {
      data: { ...NO_APPROVAL.data, available: true, actions: { ...NO_APPROVAL.data.actions, canApprove: true } },
    };
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () => json(approving),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ numberPreview: "WI/PRD/" })),
    });
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByTestId("number-note")).toHaveTextContent("Approving this gives it its number, WI/PRD/…, followed by the next serial.");
    unmount();

    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ numberPreview: "WI/PRD/" })),
    });
    render(<TemplatesPage />);
    await screen.findByTestId("audience-none");
    expect(screen.queryByTestId("number-note")).toBeNull();
  });

  it("says the whole number when it is made of typed parts only", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    const approving = {
      data: { ...NO_APPROVAL.data, available: true, actions: { ...NO_APPROVAL.data.actions, canApprove: true } },
    };
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () => json(approving),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({ numberPreview: "BP/2026", numberPreviewComplete: true })),
    });

    render(<TemplatesPage />);

    expect(await screen.findByTestId("number-note")).toHaveTextContent("Approving this gives it its number, BP/2026.");
  });
});

describe("a draft beside a version in review", () => {
  it("says that it waits until the approval of the other version has ended", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({}, { ...NONE, state: "IN_REVIEW", inReviewVersion: 4, draftVersion: 5, highestVersion: 5 })),
    });

    render(<TemplatesPage />);

    expect(await screen.findByTestId("draft-waits")).toHaveTextContent(
      "Version 5 is a draft. It can be sent for approval after the approval of version 4 has ended",
    );
  });

  it("says nothing of the kind when nothing is in review", async () => {
    setUrl(`/apps/inspection/templates?open=${ID}`);
    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({}, { ...NONE, state: "DRAFT", draftVersion: 1, highestVersion: 1 })),
    });

    render(<TemplatesPage />);

    await screen.findByTestId("audience-none");
    expect(screen.queryByTestId("draft-waits")).toBeNull();
  });
});
