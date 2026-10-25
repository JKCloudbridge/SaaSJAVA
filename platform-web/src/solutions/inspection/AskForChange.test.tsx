import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { describePlace, placesOfSelection, type AnnotateContext } from "@/lib/sheets/annotate";
import { sampleDoc } from "@/lib/sheets/fixtures";
import { displayRows } from "@/lib/sheets/geometry";
import { fakeApi, json, refusal } from "@/test/fakeApi";
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
};

function context(extra: Partial<AnnotateContext> = {}): AnnotateContext {
  const doc = sampleDoc();
  const rows = displayRows(doc);
  return {
    versionNo: 1,
    doc,
    places: placesOfSelection(doc, rows, { ar: 2, ac: 1, fr: 2, fc: 1 }),
    describe: (place) => describePlace(doc, rows, place),
    select: vi.fn(),
    activations: 0,
    ...extra,
  };
}

function draw(ctx: AnnotateContext = context(), view: ChangeRequestsView | undefined = VIEW) {
  const onSent = vi.fn();
  const onShown = vi.fn();
  const element = (next: AnnotateContext, shown: { rowId: string | null; columnId: string | null } | null = null) => (
    <AskForChange templateId={ID} context={next} view={view} show={shown} onShown={onShown} onSent={onSent} />
  );
  const utils = render(element(ctx));
  return {
    ...utils,
    onSent,
    onShown,
    rerender: (next: AnnotateContext, shown = null as Parameters<typeof element>[1]) => utils.rerender(element(next, shown)),
  };
}

const open = () => fireEvent.click(screen.getByRole("button", { name: "Ask for a change" }));
const box = () => screen.getByRole("textbox", { name: /Your comment/ });
const write = (text: string) => fireEvent.change(box(), { target: { value: text } });
const escape = () => fireEvent.keyDown(document, { key: "Escape" });

describe("the way in to asking for a change (FA6D)", () => {
  it("draws a button, not the request, until it is pressed", () => {
    draw();

    expect(screen.getByRole("button", { name: "Ask for a change" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByTestId("annotate-panel")).toBeNull();
  });

  it("opens a popup that names the place the person stood on, and puts the focus in the box", async () => {
    draw();

    open();

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("textbox", { name: "Your comment — Cell: column “Amount”, row “First”" })).toBeInTheDocument();
    await waitFor(() => expect(box()).toHaveFocus());
  });

  it("closes on Escape when nothing was written", () => {
    draw();
    open();

    escape();

    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("asks before it closes over something unsent, and keeps the words when told to", async () => {
    draw();
    open();
    write("The torque is wrong");

    escape();

    expect(screen.getByTestId("ask-for-change-confirm")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close and keep my words" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    open();
    expect(box()).toHaveValue("The torque is wrong");
  });

  it("goes back to writing when asked to keep writing", async () => {
    draw();
    open();
    write("Still writing");

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep writing" }));

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(screen.queryByTestId("ask-for-change-confirm")).toBeNull();
    expect(box()).toHaveValue("Still writing");
    await waitFor(() => expect(box()).toHaveFocus());
  });

  it("drops the words only when the person says so", () => {
    draw();
    open();
    write("Throw this away");

    escape();
    fireEvent.click(screen.getByRole("button", { name: "Discard my words" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    open();
    expect(box()).toHaveValue("");
  });

  it("does not close over a stray click beside the popup", () => {
    draw();
    open();
    write("Not lost");

    fireEvent.click(screen.getByTestId("ask-for-change-popup"));

    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(box()).toHaveValue("Not lost");
  });

  it("sends the request as before, closes, says it was sent and clears what was written", async () => {
    const calls = fakeApi({ [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201) });
    const { onSent } = draw();
    open();
    write("This cell is wrong");
    fireEvent.click(screen.getByRole("button", { name: "Add to my request" }));
    fireEvent.change(screen.getByRole("textbox", { name: "What is the request about?" }), { target: { value: "Fix it" } });

    fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

    expect(await screen.findByTestId("ask-for-change-sent")).toHaveTextContent("Your request was sent");
    expect(screen.queryByRole("dialog")).toBeNull();
    const body = JSON.parse(calls.find((call) => call.key === `POST ${PATH}`)?.body ?? "null") as {
      title: string;
      versionNo: number;
      comments: { rowId?: string; columnId?: string; text: string }[];
    };
    expect(body).toEqual({ title: "Fix it", versionNo: 1, comments: [{ rowId: "r1", columnId: "c2", text: "This cell is wrong" }] });
    expect(onSent).toHaveBeenCalledTimes(1);

    open();
    expect(box()).toHaveValue("");
    expect(screen.queryByTestId("drafted")).toBeNull();
  });

  it("keeps the popup open and shows the API's words for a refusal", async () => {
    fakeApi({ [`POST ${PATH}`]: () => refusal("CONFLICT", "You already have 10 open requests on this template.", 409) });
    const { onSent } = draw();
    open();
    write("A comment");
    fireEvent.click(screen.getByRole("button", { name: "Add to my request" }));
    fireEvent.change(screen.getByRole("textbox", { name: "What is the request about?" }), { target: { value: "Fix it" } });

    fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

    expect(await screen.findByText("You already have 10 open requests on this template.")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
    expect(screen.getByTestId("drafted")).toBeInTheDocument();
  });

  it("opens by itself, standing on the cell, when Enter is pressed in the grid", async () => {
    const first = context();
    const { rerender } = draw(first);

    rerender({ ...first, activations: 1 });

    expect(await screen.findByRole("dialog", { name: "Ask for a change" })).toBeInTheDocument();
    await waitFor(() => expect(box()).toHaveAccessibleName("Your comment — Cell: column “Amount”, row “First”"));
  });

  it("stands the grid on a place a comment in the list asked to be shown, then says it is done", async () => {
    const ctx = context();
    const { rerender, onShown } = draw(ctx);

    rerender(ctx, { rowId: "r2", columnId: null });

    await waitFor(() => expect(onShown).toHaveBeenCalledTimes(1));
    expect(ctx.select).toHaveBeenCalledWith({ rowId: "r2", columnId: null });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("says so, and draws no button, when the API says nobody can ask", () => {
    draw(context(), { ...VIEW, canRequest: false });

    expect(screen.getByTestId("annotate-unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ask for a change" })).toBeNull();
  });

  it("draws the button in the toolbar it is given", () => {
    const slot = document.createElement("div");
    document.body.appendChild(slot);
    const ctx = context();
    render(<AskForChange templateId={ID} context={ctx} view={VIEW} show={null} onShown={vi.fn()} onSent={vi.fn()} toolbarSlot={slot} />);

    expect(slot.querySelector("button")).toHaveTextContent("Ask for a change");
    slot.remove();
  });
});
