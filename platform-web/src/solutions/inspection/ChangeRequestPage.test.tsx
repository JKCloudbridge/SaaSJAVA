import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { ChangeRequestPage } from "./ChangeRequestPage";

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
    versionNo: 3,
    requesterName: "Reader A",
    requesterDepartment: "Plant 1 · Production",
    mine: false,
    createdAt: "2026-10-22T08:00:00Z",
    placesTotal: 1,
    placesInActive: 1,
    canComment: false,
    canTurn: false,
    canReopen: false,
    comments: [
      comment("c1"),
      comment("c2", { kind: "CELL", label: "Row 2, Column B", rowId: "r2", columnId: "cB", text: "Say 3 metres." }),
    ],
    templateId: TEMPLATE,
    templateName: "Fire exit template",
    templateNumber: "WI/PRD/03",
    reviewId: REVIEW,
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
          stepCount: 1,
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
    [`GET /api/v1/objects/InspectionChangeRequest/records/${REVIEW}/approval`]: () => json(panel(actions)),
  };
}

beforeEach(() => {
  setUrl(`/apps/inspection/s-change-requests?open=${REQUEST}`);
});

describe("one change request (FA6E)", () => {
  it("shows the template and the version at the top left, who asked and from which department in one row", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByRole("heading", { name: "Fire exit template · WI/PRD/03" })).toBeInTheDocument();
    expect(screen.getByTestId("request-version")).toHaveTextContent("Version 3 read");
    expect(screen.getByTestId("request-by")).toHaveTextContent("Reader A");
    expect(screen.getByTestId("request-department")).toHaveTextContent("Plant 1 · Production");
  });

  it("shows the first comment, then the changes requested place by place", async () => {
    fakeApi(answers(request(), {}));

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByTestId("request-first-comment")).toHaveTextContent("Please make the walkway wording clearer.");
    const changes = screen.getByRole("list", { name: "Changes requested" });
    expect(within(changes).getByText(/Cell: Row 2, Column B/)).toBeInTheDocument();
    expect(within(changes).getByText("Say 3 metres.")).toBeInTheDocument();
  });

  it("offers Accept and Decline when the approval says the person has a task, and nothing when it does not", async () => {
    fakeApi(answers(request(), { canApprove: true, canReject: true }));
    const first = render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    expect(await screen.findByRole("button", { name: "Accept" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Decline" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    first.unmount();

    fakeApi(answers(request(), {}));
    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    await screen.findByTestId("request-by");
    expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
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
      [`GET /api/v1/change-requests/${REQUEST}`]: () => json({ data: request({ status: "DECLINED" }) }),
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

  it("turns an accepted request into a version only when the server says it can", async () => {
    const calls = fakeApi({
      ...answers(request({ status: "ACCEPTED", canTurn: true }), {}),
      [`POST /api/v1/templates/${TEMPLATE}/change-requests/turn-into-version`]: () =>
        json({ data: { versionNo: 4, created: true, requests: 1 } }),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);
    fireEvent.click(await screen.findByTestId("request-turn"));

    expect(await screen.findByText(/Version 4 was started as a copy of the version in use/)).toBeInTheDocument();
    expect(JSON.parse(calls.find((one) => one.key.endsWith("/turn-into-version"))?.body ?? "{}")).toEqual({
      requestIds: [REQUEST],
    });
  });

  it("says in the words of the screen when the request is not available", async () => {
    fakeApi({
      [`GET /api/v1/change-requests/${REQUEST}`]: () => refusal("NOT_FOUND", "This change request does not exist.", 404),
    });

    render(<ChangeRequestPage id={REQUEST} onBack={() => undefined} />);

    expect(await screen.findByText(/This change request is not available/)).toBeInTheDocument();
  });
});
