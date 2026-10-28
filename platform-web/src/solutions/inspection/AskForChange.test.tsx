import { configure, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { AskForChange } from "./AskForChange";
import type { ChangeRequestsView } from "./changeRequests";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";
const PATH = `/api/v1/templates/${ID}/change-requests`;

afterEach(() => {
  vi.unstubAllGlobals();
});

const VIEW: ChangeRequestsView = {
  requests: [],
  canReview: false,
  canRequest: true,
  activeVersion: 1,
  draftVersion: 0,
  openCount: 0,
  inProgress: false,
};

/** What the server draws for the register while a person types. */
const DRAWN: Record<string, Handler> = {
  [`POST ${PATH}/register/page`]: () =>
    json({ data: { version: 0, documentName: "Fire exit template", revNo: "00", canComplete: false, grid: {} } }),
};

function draw(view: ChangeRequestsView | undefined = VIEW) {
  const onSent = vi.fn();
  const utils = render(<AskForChange templateId={ID} versionNo={1} view={view} onSent={onSent} />);
  return { ...utils, onSent };
}

const open = () => fireEvent.click(screen.getByRole("button", { name: "Ask for a change" }));
const asked = (no: string, change: string) => {
  fireEvent.change(screen.getByTestId("register-request-no"), { target: { value: no } });
  fireEvent.change(screen.getByTestId("register-change"), { target: { value: change } });
};
const escape = () => fireEvent.keyDown(document, { key: "Escape" });

describe("the way in to asking for a change (FA6D, FA10)", () => {
  it("draws a plain button, not the request, until it is pressed", () => {
    draw();

    expect(screen.getByRole("button", { name: "Ask for a change" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByTestId("request-panel")).toBeNull();
  });

  it("opens a popup with the register form and no part for a place on the sheet", () => {
    fakeApi(DRAWN);
    draw();

    open();

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toHaveAttribute("aria-modal", "true");
    expect(screen.getByTestId("register-form")).toBeInTheDocument();
    expect(screen.queryByText("Where on the sheet (optional)")).toBeNull();
    expect(screen.queryByRole("button", { name: "Add the place to my request" })).toBeNull();
    expect(screen.queryByRole("button", { name: "This cell" })).toBeNull();
    expect(screen.queryByRole("textbox", { name: /Your note/ })).toBeNull();
    expect(screen.queryByTestId("register-view")).toBeNull();
  });

  it("closes on Escape when nothing was written", () => {
    fakeApi(DRAWN);
    draw();
    open();

    escape();

    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("asks before it closes over something unsent, and keeps the words when told to", () => {
    fakeApi(DRAWN);
    draw();
    open();
    asked("DCN-9", "A wider walkway");

    escape();

    expect(screen.getByTestId("ask-for-change-confirm")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close and keep my words" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    open();
    expect(screen.getByTestId("register-request-no")).toHaveValue("DCN-9");
    expect(screen.getByTestId("register-change")).toHaveValue("A wider walkway");
  });

  it("goes back to writing when asked to keep writing", () => {
    fakeApi(DRAWN);
    draw();
    open();
    asked("DCN-9", "Still writing");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep writing" }));

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(screen.queryByTestId("ask-for-change-confirm")).toBeNull();
    expect(screen.getByTestId("register-change")).toHaveValue("Still writing");
  });

  it("drops the words only when the person says so", () => {
    fakeApi(DRAWN);
    draw();
    open();
    asked("DCN-9", "Throw this away");

    escape();
    fireEvent.click(screen.getByRole("button", { name: "Discard my words" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    open();
    expect(screen.getByTestId("register-change")).toHaveValue("");
  });

  it("does not close over a stray click beside the popup", () => {
    fakeApi(DRAWN);
    draw();
    open();
    asked("DCN-9", "Not lost");

    fireEvent.click(screen.getByTestId("ask-for-change-popup"));

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(screen.getByTestId("register-change")).toHaveValue("Not lost");
  });

  it("sends the register and the revision in use, closes, says it was sent and clears what was written", async () => {
    const calls = fakeApi({ ...DRAWN, [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201) });
    const { onSent } = draw();
    open();
    asked("DCN-1", "Fix it");

    fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

    expect(await screen.findByTestId("ask-for-change-sent")).toHaveTextContent("Your request was sent");
    expect(screen.queryByRole("dialog")).toBeNull();
    const body = JSON.parse(calls.find((call) => call.key === `POST ${PATH}`)?.body ?? "null") as {
      register: { requestNo: string; changeRequested: string };
      versionNo: number;
      comments: unknown[];
    };
    expect(body.register).toMatchObject({ requestNo: "DCN-1", changeRequested: "Fix it" });
    expect(body.versionNo).toBe(1);
    expect(body.comments).toEqual([]);
    expect(onSent).toHaveBeenCalledTimes(1);

    open();
    expect(screen.getByTestId("register-request-no")).toHaveValue("");
  });

  it("keeps the popup open and shows the API's words for a refusal", async () => {
    fakeApi({
      ...DRAWN,
      [`POST ${PATH}`]: () => refusal("CONFLICT", "A change is already in progress on this template.", 409),
    });
    const { onSent } = draw();
    open();
    asked("DCN-1", "Fix it");

    fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

    expect(await screen.findByText("A change is already in progress on this template.")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
  });

  it("says so, and draws no button, when the API says nobody can ask", () => {
    draw({ ...VIEW, canRequest: false });

    expect(screen.getByTestId("request-unavailable")).toHaveTextContent("Nobody can ask for a change");
    expect(screen.queryByRole("button", { name: "Ask for a change" })).toBeNull();
  });

  it("says a change is in progress when that is why nobody can ask", () => {
    draw({ ...VIEW, canRequest: false, inProgress: true });

    expect(screen.getByTestId("request-unavailable")).toHaveTextContent("A change is already in progress");
  });

  it("draws the button in the toolbar it is given", () => {
    const slot = document.createElement("div");
    document.body.appendChild(slot);
    render(<AskForChange templateId={ID} versionNo={1} view={VIEW} onSent={vi.fn()} toolbarSlot={slot} />);

    expect(slot.querySelector("button")).toHaveTextContent("Ask for a change");
    slot.remove();
  });
});
