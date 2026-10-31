import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { ChangeRequestPage } from "./ChangeRequestPage";

// These screens draw a lot; a slow machine needs more than the default second to settle.
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20000 });

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const REQUEST = "0198f5c0-0000-7000-8000-0000000000a1";
const REVIEW = "0198f5c0-0000-7000-8000-0000000000b1";
const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";
const APPROVAL = "0198f5c0-0000-7000-8000-0000000000c1";

const NONE = {
  canApprove: false,
  canAssign: false,
  canCancel: false,
  canReapply: false,
  canReassign: false,
  canRecall: false,
  canReject: false,
  canSubmit: false,
};

function comment(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    kind: "TEMPLATE",
    label: "",
    inActiveVersion: true,
    text: "Please make the walkway wording clearer.",
    createdAt: "2026-10-22T08:00:00Z",
    authorName: "Reader A",
    mine: false,
    ...extra,
  };
}

function request(extra: Record<string, unknown> = {}) {
  return {
    id: REQUEST,
    title: "Wording of the walkway",
    status: "OPEN",
    versionNo: 1,
    requesterName: "Reader A",
    requesterDepartment: "Plant 1 · Production",
    mine: false,
    createdAt: "2026-10-22T08:00:00Z",
    placesTotal: 1,
    placesInActive: 1,
    canComment: false,
    canCreateCopy: false,
    canChangeCopy: false,
    comments: [
      comment("c1", { kind: "CELL", label: "Row 2, Column B", rowId: "r2", columnId: "cB", text: "Say 3 metres." }),
    ],
    templateId: TEMPLATE,
    templateName: "Fire exit template",
    templateNumber: "WI/PRD/03",
    reviewId: REVIEW,
    ...extra,
  };
}

function register(extra: Record<string, unknown> = {}) {
  return {
    version: 2,
    documentName: "Fire exit template",
    requestNo: "DCN-12",
    requestedOn: "2026-10-22",
    documentNo: "WI/PRD/03",
    revNo: "00",
    changeRequested: "Make the walkway wider",
    requestedBy: "Reader A",
    canComplete: false,
    grid: {},
    ...extra,
  };
}

function panel(actions: Record<string, boolean>, extra: Record<string, unknown> = {}) {
  return {
    data: {
      available: true,
      locked: true,
      processes: [],
      actions: { ...NONE, ...actions },
      current: {
        actions: { ...NONE, ...actions },
        history: [],
        historyVisible: true,
        steps: [
          {
            position: 1,
            name: "plant",
            label: "Plant QMS",
            completion: "ANY",
            status: "ACTIVE",
            assignments: [{ id: "t1", assignee: { userId: "u1", name: "Plant Person" }, via: "RULE", status: "OPEN" }],
          },
        ],
        summary: {
          id: APPROVAL,
          objectApiName: "InspectionChangeRequest",
          processApiName: "Review__c",
          processLabel: "Change request review",
          processVersion: 1,
          recordId: REVIEW,
          startedAt: "2026-10-22T08:00:00Z",
          status: "PENDING",
          stepCount: 3,
          submittedBy: { userId: "u0", name: "Reader A" },
          version: 4,
        },
        ...extra,
      },
    },
  };
}

function answers(view: Record<string, unknown>, actions: Record<string, boolean>): Record<string, Handler> {
  return {
    [`GET /api/v1/change-requests/${REQUEST}`]: () => json({ data: view }),
    [`GET /api/v1/templates/${TEMPLATE}/change-requests/${REQUEST}/register`]: () => json({ data: register() }),
    [`GET /api/v1/objects/InspectionChangeRequest/records/${REVIEW}/approval`]: () => json(panel(actions)),
  };
}

beforeEach(() => {
  setUrl(`/apps/inspection/s-change-requests?open=${REQUEST}`);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("one change request (FA6E, FA8)", () => {
  it("shows the template at the top, the revision read from the register, and who asked from which department", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByRole("heading", { name: "Fire exit template · WI/PRD/03" })).toBeInTheDocument();
    expect(await screen.findByTestId("request-version")).toHaveTextContent("Revision 00 read");
    expect(screen.getByTestId("request-by")).toHaveTextContent("Reader A");
    expect(screen.getByTestId("request-department")).toHaveTextContent("Plant 1 · Production");
    expect(screen.getByText("In progress")).toBeInTheDocument();
  });

  it("shows the register in the form view with the Excel view one click away, and nothing to upload", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByTestId("register-view-form")).toHaveTextContent("Make the walkway wider");
    expect(screen.getByTestId("register-tab-form")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("register-tab-excel")).toBeInTheDocument();
    expect(screen.getByTestId("register-download")).toBeInTheDocument();
    expect(screen.queryByTestId("register-upload")).toBeNull();
    expect(screen.queryByTestId("register-save")).toBeNull();
  });

  it("has no section for places on the sheet and no section for the revised sheet", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    await screen.findByTestId("request-by");
    expect(screen.queryByRole("heading", { name: "Where on the sheet" })).toBeNull();
    expect(screen.queryByRole("heading", { name: "Revised sheet" })).toBeNull();
    expect(screen.queryByRole("list", { name: "Places on the sheet" })).toBeNull();
    expect(screen.queryByText(/opens the sheet of the template as it stands now/)).toBeNull();
    expect(screen.queryByText("Current revision")).toBeNull();
    expect(screen.queryByText("no longer in this revision")).toBeNull();
  });
});

describe("the two buttons of a request (FA10)", () => {
  it("shows View Current Version, which opens the Sheet tab of the template at the revision in use", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    const view = await screen.findByTestId("view-current");
    expect(view).toHaveTextContent("View Current Version");
    // The version the person read is the one in use while the request is open: the sheet opens on it, not on the list.
    expect(view).toHaveAttribute("href", `/apps/inspection/s-templates?open=${TEMPLATE}&tab=sheet&version=1`);
    expect(screen.queryByTestId("create-copy")).toBeNull();
    expect(screen.queryByTestId("open-copy")).toBeNull();
  });

  it("puts the buttons in one row", async () => {
    fakeApi(answers(request({ canCreateCopy: true }), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    const row = await screen.findByTestId("request-sheet-buttons");
    expect(within(row).getByText("View Current Version")).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Create Revised Version" })).toBeInTheDocument();
  });

  it("makes the revised copy with the server's rules, then goes to the Sheet tab opened on that copy", async () => {
    const calls = fakeApi({
      ...answers(request({ canCreateCopy: true }), { canApprove: true, canReject: true }),
      [`POST /api/v1/templates/${TEMPLATE}/change-requests/${REQUEST}/revised-copy`]: () =>
        json({ data: request({ canCreateCopy: false, canChangeCopy: true, resultingVersion: 2, resultingVersionStatus: "DRAFT" }) }),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    fireEvent.click(await screen.findByTestId("create-copy"));

    await waitFor(() => expect(currentUrl()).toBe(`/apps/inspection/s-templates?open=${TEMPLATE}&tab=sheet&version=2`));
    expect(calls.some((one) => one.key.endsWith("/revised-copy"))).toBe(true);
  });

  it("says View Revised Version once the copy exists and the person may change it, and goes to the same place", async () => {
    fakeApi(
      answers(request({ canChangeCopy: true, resultingVersion: 2, resultingVersionStatus: "DRAFT" }), {
        canApprove: true,
        canReject: true,
      }),
    );

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    const open = await screen.findByTestId("open-copy");
    expect(open).toHaveTextContent("View Revised Version");
    expect(open).toHaveAttribute("href", `/apps/inspection/s-templates?open=${TEMPLATE}&tab=sheet&version=2`);
    expect(screen.queryByTestId("create-copy")).toBeNull();
    expect(screen.getByTestId("request-became")).toHaveTextContent("The revised sheet (the revised copy, being prepared)");
  });

  it("draws the second button for nobody who cannot make or change the copy", async () => {
    fakeApi(answers(request({ resultingVersion: 2, resultingVersionStatus: "DRAFT" }), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    await screen.findByTestId("view-current");
    expect(screen.queryByTestId("create-copy")).toBeNull();
    expect(screen.queryByTestId("open-copy")).toBeNull();
  });

  it("says nothing about the revision in use on a published request (the Details tab has it) and draws no second button", async () => {
    fakeApi(
      answers(
        request({ status: "DONE", resultingVersion: 2, resultingVersionStatus: "ACTIVE", resultingRevision: 1 }),
        {},
      ),
    );

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    const view = await screen.findByTestId("view-current");
    // Once published, the version in use is the one this request made.
    expect(view).toHaveAttribute("href", `/apps/inspection/s-templates?open=${TEMPLATE}&tab=sheet&version=2`);
    expect(screen.queryByTestId("request-became")).toBeNull();
    expect(screen.queryByTestId("revised-sheet-note")).toBeNull();
    expect(screen.queryByText(/The revised sheet was published/)).toBeNull();
    expect(screen.getByText("Published")).toBeInTheDocument();
    expect(screen.queryByTestId("open-copy")).toBeNull();
  });

  it("says that a declined request left the revision in use as it was", async () => {
    fakeApi(answers(request({ status: "DECLINED" }), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByTestId("revised-sheet-note")).toHaveTextContent("revision in use is unchanged");
  });

  it("shows the server's words when it refuses the copy, and stays on the page", async () => {
    fakeApi({
      ...answers(request({ canCreateCopy: true }), {}),
      [`POST /api/v1/templates/${TEMPLATE}/change-requests/${REQUEST}/revised-copy`]: () =>
        refusal("CONFLICT", "A revised copy has been made for this request already.", 409),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    fireEvent.click(await screen.findByTestId("create-copy"));

    expect(await screen.findByText("A revised copy has been made for this request already.")).toBeInTheDocument();
    expect(currentUrl()).toBe(`/apps/inspection/s-change-requests?open=${REQUEST}`);
  });
});

describe("the approval of a request (FA6E)", () => {
  it("offers Approve and Decline when the approval says the person has a task, and nothing when it does not", async () => {
    fakeApi(answers(request(), { canApprove: true, canReject: true }));
    const first = render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Decline" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
    first.unmount();

    fakeApi(answers(request(), {}));
    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    await screen.findByTestId("request-by");
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Decline" })).toBeNull();
  });

  it("asks for a reason before it declines, and sends the decision to the approval of the request", async () => {
    const calls = fakeApi({
      ...answers(request(), { canApprove: true, canReject: true }),
      [`POST /api/v1/approvals/instances/${APPROVAL}/reject`]: () => json({ data: panel({}).data.current }),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    fireEvent.click(await screen.findByRole("button", { name: "Decline" }));

    const dialog = await screen.findByRole("dialog");
    const submit = within(dialog).getByRole("button", { name: "Decline" });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Comment/), { target: { value: "Not needed." } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);

    await waitFor(() => expect(calls.some((one) => one.key === `POST /api/v1/approvals/instances/${APPROVAL}/reject`)).toBe(true));
    const sent = JSON.parse(calls.find((one) => one.key.endsWith("/reject"))?.body ?? "{}");
    expect(sent).toMatchObject({ comment: "Not needed.", version: 4 });
  });

  it("draws the approval flow beside the request", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByRole("navigation", { name: "Approval flow" })).toHaveTextContent("Plant QMS");
  });

  it("shows the reason it was declined, read from the approval history", async () => {
    fakeApi({
      ...answers(request({ status: "DECLINED" }), {}),
      [`GET /api/v1/objects/InspectionChangeRequest/records/${REVIEW}/approval`]: () =>
        json(
          panel(
            {},
            {
              history: [{ id: "h1", kind: "REJECTED", comment: "Already covered by the policy.", at: "2026-10-23T08:00:00Z" }],
            },
          ),
        ),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByTestId("request-declined-reason")).toHaveTextContent("Already covered by the policy.");
  });

  it("says in the words of the screen when the request is not available", async () => {
    fakeApi({
      [`GET /api/v1/change-requests/${REQUEST}`]: () => refusal("NOT_FOUND", "This change request does not exist.", 404),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByText(/This change request is not available/)).toBeInTheDocument();
  });
});
