import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
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
    mine: false,
    createdAt: "2026-10-22T08:00:00Z",
    placesTotal: 1,
    placesInActive: 1,
    canComment: false,
    canAccept: false,
    canDecline: false,
    canTurn: false,
    canReopen: false,
    comments: [comment()],
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
    expect(screen.getByText(/stand on a place in the sheet/)).toBeInTheDocument();
    unmount();

    render(<ChangeRequestsSection templateId={ID} state={ready(view([], { canReview: true }))} onChanged={vi.fn()} />);
    expect(screen.getByText("Nobody has asked for a change to this template.")).toBeInTheDocument();
  });
});

describe("a request and its comments", () => {
  it("writes who asked, what they read, where each comment points and that a place has gone", () => {
    draw(
      ready(
        view(
          [
            request({
              placesTotal: 2,
              placesInActive: 1,
              versionNo: 1,
              comments: [
                comment(),
                comment({ id: "c-2", kind: "COLUMN", rowId: undefined, columnId: "c1", label: "Torque", inActiveVersion: false, text: "Too narrow" }),
                comment({ id: "c-3", kind: "TEMPLATE", rowId: undefined, label: "", text: "In general", mine: true }),
              ],
            }),
          ],
          { activeVersion: 3 },
        ),
      ),
    );

    const card = screen.getByTestId("change-request");
    expect(within(card).getByRole("heading", { name: "The first row" })).toBeInTheDocument();
    expect(within(card).getByText("Open")).toBeInTheDocument();
    expect(card).toHaveTextContent("Reader A asked on");
    expect(card).toHaveTextContent("reading version 1; version 3 is in use now");
    expect(card).toHaveTextContent("2 places named, 1 no longer in the version in use");
    expect(within(card).getByText("Row “First”")).toBeInTheDocument();
    expect(within(card).getByText("Column “Torque”")).toBeInTheDocument();
    expect(within(card).getAllByText("no longer in this version")).toHaveLength(1);
    expect(within(card).getByText("The whole template")).toBeInTheDocument();
    expect(card).toHaveTextContent("You, ");
  });

  it("keeps the line breaks of a typed text and never reads it as markup", () => {
    draw(ready(view([request({ title: "<b>bold?</b>", comments: [comment({ text: "one\ntwo <i>three</i>" })] })])));

    expect(screen.getByRole("heading", { name: "<b>bold?</b>" })).toBeInTheDocument();
    const text = screen.getByText(/one/);
    expect(text).toHaveClass("comment-text");
    expect(text.textContent).toBe("one\ntwo <i>three</i>");
    expect(document.querySelector("i")).toBeNull();
  });

  it("says someone who has left when there is no name", () => {
    draw(ready(view([request({ requesterName: undefined, comments: [comment({ authorName: undefined })] })])));

    expect(screen.getAllByText(/Someone who has left/).length).toBeGreaterThan(0);
  });

  it("says which version a request became and the reason it was declined", () => {
    draw(
      ready(
        view([
          request({ id: "q-2", status: "DONE", resultingVersion: 3, resultingVersionStatus: "IN_REVIEW", title: "Done one" }),
          request({ id: "q-3", status: "DECLINED", decisionNote: "Not our decision", title: "Declined one" }),
        ]),
      ),
    );

    const cards = screen.getAllByTestId("change-request");
    expect(screen.getByText("Turned into Version 3 (in review).")).toBeInTheDocument();
    expect(screen.getByText("Not our decision")).toBeInTheDocument();
    expect(within(cards[1] as HTMLElement).getByText("Declined")).toBeInTheDocument();
    expect(within(cards[0] as HTMLElement).getByText("Done")).toBeInTheDocument();
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
});

describe("the person who asked", () => {
  it("adds a comment to their open request and offers nothing to answer or turn it", async () => {
    const calls = fakeApi({ [`POST ${BASE}/q-1/comments`]: () => json({ data: request() }) });
    const { onChanged } = draw(ready(view([request({ mine: true, canComment: true })])));

    expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Decline" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Turn the chosen requests/ })).toBeNull();
    expect(screen.queryByRole("checkbox")).toBeNull();
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

describe("the people who may change the template", () => {
  const REVIEW = { canReview: true };

  it("accepts a request and says so", async () => {
    const calls = fakeApi({ [`POST ${BASE}/q-1/answer`]: () => json({ data: request({ status: "ACCEPTED" }) }) });
    const { onChanged } = draw(ready(view([request({ canAccept: true, canDecline: true })], REVIEW)));

    fireEvent.click(screen.getByRole("button", { name: "Accept" }));

    await screen.findByText("The request was accepted.");
    expect(bodyOf(calls, `POST ${BASE}/q-1/answer`)).toEqual({ action: "ACCEPT" });
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it("declines only with a reason, which is sent with the answer", async () => {
    const calls = fakeApi({ [`POST ${BASE}/q-1/answer`]: () => json({ data: request({ status: "DECLINED" }) }) });
    draw(ready(view([request({ canAccept: true, canDecline: true })], REVIEW)));

    fireEvent.click(screen.getByRole("button", { name: "Decline" }));
    const decline = screen.getByRole("button", { name: "Decline it" });
    expect(decline).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Why is it declined/), { target: { value: "Out of scope" } });
    fireEvent.click(decline);

    await screen.findByText("The request was declined.");
    expect(bodyOf(calls, `POST ${BASE}/q-1/answer`)).toEqual({ action: "DECLINE", note: "Out of scope" });
    await waitFor(() => expect(screen.queryByLabelText(/Why is it declined/)).toBeNull());
  });

  it("reopens a request whose version was not used", async () => {
    const calls = fakeApi({ [`POST ${BASE}/q-1/answer`]: () => json({ data: request() }) });
    draw(
      ready(
        view(
          [request({ status: "DONE", resultingVersion: 2, resultingVersionStatus: "REJECTED", canReopen: true })],
          REVIEW,
        ),
      ),
    );

    expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Reopen" }));

    await screen.findByText("The request is open again.");
    expect(bodyOf(calls, `POST ${BASE}/q-1/answer`)).toEqual({ action: "REOPEN" });
  });

  it("turns the chosen requests into a version and says it is a copy of the one in use", async () => {
    const calls = fakeApi({
      [`POST ${BASE}/turn-into-version`]: () => json({ data: { versionNo: 4, created: true, requests: 2 } }),
    });
    const { onChanged } = draw(
      ready(
        view(
          [
            request({ id: "q-1", title: "First", canTurn: true }),
            request({ id: "q-2", title: "Second", canTurn: true }),
            request({ id: "q-3", title: "Third", status: "DECLINED" }),
          ],
          { ...REVIEW, activeVersion: 3 },
        ),
      ),
    );
    const turn = screen.getByRole("button", { name: "Turn the chosen requests into a new version" });
    expect(turn).toBeDisabled();
    expect(screen.getAllByRole("checkbox")).toHaveLength(2);

    fireEvent.click(screen.getByRole("checkbox", { name: "Include “First” in the new version" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Include “Second” in the new version" }));
    fireEvent.click(turn);

    expect(await screen.findByText(/Version 4 was started as a copy of the version in use, and 2 requests are linked/)).toBeInTheDocument();
    expect(bodyOf(calls, `POST ${BASE}/turn-into-version`)).toEqual({ requestIds: ["q-1", "q-2"] });
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Turn the chosen requests into a new version" })).toBeDisabled();
  });

  it("says when the requests joined a draft that already existed", async () => {
    fakeApi({ [`POST ${BASE}/turn-into-version`]: () => json({ data: { versionNo: 5, created: false, requests: 1 } }) });
    draw(ready(view([request({ canTurn: true })], REVIEW)));

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Turn the chosen requests into a new version" }));

    expect(await screen.findByText(/A draft already existed, so 1 request is linked to version 5 as it is/)).toBeInTheDocument();
  });

  it("shows the API's words when a request cannot be answered and changes nothing", async () => {
    fakeApi({
      [`POST ${BASE}/q-1/answer`]: () =>
        refusal("CONFLICT", "This request was answered by someone else just now. Reload the page.", 409),
    });
    const { onChanged } = draw(ready(view([request({ canAccept: true })], REVIEW)));

    fireEvent.click(screen.getByRole("button", { name: "Accept" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("answered by someone else just now");
    expect(onChanged).not.toHaveBeenCalled();
  });
});
