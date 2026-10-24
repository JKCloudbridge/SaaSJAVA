import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { sizeTheGrid } from "@/test/virtualDom";
import { FillInsPage } from "./FillInsPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

sizeTheGrid();
configure({ asyncUtilTimeout: 5000 });

const FILL = "0198f5c0-0000-7000-8000-000000000051";
const OTHER = "0198f5c0-0000-7000-8000-000000000052";
const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";
const RECORDS = "/api/v1/objects/InspectionFill/records";

function row(id: string, extra: Record<string, unknown> = {}) {
  return {
    fillId: id,
    templateId: TEMPLATE,
    name: "Fire exit check",
    status: "DRAFT",
    versionNo: 3,
    versionInUse: true,
    rowCount: 2,
    createdAt: "2026-10-23T08:00:00Z",
    updatedAt: "2026-10-23T09:00:00Z",
    ...extra,
  };
}

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

function answers(rows = [row(FILL, { filledBy: "Pat Filler" }), row(OTHER, { status: "APPROVED", versionNo: 2, versionInUse: false })]) {
  return {
    "GET /api/v1/objects/InspectionFill/views": () => json({ data: [] }),
    "GET /api/v1/objects/InspectionFill/form": () =>
      json({
        data: {
          objectApiName: "InspectionFill",
          label: "Fill-in",
          pluralLabel: "Fill-ins",
          layout: { source: "LAYOUT", sections: [] },
          fields: [
            { apiName: "name", label: "Name", type: "TEXT", sortable: true, filterOperators: ["EQ", "CONTAINS"] },
            {
              apiName: "status",
              label: "Status",
              type: "PICKLIST",
              values: [{ value: "DRAFT", label: "Draft", active: true }],
              sortable: true,
              filterOperators: ["EQ"],
            },
          ],
          sections: [],
          recordTypes: [],
          actions: { create: false, update: false, delete: false },
        },
      }),
    "POST /api/v1/fill-ins/query": () => json({ data: { rows } }),
    [`GET ${RECORDS}/${FILL}`]: () =>
      json({
        data: {
          id: FILL,
          objectApiName: "InspectionFill",
          ownerId: "0198f5c0-0000-7000-8000-000000000001",
          fields: { name: "Fire exit check", templateId: TEMPLATE, status: "DRAFT" },
          version: 1,
          createdAt: "2026-10-23T08:00:00Z",
          updatedAt: "2026-10-23T08:00:00Z",
        },
      }),
    [`GET ${RECORDS}/${FILL}/approval`]: () => json(NO_APPROVAL),
    [`GET ${RECORDS}/${FILL}/files`]: () => json({ data: [] }),
    [`GET /api/v1/sheet-fills/InspectionFill/${FILL}`]: () =>
      json({
        data: {
          fill: {
            fillId: FILL,
            ownerObject: "InspectionTemplate",
            ownerId: TEMPLATE,
            versionNo: 3,
            versionStatus: "ACTIVE",
            status: "DRAFT",
            version: 4,
            rowCount: 0,
            cellsBytes: 28,
            cellsHash: "a".repeat(64),
            corrections: 0,
            createdAt: "2026-10-23T08:00:00Z",
            updatedAt: "2026-10-23T09:00:00Z",
          },
          grid: sampleDoc(),
          cells: { formatVersion: 1, rows: [] },
          canEdit: true,
          correction: false,
          limits: { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000 },
        },
      }),
  } as Record<string, Handler>;
}

beforeEach(() => {
  setUrl("/apps/inspection/s-fill-ins");
});

describe("the list of fill-ins", () => {
  it("lists them with their status, version, rows and the person who made them", async () => {
    fakeApi(answers());

    render(<FillInsPage />);

    expect(await screen.findAllByText("Fire exit check")).toHaveLength(2);
    expect(screen.getByRole("heading", { name: /All fill-ins/ })).toBeInTheDocument();
    expect(screen.getByText("Pat Filler")).toBeInTheDocument();
    expect(within(screen.getByTestId("fill-ins-list")).getByText("Draft")).toBeInTheDocument();
    expect(within(screen.getByTestId("fill-ins-list")).getByText("Approved")).toBeInTheDocument();
    expect(screen.getByText("v3")).toBeInTheDocument();
    expect(screen.getByText("v2 (a newer version is in use)")).toBeInTheDocument();
    expect(screen.getAllByText("Not sent")).toHaveLength(2);
  });

  it("marks an archived fill-in in the list", async () => {
    fakeApi(answers([row(FILL, { archivedAt: "2026-10-23T10:00:00Z" }), row(OTHER)]));

    render(<FillInsPage />);
    await screen.findAllByText("Fire exit check");

    expect(within(screen.getByTestId("fill-ins-list")).getAllByText("Archived")).toHaveLength(1);
  });

  it("offers no New: a fill-in is started from its template", async () => {
    fakeApi(answers());

    render(<FillInsPage />);
    await screen.findAllByText("Fire exit check");

    expect(screen.queryByRole("button", { name: /New/ })).not.toBeInTheDocument();
  });

  it("asks the API for the template of the address, and has one search box instead of a filter pane", async () => {
    const asked: string[] = [];
    setUrl(`/apps/inspection/s-fill-ins?template=${TEMPLATE}`);
    fakeApi({
      ...answers(),
      "POST /api/v1/fill-ins/query": (request) =>
        request.text().then((body) => {
          asked.push(body);
          return json({ data: { rows: [row(FILL)] } });
        }),
    });
    render(<FillInsPage />);
    await screen.findByText("Fire exit check");
    expect(JSON.parse(asked[0] ?? "{}").templateId).toBe(TEMPLATE);

    expect(screen.queryByTestId("fill-in-filters")).toBeNull();
    expect(screen.getByRole("searchbox")).toBeInTheDocument();
  });

  it("continues from the cursor of the last fill-in seen and appends the next page", async () => {
    const cursors: (string | null)[] = [];
    fakeApi({
      ...answers(),
      "POST /api/v1/fill-ins/query": (request) =>
        request.text().then((body) => {
          const cursor = (JSON.parse(body) as { cursor?: string }).cursor ?? null;
          cursors.push(cursor);
          return json(
            cursor ? { data: { rows: [row(OTHER)] } } : { data: { rows: [row(FILL)], nextCursor: "after-fill" } },
          );
        }),
    });
    render(<FillInsPage />);
    await screen.findByText("Fire exit check");

    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    await waitFor(() => expect(screen.getAllByText("Fire exit check")).toHaveLength(2));
    expect(cursors).toEqual([null, "after-fill"]);
    expect(screen.queryByRole("button", { name: "Show more" })).not.toBeInTheDocument();
  });

  it("says plainly when there is nothing, and in the API's words when the list cannot be read", async () => {
    fakeApi({ ...answers([]) });
    const { unmount } = render(<FillInsPage />);
    expect(await screen.findByText(/There are no fill-ins you can see yet/)).toBeInTheDocument();
    unmount();

    fakeApi({
      ...answers(),
      "POST /api/v1/fill-ins/query": () => refusal("NOT_FOUND", "This does not exist.", 404),
    });
    render(<FillInsPage />);
    expect(await screen.findByText("This does not exist.")).toBeInTheDocument();
  });
});

describe("one fill-in", () => {
  it("opens in the same pane with its facts and its cells when a row is chosen", async () => {
    fakeApi(answers());
    render(<FillInsPage />);

    fireEvent.click((await screen.findAllByRole("button", { name: "Fire exit check" }))[0] as HTMLElement);

    expect(currentUrl()).toBe(`/apps/inspection/s-fill-ins?open=${FILL}`);
    expect(await screen.findByTestId("fill-in-detail")).toBeInTheDocument();
    expect(await screen.findByRole("grid", { name: "Fire exit check, version 3" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Fire exit check" })).toHaveAttribute(
      "href",
      `/apps/inspection/s-templates?open=${TEMPLATE}`,
    );
  });

  it("still shows the cells to a person who may not read the header", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    fakeApi({
      ...answers(),
      [`GET ${RECORDS}/${FILL}`]: () => refusal("NOT_FOUND", "This does not exist.", 404),
    });

    render(<FillInsPage />);

    expect(await screen.findByRole("grid", { name: /version 3/ })).toBeInTheDocument();
    expect(screen.queryByText("Details")).not.toBeInTheDocument();
  });

  it("warns that only what is saved is sent while there are unsaved changes", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    fakeApi(answers());
    render(<FillInsPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within((await screen.findAllByTestId("fill-row"))[0] as HTMLElement).getByRole("textbox", {
      name: /Amount/,
    });

    fireEvent.change(box, { target: { value: "5" } });
    fireEvent.blur(box);

    expect(await screen.findByTestId("unsaved-note")).toHaveTextContent("Submit saves them first");
  });

  it("sends a fill-in for good with Submit when no approval process applies, and reads it again as stored", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    let sent = 0;
    fakeApi({
      ...answers(),
      [`POST /api/v1/fill-ins/${FILL}/submit`]: () => {
        sent += 1;
        return json({ data: { outcome: "SUBMITTED" } });
      },
    });
    render(<FillInsPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Submit" }));

    expect(await screen.findByTestId("send-note")).toHaveTextContent("The fill-in was submitted");
    expect(sent).toBe(1);
    expect(screen.queryByRole("dialog", { name: "Submit for approval" })).not.toBeInTheDocument();
  });

  it("saves what was typed before it sends, and sends nothing when the save is refused", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    let sent = 0;
    const calls = fakeApi({
      ...answers(),
      [`PUT /api/v1/sheet-fills/InspectionFill/${FILL}/cells`]: () =>
        refusal("VALIDATION_ERROR", "A value is not valid.", 422),
      [`POST /api/v1/fill-ins/${FILL}/submit`]: () => {
        sent += 1;
        return json({ data: { outcome: "SUBMITTED" } });
      },
    });
    render(<FillInsPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within((await screen.findAllByTestId("fill-row"))[0] as HTMLElement).getByRole("textbox", {
      name: /Amount/,
    });
    fireEvent.change(box, { target: { value: "5" } });
    fireEvent.blur(box);

    fireEvent.click(await screen.findByRole("button", { name: "Submit" }));

    expect(await screen.findByTestId("save-refused")).toHaveTextContent("A value is not valid.");
    expect(calls.some((call) => call.key.includes("/cells"))).toBe(true);
    expect(sent).toBe(0);
  });

  it("opens the ordinary approval popup instead when the API says a review is needed", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    fakeApi({
      ...answers(),
      [`POST /api/v1/fill-ins/${FILL}/submit`]: () => json({ data: { outcome: "REVIEW_NEEDED" } }),
      [`GET ${RECORDS}/${FILL}/approval`]: () =>
        json({
          data: {
            ...NO_APPROVAL.data,
            available: true,
            processes: [{ apiName: "fill_review", label: "Fill-in review" }],
            actions: { ...NO_APPROVAL.data.actions, canSubmit: true },
          },
        }),
    });
    render(<FillInsPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Submit" }));

    expect(await screen.findByTestId("send-note")).toHaveTextContent("A review is set up for fill-ins");
    expect(await screen.findByRole("button", { name: "Submit and lock the record" })).toBeInTheDocument();
    // The page has one Submit: the approval's own button is not drawn beside it.
    expect(screen.queryByTestId("approval-submit")).not.toBeInTheDocument();
  });

  it("shows the API's words when the fill-in cannot be sent", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    fakeApi({
      ...answers(),
      [`POST /api/v1/fill-ins/${FILL}/submit`]: () =>
        refusal("CONFLICT", "This fill-in has no rows yet. Add at least one row before sending it.", 409),
    });
    render(<FillInsPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Submit" }));

    expect(await screen.findByTestId("send-note")).toHaveTextContent("This fill-in has no rows yet");
  });

  it("leaves with Cancel and sends nothing; unsaved changes are never dropped by one press", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    const calls = fakeApi(answers());
    render(<FillInsPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Form view" }));
    const box = within((await screen.findAllByTestId("fill-row"))[0] as HTMLElement).getByRole("textbox", {
      name: /Amount/,
    });
    fireEvent.change(box, { target: { value: "5" } });
    fireEvent.blur(box);

    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    expect(currentUrl()).toContain(`open=${FILL}`);
    fireEvent.click(screen.getByRole("button", { name: "Leave without saving" }));
    expect(currentUrl()).not.toContain("open=");
    expect(calls.some((call) => call.key.includes("/submit"))).toBe(false);
  });

  it("offers no Submit or Cancel for a fill-in that was already sent", async () => {
    setUrl(`/apps/inspection/s-fill-ins?open=${FILL}`);
    fakeApi({
      ...answers(),
      [`GET ${RECORDS}/${FILL}`]: () =>
        json({
          data: {
            id: FILL,
            objectApiName: "InspectionFill",
            ownerId: "0198f5c0-0000-7000-8000-000000000001",
            fields: { name: "Fire exit check", templateId: TEMPLATE, status: "SUBMITTED" },
            version: 2,
            createdAt: "2026-10-23T08:00:00Z",
            updatedAt: "2026-10-23T08:00:00Z",
          },
        }),
    });
    render(<FillInsPage />);

    await screen.findByRole("grid", { name: /version 3/ });
    expect(screen.queryByRole("button", { name: "Submit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
  });
});
