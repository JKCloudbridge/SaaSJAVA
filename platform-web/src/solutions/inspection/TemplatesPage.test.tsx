import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { sizeTheGrid } from "@/test/virtualDom";
import { TemplatesPage } from "./TemplatesPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

// The abilities of the signed-in member, for the note that only people who can set things up read; null is no session.
const session = vi.hoisted(() => ({ abilities: null as readonly string[] | null }));
vi.mock("@/lib/session/SessionProvider", async (original) => ({
  ...(await original<typeof import("@/lib/session/SessionProvider")>()),
  useAbilities: () => session.abilities,
}));

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
        layout: {
          source: "LAYOUT",
          sections: [{ label: "Details", columns: 1, fields: ["name", "category", "subCategory"] }],
        },
        fields: [
          {
            apiName: "name",
            label: "Name",
            type: "TEXT",
            editable: true,
            required: true,
            sortable: true,
            filterOperators: ["EQ", "CONTAINS"],
          },
          { apiName: "number", label: "Number", type: "TEXT", sortable: true, filterOperators: ["EQ", "CONTAINS"] },
          {
            apiName: "category",
            label: "Category",
            type: "PICKLIST",
            values: picks(["WI", "SOP", "CSF"]),
            sortable: true,
            filterOperators: ["EQ", "IN"],
          },
          {
            apiName: "subCategory",
            label: "Sub-category",
            type: "PICKLIST",
            values: picks(["PRD", "QLT", "CUSA"]),
            sortable: true,
            filterOperators: ["EQ"],
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
    "GET /api/v1/objects/InspectionTemplate/views": () => json({ data: [] }),
    "POST /api/v1/templates/query": () => json(pageOf(rows, { canCreate: create })),
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
    [`GET /api/v1/templates/${ID}/fill-in-options`]: () =>
      json({ data: { canStart: false, activeVersion: 0, reason: "You do not have the right to fill in templates." } }),
    [`GET /api/v1/templates/${ID}/change-requests`]: () =>
      json({
        data: { requests: [], canReview: false, canRequest: false, activeVersion: 0, draftVersion: 0, openCount: 0 },
      }),
    [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
      json({ data: { versions: [], canChange: true, limits: LIMITS } }),
  } as Record<string, Handler>;
}

beforeEach(() => {
  setUrl("/apps/inspection/templates");
  session.abilities = null;
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
    expect(screen.getByRole("heading", { name: /All templates/ })).toBeInTheDocument();
  });

  it("offers New next to the search box only when the API says the member may create a template", async () => {
    fakeApi(listAnswers(true));
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByRole("button", { name: "New" })).toBeInTheDocument();
    unmount();

    fakeApi(listAnswers(false));
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(screen.queryByRole("button", { name: "New" })).toBeNull();
  });

  it("says in the API's words when the list cannot be read", async () => {
    fakeApi({
      ...listAnswers(),
      "POST /api/v1/templates/query": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText("You are not allowed to perform this action.")).toBeInTheDocument();
  });

  it("has one search box that asks the server, and no filter pane", async () => {
    const asked: string[] = [];
    fakeApi({
      ...listAnswers(),
      "POST /api/v1/templates/query": (request) =>
        request.text().then((body) => {
          asked.push(body);
          return json(pageOf([templateRow(ID, "Fire exit check")]));
        }),
    });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    expect(screen.queryByTestId("template-filters")).toBeNull();

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "wi/prd" } });

    await waitFor(() => expect(asked.some((body) => JSON.parse(body).search === "wi/prd")).toBe(true));
  });

  it("orders by a header on the server: first click high to low, second low to high, third back", async () => {
    const asked: Array<{ sort?: unknown[] }> = [];
    fakeApi({
      ...listAnswers(),
      "POST /api/v1/templates/query": (request) =>
        request.text().then((body) => {
          asked.push(JSON.parse(body));
          return json(pageOf([templateRow(ID, "Fire exit check"), templateRow(OTHER, "Stores audit")]));
        }),
    });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");
    const header = () => screen.getByRole("button", { name: /^Name/ });
    const last = () => asked[asked.length - 1]?.sort;

    fireEvent.click(header());
    await waitFor(() => expect(last()).toEqual([{ field: "name", descending: true }]));
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute("aria-sort", "descending");
    fireEvent.click(header());
    await waitFor(() => expect(last()).toEqual([{ field: "name", descending: false }]));
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute("aria-sort", "ascending");
    fireEvent.click(header());
    await waitFor(() => expect(last()).toEqual([]));
    expect(screen.getByRole("columnheader", { name: /Name/ })).not.toHaveAttribute("aria-sort");
  });

  it("shows more with the cursor of the page", async () => {
    const cursors: Array<string | null> = [];
    fakeApi({
      ...listAnswers(),
      "POST /api/v1/templates/query": (request) =>
        request.text().then((body) => {
          const cursor = (JSON.parse(body) as { cursor?: string }).cursor ?? null;
          cursors.push(cursor);
          return json(
            cursor === null
              ? pageOf([templateRow(ID, "Fire exit check")], { nextCursor: "next-1", filtered: true })
              : pageOf([templateRow(OTHER, "Stores audit")], { filtered: true }),
          );
        }),
    });
    render(<TemplatesPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    expect(await screen.findByText("Stores audit")).toBeInTheDocument();
    expect(screen.getByText("Fire exit check")).toBeInTheDocument();
    expect(cursors).toEqual([null, "next-1"]);
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("says plainly when there are no templates", async () => {
    fakeApi({ ...listAnswers(), "POST /api/v1/templates/query": () => json(pageOf([])) });
    render(<TemplatesPage />);
    expect(await screen.findByText("There are no templates you can see yet.")).toBeInTheDocument();
  });
});

const OPEN = `/apps/inspection/templates?open=${ID}`;

const approvalWith = (actions: Record<string, boolean>, extra: Record<string, unknown> = {}) => ({
  data: { ...NO_APPROVAL.data, available: true, ...extra, actions: { ...NO_APPROVAL.data.actions, ...actions } },
});

describe("one template", () => {
  it("opens in the same pane with its status, its details and no way back but the Templates tab", async () => {
    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({}, { ...NONE, state: "ACTIVE", activeVersion: 3, inReviewVersion: 4, highestVersion: 4 })),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Fire exit check" }));

    expect(currentUrl()).toBe(OPEN);
    const detail = await screen.findByTestId("template-detail");
    expect(within(detail).getByRole("heading", { name: "Fire exit check" })).toBeInTheDocument();
    expect(await within(detail).findByTestId("lifecycle-text")).toHaveTextContent("Active v3, v4 in review");
    expect(within(detail).getByRole("tab", { name: "Details", selected: true })).toBeInTheDocument();
    expect(within(detail).queryByRole("button", { name: "Templates" })).toBeNull();
    expect(within(detail).queryByRole("link", { name: "Edit the details" })).toBeNull();
    expect(await within(detail).findByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("tells a person who can set things up that no approval process exists, and nobody else", async () => {
    setUrl(OPEN);
    session.abilities = ["metadata.manage", "metadata.view"];
    fakeApi(listAnswers());
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByTestId("no-approval-note")).toHaveTextContent(
      "No approval process is set up for templates yet.",
    );
    unmount();

    session.abilities = [];
    fakeApi(listAnswers());
    render(<TemplatesPage />);
    await screen.findByTestId("audience-none");
    expect(screen.queryByTestId("no-approval-note")).toBeNull();
  });

  it("draws no such note when an approval process is available", async () => {
    setUrl(OPEN);
    session.abilities = ["metadata.manage", "metadata.view"];
    fakeApi({ ...listAnswers(), [`GET ${RECORDS}/${ID}/approval`]: () => json(approvalWith({ canSubmit: true })) });
    render(<TemplatesPage />);

    await screen.findByTestId("audience-none");

    expect(screen.queryByTestId("no-approval-note")).toBeNull();
  });

  it("keeps the open tab in the address and offers Edit on the Details tab only", async () => {
    setUrl(OPEN);
    fakeApi(listAnswers());
    render(<TemplatesPage />);
    await screen.findByRole("button", { name: "Edit" });

    fireEvent.click(screen.getByRole("tab", { name: "Sheet" }));

    expect(currentUrl()).toBe(`${OPEN}&tab=fill-ins`.replace("fill-ins", "sheet"));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Edit" })).toBeNull());
  });

  it("edits the details in place: Save and Cancel replace Edit, and nothing else opens", async () => {
    setUrl(OPEN);
    const calls = fakeApi({
      ...listAnswers(),
      [`PATCH ${RECORDS}/${ID}`]: () =>
        json({
          data: {
            id: ID,
            objectApiName: "InspectionTemplate",
            ownerId: "0198f5c0-0000-7000-8000-000000000001",
            fields: { name: "Fire exit check", category: "WI", subCategory: "PRD" },
            version: 1,
            createdAt: "2026-10-22T08:00:00Z",
            updatedAt: "2026-10-22T08:00:00Z",
          },
        }),
      [`POST ${RECORDS}/validate`]: () => json({ data: { calculated: {}, cleared: [] } }),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));

    expect(await screen.findByTestId("template-save")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.getByRole("tab", { name: "Sheet" })).toBeDisabled();
    expect(currentUrl()).toBe(OPEN);

    fireEvent.click(screen.getByTestId("template-cancel"));
    expect(await screen.findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(calls.some((call) => call.key.startsWith("PATCH"))).toBe(false);
  });

  it("shows a template the API does not give as not found, in one set of words", async () => {
    setUrl(OPEN);
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}`]: () => refusal("NOT_FOUND", "The requested resource was not found.", 404),
    });

    render(<TemplatesPage />);

    expect(await screen.findByText(/This template is not available/)).toBeInTheDocument();
    expect(screen.queryByTestId("sheet-workspace")).toBeNull();
  });

  it("lists the versions of the sheet first, and opens one read-only when it is in use", async () => {
    setUrl(`${OPEN}&tab=sheet`);
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

    fireEvent.click(await screen.findByRole("button", { name: "Open version 1" }));
    expect(await screen.findByRole("grid", { name: "Fire exit check, version 1" })).toHaveAttribute("aria-readonly", "true");
    // The buttons of the open version are in the row of the tabs, and the old title line is gone.
    const tabsRow = screen.getByRole("tablist", { name: "Parts of the template" }).parentElement as HTMLElement;
    expect(within(tabsRow).getByRole("button", { name: /Versions/ })).toBeInTheDocument();
    expect(within(tabsRow).getByRole("tab", { name: "Form view" })).toBeInTheDocument();
    expect(within(tabsRow).getByRole("tab", { name: "Grid view" })).toBeInTheDocument();
    expect(screen.queryByText(/174 bytes|10 bytes/)).toBeNull();
    const asked = calls.map((c) => c.key).filter((k) => k.includes("/sheets/"));
    expect(asked).toEqual([`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`, `GET /api/v1/sheets/InspectionTemplate/${ID}/versions/1`]);

    fireEvent.click(screen.getByRole("button", { name: /Versions/ }));
    expect(await screen.findByRole("button", { name: "Open version 1" })).toBeInTheDocument();
  });

  it("offers Publish beside Edit, and Unpublish and Retire in the more menu, only as the lifecycle's flags say", async () => {
    setUrl(OPEN);
    fakeApi({ ...listAnswers(), [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf()) });
    const { unmount } = render(<TemplatesPage />);
    await screen.findByTestId("audience-none");
    expect(screen.queryByRole("button", { name: "Publish" })).toBeNull();
    expect(screen.queryByRole("button", { name: "More actions" })).toBeNull();
    unmount();

    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({ canPublish: true, canUnpublish: true, canRetire: true })),
    });
    render(<TemplatesPage />);
    expect(await screen.findByRole("button", { name: "Publish" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    expect(screen.getByRole("menuitem", { name: "Unpublish" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Retire" })).toBeInTheDocument();
  });

  it("lists who it is published to, and says when a group no longer exists", async () => {
    setUrl(OPEN);
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
    setUrl(OPEN);
    let attempts = 0;
    const calls = fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ canUnpublish: true })),
      [`POST /api/v1/templates/${ID}/unpublish`]: () =>
        ++attempts === 1 ? refusal("CONFLICT", "This template is not published.", 409) : json(lifecycleOf()),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Unpublish" }));
    expect(await screen.findByText("This template is not published.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Unpublish" }));
    expect(await screen.findByText(/was taken back from the people/)).toBeInTheDocument();
    expect(calls.filter((call) => call.key === `POST /api/v1/templates/${ID}/unpublish`)).toHaveLength(2);
  });

  it("asks once before it retires a template, and then retires it", async () => {
    setUrl(OPEN);
    const calls = fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ canRetire: true })),
      [`POST /api/v1/templates/${ID}/retire`]: () => json(lifecycleOf({ retired: true })),
    });
    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Retire" }));
    expect(calls.some((call) => call.key.endsWith("/retire"))).toBe(false);
    expect(screen.getByRole("alertdialog", { name: "Retire this template" })).toHaveTextContent(/never given to another template/);
    fireEvent.click(screen.getByRole("button", { name: "Retire it" }));

    expect(await screen.findByText("The template is retired.")).toBeInTheDocument();
    expect(calls.filter((call) => call.key === `POST /api/v1/templates/${ID}/retire`)).toHaveLength(1);
  });

  it("shows the start of the number only while the approval is the member's to give", async () => {
    setUrl(OPEN);
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () => json(approvalWith({ canApprove: true })),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ numberPreview: "WI/PRD/" })),
    });
    const { unmount } = render(<TemplatesPage />);
    expect(await screen.findByTestId("number-note")).toHaveTextContent("WI/PRD/…");
    unmount();

    fakeApi({
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ numberPreview: "WI/PRD/" })),
    });
    render(<TemplatesPage />);
    await screen.findByTestId("audience-none");
    expect(screen.queryByTestId("number-note")).toBeNull();
  });

  it("shows the whole number when it is made of typed parts only", async () => {
    setUrl(OPEN);
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () => json(approvalWith({ canApprove: true })),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({ numberPreview: "BP/2026", numberPreviewComplete: true })),
    });

    render(<TemplatesPage />);

    expect(await screen.findByTestId("number-note")).toHaveTextContent("BP/2026");
    expect(screen.getByTestId("number-note")).not.toHaveTextContent("…");
  });
});

describe("the buttons of the approval in the page header", () => {
  it("offers Submit for approval to the person who may submit, and nothing of approving", async () => {
    setUrl(OPEN);
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () => json(approvalWith({ canSubmit: true }, { processes: [{ apiName: "p1", label: "Template approval" }] })),
    });

    render(<TemplatesPage />);

    expect(await screen.findByRole("button", { name: "Submit for approval" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
  });

  it("offers Approve and Reject to an approver, and opens Publish once the approval ended and the member may publish", async () => {
    setUrl(OPEN);
    let decided = false;
    const summary = { id: "req-1", version: 1, status: "PENDING", processLabel: "Template approval", submittedBy: null };
    fakeApi({
      ...listAnswers(),
      [`GET ${RECORDS}/${ID}/approval`]: () =>
        json(
          approvalWith(
            { canApprove: !decided, canReject: !decided },
            { current: { summary, steps: [], history: [], historyVisible: false } },
          ),
        ),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () => json(lifecycleOf({ canPublish: decided })),
      "POST /api/v1/approvals/instances/req-1/approve": () => {
        decided = true;
        return json({ data: {} });
      },
      "GET /api/v1/templates/audience-options": () => json({ data: { locations: [], departments: [] } }),
    });

    render(<TemplatesPage />);

    expect(await screen.findByRole("button", { name: "Reject" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve" });
    fireEvent.click(within(dialog).getByRole("button", { name: /^Approve/ }));

    expect(await screen.findByRole("dialog", { name: "Publish this template" })).toBeInTheDocument();
  });
});

describe("change requests on the template page (FA5)", () => {
  const SUMMARY = {
    versionNo: 3,
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

  function reading(canRequest: boolean, requests: unknown[] = []) {
    return {
      ...listAnswers(),
      [`GET /api/v1/templates/${ID}/lifecycle`]: () =>
        json(lifecycleOf({}, { ...NONE, state: "ACTIVE", activeVersion: 3, highestVersion: 3 })),
      [`GET /api/v1/templates/${ID}/change-requests`]: () =>
        json({ data: { requests, canReview: false, canRequest, activeVersion: 3, draftVersion: 0, openCount: requests.length } }),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions`]: () =>
        json({ data: { versions: [SUMMARY], canChange: false, limits: LIMITS } }),
      [`GET /api/v1/sheets/InspectionTemplate/${ID}/versions/3`]: () => json({ data: { version: SUMMARY, grid: sampleDoc() } }),
    };
  }

  it("offers a reader of the version in use the panel to ask for a change under the grid, and lists their requests", async () => {
    setUrl(`${OPEN}&tab=sheet`);
    fakeApi(
      reading(true, [
        {
          id: "q-1",
          title: "The first row",
          status: "OPEN",
          versionNo: 3,
          requesterName: "Me",
          mine: true,
          createdAt: "2026-10-22T08:00:00Z",
          placesTotal: 1,
          placesInActive: 1,
          canComment: true,
          canAccept: false,
          canDecline: false,
          canTurn: false,
          canReopen: false,
          comments: [
            {
              id: "c-1",
              kind: "ROW",
              rowId: "r1",
              label: "First",
              inActiveVersion: true,
              text: "Wrong",
              createdAt: "2026-10-22T08:00:00Z",
              mine: true,
            },
          ],
        },
      ]),
    );

    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Open version 3" }));
    expect(await screen.findByTestId("annotate-panel")).toBeInTheDocument();
    expect(screen.getByRole("grid", { name: "Fire exit check, version 3" })).toHaveAttribute("aria-readonly", "true");
    expect(await screen.findByRole("heading", { name: "The first row" })).toBeInTheDocument();
    expect(document.querySelector(".sheet-row-noted")).not.toBeNull();
  });

  it("offers no panel when the API says nobody can ask for a change", async () => {
    setUrl(`${OPEN}&tab=sheet`);
    fakeApi(reading(false));

    render(<TemplatesPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Open version 3" }));
    expect(await screen.findByRole("grid", { name: "Fire exit check, version 3" })).toBeInTheDocument();
    expect(screen.queryByTestId("annotate-panel")).toBeNull();
    expect(await screen.findByText(/You have not asked for a change to this template/)).toBeInTheDocument();
  });
});

const FILL_LIST = {
  "GET /api/v1/objects/InspectionFill/views": () => json({ data: [] }),
  "GET /api/v1/objects/InspectionFill/form": () =>
    json({
      data: {
        objectApiName: "InspectionFill",
        label: "Fill-in",
        pluralLabel: "Fill-ins",
        layout: { source: "LAYOUT", sections: [] },
        fields: [{ apiName: "name", label: "Name", type: "TEXT", sortable: true, filterOperators: ["EQ"] }],
        sections: [],
        recordTypes: [],
        actions: { create: false, update: false, delete: false },
      },
    }),
} as Record<string, Handler>;

describe("the Fill-ins tab (FF1)", () => {
  const fillRow = {
    fillId: "f-1",
    name: "Fill 1",
    templateId: ID,
    status: "DRAFT",
    versionNo: 3,
    versionInUse: true,
    rowCount: 2,
    createdAt: "2026-10-22T08:00:00Z",
    corrections: 0,
  };

  it("lists the fill-ins of this template and offers Start a fill-in where New is, only when the API says the member may", async () => {
    setUrl(`${OPEN}&tab=fill-ins`);
    const asked: string[] = [];
    fakeApi({
      ...listAnswers(),
      ...FILL_LIST,
      [`GET /api/v1/templates/${ID}/fill-in-options`]: () => json({ data: { canStart: true, activeVersion: 3 } }),
      "POST /api/v1/fill-ins/query": (request) =>
        request.text().then((body) => {
          asked.push((JSON.parse(body) as { templateId?: string }).templateId ?? "");
          return json({ data: { rows: [fillRow] } });
        }),
    });

    render(<TemplatesPage />);

    expect(await screen.findByRole("button", { name: "Start a fill-in" })).toBeInTheDocument();
    expect(await screen.findByText("Fill 1")).toBeInTheDocument();
    expect(asked[0]).toBe(ID);
  });

  it("draws no Start button and no explanation when a fill-in cannot be started", async () => {
    setUrl(`${OPEN}&tab=fill-ins`);
    fakeApi({ ...listAnswers(), ...FILL_LIST, "POST /api/v1/fill-ins/query": () => json({ data: { rows: [] } }) });

    render(<TemplatesPage />);

    expect(await screen.findByText("No fill-ins on this template yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start a fill-in" })).toBeNull();
    expect(screen.queryByText("You do not have the right to fill in templates.")).toBeNull();
  });
});

describe("the New template popup", () => {
  it("opens over the list, asks for the details, and closes on Escape without leaving the list", async () => {
    setUrl("/apps/inspection/templates?new=1");
    fakeApi(listAnswers());

    render(<TemplatesPage />);

    const dialog = await screen.findByRole("dialog", { name: "New template" });
    expect(within(dialog).queryByRole("link")).toBeNull();
    expect(await screen.findByTestId("templates-list")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(currentUrl()).toBe("/apps/inspection/templates"));
  });

  it("asks the author for Level then Document, narrows Document to the level, and leaves the identity pair out", async () => {
    setUrl("/apps/inspection/templates?new=1");
    const picks = (values: string[]) => values.map((value) => ({ value, label: `${value} label`, active: true }));
    fakeApi({
      ...listAnswers(),
      "GET /api/v1/objects/InspectionTemplate/form": () =>
        json({
          data: {
            objectApiName: "InspectionTemplate",
            label: "Template",
            pluralLabel: "Templates",
            layout: {
              source: "LAYOUT",
              sections: [{ label: "Details", columns: 1, fields: ["name", "level", "documentType", "category"] }],
            },
            fields: [
              { apiName: "name", label: "Template name", type: "TEXT", editable: true, required: true },
              { apiName: "level", label: "Level", type: "PICKLIST", editable: true, values: picks(["L1", "L2"]) },
              {
                apiName: "documentType",
                label: "Document",
                type: "PICKLIST",
                editable: true,
                values: picks(["DOCA", "DOCB", "DOCC"]),
                dependency: { controllingField: "level", allowed: { L1: ["DOCA", "DOCB"], L2: ["DOCC"] } },
              },
              { apiName: "category", label: "Identity category", type: "PICKLIST", editable: false, values: picks(["WI"]) },
            ],
            sections: [],
            recordTypes: [],
            actions: { create: true, update: true, delete: false },
          },
        }),
    });

    render(<TemplatesPage />);

    const dialog = await screen.findByRole("dialog", { name: "New template" });
    const level = await within(dialog).findByLabelText(/^Level/);
    const document = within(dialog).getByLabelText(/^Document/);
    const order = within(dialog).getAllByRole("combobox").map((box) => box.id);
    expect(order.indexOf(level.id)).toBeLessThan(order.indexOf(document.id));

    fireEvent.change(level, { target: { value: "L2" } });
    expect(within(document).getAllByRole("option").map((option) => option.getAttribute("value"))).toContain("DOCC");
    expect(within(document).queryByRole("option", { name: "DOCA label" })).toBeNull();
    expect(within(dialog).queryByLabelText(/^Identity category/)).toBeNull();
  });
});
