import { configure, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, type Call } from "@/test/fakeApi";
import type { ChangeCommentView, ChangeRequestView, ChangeRequestsState, ChangeRequestsView } from "./changeRequests";
import { ChangeRequestsSection } from "./ChangeRequestsSection";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";
const BASE = `/api/v1/templates/${ID}/change-requests`;

afterEach(() => {
  vi.unstubAllGlobals();
});

function comment(extra: Partial<ChangeCommentView> = {}): ChangeCommentView {
  return {
    id: "c-1",
    kind: "ROW",
    rowId: "r1",
    label: "First",
    inActiveVersion: true,
    text: "This row is wrong",
    createdAt: "2026-10-22T08:00:00Z",
    authorName: "Reader A",
    mine: false,
    ...extra,
  };
}

function request(extra: Partial<ChangeRequestView> = {}): ChangeRequestView {
  return {
    id: "q-1",
    title: "The first row",
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
    comments: [comment()],
    templateId: "t-1",
    ...extra,
  };
}

function view(requests: ChangeRequestView[], extra: Partial<ChangeRequestsView> = {}): ChangeRequestsView {
  return {
    requests,
    canReview: false,
    canRequest: true,
    activeVersion: 1,
    draftVersion: 0,
    openCount: requests.filter((one) => one.status === "OPEN").length,
    inProgress: requests.some((one) => one.status === "OPEN"),
    ...extra,
  };
}

function draw(state: ChangeRequestsState, onShow?: (place: unknown) => void) {
  const onChanged = vi.fn();
  render(<ChangeRequestsSection templateId={ID} state={state} onChanged={onChanged} onShow={onShow as never} />);
  return { onChanged };
}

const ready = (v: ChangeRequestsView): ChangeRequestsState => ({ status: "ready", view: v });
const bodyOf = (calls: Call[], key: string) => JSON.parse(calls.filter((c) => c.key === key).at(-1)?.body ?? "null") as unknown;

describe("the states of the section", () => {
  it("says it is loading, and shows the API's words when it cannot load", () => {
    const { unmount } = render(<ChangeRequestsSection templateId={ID} state={{ status: "loading" }} onChanged={vi.fn()} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading the change requests");
    unmount();

    render(
      <ChangeRequestsSection
        templateId={ID}
        state={{ status: "failed", failure: { code: "NOT_FOUND", message: "This template does not exist." } }}
        onChanged={vi.fn()}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("This template does not exist.");
  });

  it("tells a person who may only read how to ask, and tells the owner that nobody has", () => {
    const { unmount } = render(<ChangeRequestsSection templateId={ID} state={ready(view([]))} onChanged={vi.fn()} />);
    expect(screen.getByText(/To ask for one, press Ask for a change/)).toBeInTheDocument();
    unmount();

    render(<ChangeRequestsSection templateId={ID} state={ready(view([], { canReview: true }))} onChanged={vi.fn()} />);
    expect(screen.getByText("Nobody has asked for a change to this template.")).toBeInTheDocument();
  });
});

describe("a request and its comments", () => {
  it("writes who asked, where each comment points, that a place has gone, and the way to the request", () => {
    draw(
      ready(
        view([
          request({
            placesTotal: 2,
            placesInActive: 1,
            reviewId: "rv-1",
            comments: [
              comment(),
              comment({ id: "c-2", kind: "COLUMN", rowId: undefined, columnId: "c1", label: "Torque", inActiveVersion: false, text: "Too narrow" }),
              comment({ id: "c-3", kind: "TEMPLATE", rowId: undefined, label: "", text: "In general", mine: true }),
            ],
          }),
        ]),
      ),
    );

    const card = screen.getByTestId("change-request");
    expect(within(card).getByRole("heading", { name: "The first row" })).toBeInTheDocument();
    expect(within(card).getByText("In progress")).toBeInTheDocument();
    expect(card).toHaveTextContent("Reader A asked on");
    expect(card).toHaveTextContent("2 places named, 1 no longer in the revision in use");
    expect(card).toHaveTextContent("It is waiting for its approval.");
    expect(within(card).getByText("Row “First”")).toBeInTheDocument();
    expect(within(card).getByText("Column “Torque”")).toBeInTheDocument();
    expect(within(card).getAllByText("no longer in this revision")).toHaveLength(1);
    expect(within(card).getByText("The whole template")).toBeInTheDocument();
    expect(card).toHaveTextContent("You, ");
    expect(within(card).getByTestId("request-open")).toHaveAttribute(
      "href",
      `/apps/inspection/s-templates?open=${ID}&tab=change-requests&request=q-1`,
    );
  });

  it("keeps the line breaks of a typed text and never reads it as markup", () => {
    draw(ready(view([request({ title: "<b>bold?</b>", comments: [comment({ text: "one\ntwo <i>three</i>" })] })])));

    expect(screen.getByRole("heading", { name: "<b>bold?</b>" })).toBeInTheDocument();
    const text = screen.getByText(/three/);
    expect(text).toHaveClass("comment-text");
    expect(text.textContent).toBe("one\ntwo <i>three</i>");
    expect(document.querySelector("i")).toBeNull();
  });

  it("says someone who has left when there is no name", () => {
    draw(ready(view([request({ requesterName: undefined, comments: [comment({ authorName: undefined })] })])));

    expect(screen.getAllByText(/Someone who has left/).length).toBeGreaterThan(0);
  });

  it("shows the department the requester was in when they asked, a dash for none and every one for several", () => {
    draw(
      ready(
        view([
          request({ id: "q-1", title: "One", requesterDepartment: "Plant 1 · Production", status: "DECLINED" }),
          request({ id: "q-2", title: "Two", requesterDepartment: undefined, status: "DECLINED" }),
          request({
            id: "q-3",
            title: "Three",
            requesterDepartment: "Plant 1 · Production; Head office · Quality",
            status: "DECLINED",
          }),
        ]),
      ),
    );

    const lines = screen.getAllByTestId("request-department").map((line) => line.textContent);
    expect(lines).toEqual([
      "Department when asked: Plant 1 · Production",
      "Department when asked: —",
      "Department when asked: Plant 1 · Production; Head office · Quality",
    ]);
  });

  it("says which revision a published request made and that a declined one made none", () => {
    draw(
      ready(
        view([
          request({
            id: "q-2",
            status: "DONE",
            resultingVersion: 3,
            resultingVersionStatus: "ACTIVE",
            resultingRevision: 1,
            title: "Done one",
          }),
          request({ id: "q-3", status: "DECLINED", title: "Declined one" }),
        ]),
      ),
    );

    const cards = screen.getAllByTestId("change-request");
    expect(screen.getByText("Revision 01, in use.")).toBeInTheDocument();
    expect(within(cards[0] as HTMLElement).getByText("Published")).toBeInTheDocument();
    expect(within(cards[1] as HTMLElement).getByText("Declined")).toBeInTheDocument();
  });

  it("offers a comment to show in the sheet only when the sheet can show it", () => {
    const onShow = vi.fn();
    const { unmount } = render(
      <ChangeRequestsSection templateId={ID} state={ready(view([request()]))} onChanged={vi.fn()} onShow={onShow} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Show in the sheet/ }));
    expect(onShow).toHaveBeenCalledWith({ rowId: "r1", columnId: null });
    unmount();

    render(<ChangeRequestsSection templateId={ID} state={ready(view([request()]))} onChanged={vi.fn()} />);
    expect(screen.queryByRole("button", { name: /Show in the sheet/ })).toBeNull();
  });

  it("narrows the list by status", () => {
    draw(
      ready(
        view([
          request({ id: "q-1", title: "Open one" }),
          request({ id: "q-2", title: "Declined one", status: "DECLINED" }),
        ]),
      ),
    );

    fireEvent.change(screen.getByLabelText("Show"), { target: { value: "DECLINED" } });

    expect(screen.getByRole("heading", { name: "Declined one" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Open one" })).toBeNull();
    fireEvent.change(screen.getByLabelText("Show"), { target: { value: "DONE" } });
    expect(screen.getByText("No request matches.")).toBeInTheDocument();
  });

  it("has nothing to accept, turn or reopen: a request is decided by its approval", () => {
    draw(ready(view([request({ canComment: true })], { canReview: true })));

    for (const name of [/Accept/, /Decline/, /Turn the chosen/, /Reopen/, /Create revised copy/]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
    expect(screen.queryByRole("checkbox")).toBeNull();
  });
});

describe("the person who asked", () => {
  it("adds a comment to their open request", async () => {
    const calls = fakeApi({ [`POST ${BASE}/q-1/comments`]: () => json({ data: request() }) });
    const { onChanged } = draw(ready(view([request({ mine: true, canComment: true })])));

    const button = screen.getByRole("button", { name: "Add comment" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Add a comment"), { target: { value: "One more thing" } });
    fireEvent.click(button);

    await screen.findByText("Your comment was added.");
    expect(bodyOf(calls, `POST ${BASE}/q-1/comments`)).toEqual({ text: "One more thing" });
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText("Add a comment")).toHaveValue("");
  });

  it("offers no box for a comment on a closed request", () => {
    draw(ready(view([request({ mine: true, status: "DECLINED", canComment: false })])));

    expect(screen.queryByLabelText("Add a comment")).toBeNull();
  });
});
