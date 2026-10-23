import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { describePlace, placesOfSelection, type AnnotateContext } from "@/lib/sheets/annotate";
import { sampleDoc, oneDimension } from "@/lib/sheets/fixtures";
import { displayRows } from "@/lib/sheets/geometry";
import type { GridDoc } from "@/lib/sheets/types";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { AnnotatePanel } from "./AnnotatePanel";
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

/** The context the workspace would hand the panel, with the active cell on a row and a column of the document. */
function context(row: number, column: number, doc: GridDoc = sampleDoc(), extra: Partial<AnnotateContext> = {}): AnnotateContext {
  const rows = displayRows(doc);
  return {
    versionNo: 1,
    doc,
    places: placesOfSelection(doc, rows, { ar: row, ac: column, fr: row, fc: column }),
    describe: (place) => describePlace(doc, rows, place),
    select: vi.fn(),
    activations: 0,
    ...extra,
  };
}

function draw(ctx = context(2, 1), view: ChangeRequestsView | undefined = VIEW) {
  const onSent = vi.fn();
  const onShown = vi.fn();
  const utils = render(<AnnotatePanel templateId={ID} context={ctx} view={view} show={null} onShown={onShown} onSent={onSent} />);
  return { onSent, onShown, ...utils };
}

const write = (text: string) => fireEvent.change(screen.getByRole("textbox", { name: /Your comment/ }), { target: { value: text } });
const add = () => fireEvent.click(screen.getByRole("button", { name: "Add to my request" }));
const title = (text: string) => fireEvent.change(screen.getByRole("textbox", { name: "What is the request about?" }), { target: { value: text } });
const send = () => fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

describe("asking for a change under the grid (FA5)", () => {
  it("names the place the comment is about and lets the person choose another", () => {
    draw();

    expect(screen.getByRole("textbox", { name: "Your comment — Cell: column “Amount”, row “First”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Comment on this row" }));
    expect(screen.getByRole("textbox", { name: "Your comment — Row “First”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Comment on this column" }));
    expect(screen.getByRole("textbox", { name: "Your comment — Column “Amount”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Comment on the whole template" }));
    expect(screen.getByRole("textbox", { name: "Your comment — The whole template" })).toBeInTheDocument();
  });

  it("offers only the column on a blank sample row, because a row the filler will add is not a place", () => {
    const doc = oneDimension();
    const blank = displayRows(doc).findIndex((row) => row.zone === "blank");

    draw(context(blank, 1, doc));

    expect(screen.getByRole("button", { name: "Comment on this cell" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Comment on this row" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Comment on this column" })).toBeEnabled();
    expect(screen.getByRole("textbox", { name: /Column “Amount”/ })).toBeInTheDocument();
  });

  it("collects comments, shows them, lets one go, and sends the rest with the version that was read", async () => {
    const calls = fakeApi({
      [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201),
    });
    const { onSent } = draw();
    write("This cell is wrong");
    add();
    fireEvent.click(screen.getByRole("button", { name: "Comment on this column" }));
    write("This column is too narrow");
    add();
    fireEvent.click(within(screen.getByTestId("drafted")).getAllByRole("button", { name: /Remove/ })[1] as HTMLElement);
    fireEvent.click(screen.getByRole("button", { name: "Comment on the whole template" }));
    write("In general");
    add();
    title("Fix the first row");

    send();

    await screen.findByText("Your request was sent to the people who look after this template.");
    const body = JSON.parse(calls.find((c) => c.key === `POST ${PATH}`)?.body ?? "null") as {
      title: string;
      versionNo: number;
      comments: { rowId?: string; columnId?: string; text: string }[];
    };
    expect(body.title).toBe("Fix the first row");
    expect(body.versionNo).toBe(1);
    expect(body.comments).toEqual([
      { rowId: "r1", columnId: "c2", text: "This cell is wrong" },
      { text: "In general" },
    ]);
    expect(onSent).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("drafted")).toBeNull();
    expect(screen.getByRole("textbox", { name: "What is the request about?" })).toHaveValue("");
  });

  it("asks for a comment and a title before it sends anything", () => {
    const calls = fakeApi();
    draw();

    add();
    expect(screen.getByRole("alert")).toHaveTextContent("Write the comment first.");
    write("A comment");
    add();
    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Say what the request is about");
    title("Something");
    fireEvent.click(within(screen.getByTestId("drafted")).getByRole("button", { name: /Remove/ }));
    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Add at least one comment");
    expect(calls.filter((c) => c.key === `POST ${PATH}`)).toHaveLength(0);
  });

  it("shows the API's words and the places that are wrong, and keeps the person's work", async () => {
    fakeApi({
      [`POST ${PATH}`]: () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is not valid.", fields: { "comments[0].place": ["That row or column is not in the version you read."] } } }, 400),
    });
    const { onSent } = draw();
    write("Where?");
    add();
    title("Lost");

    send();

    expect(await screen.findByText("The request is not valid.")).toBeInTheDocument();
    expect(screen.getByText("That row or column is not in the version you read.")).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
    expect(screen.getByTestId("drafted")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "What is the request about?" })).toHaveValue("Lost");
  });

  it("shows a refusal that has no field in the API's words", async () => {
    fakeApi({ [`POST ${PATH}`]: () => refusal("CONFLICT", "You already have 10 open requests on this template.", 409) });
    draw();
    write("One more");
    add();
    title("Too many");

    send();

    expect(await screen.findByText("You already have 10 open requests on this template.")).toBeInTheDocument();
  });

  it("stops at ten comments in one request and says how to go on", () => {
    draw();
    for (let i = 0; i < 10; i++) {
      write(`Comment ${i}`);
      add();
    }

    write("The eleventh");
    add();

    expect(screen.getByRole("alert")).toHaveTextContent("at most 10 comments");
    expect(within(screen.getByTestId("drafted")).getAllByRole("listitem")).toHaveLength(10);
  });

  it("says that nobody can ask for a change when the API says so", () => {
    draw(context(2, 1), { ...VIEW, canRequest: false });

    expect(screen.getByTestId("annotate-unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send the request" })).toBeNull();
  });

  it("moves to the box of the comment, standing on the cell, when Enter is pressed in the grid", async () => {
    const first = context(2, 1, sampleDoc(), { activations: 0 });
    const { rerender } = draw(first);
    fireEvent.click(screen.getByRole("button", { name: "Comment on the whole template" }));

    rerender(
      <AnnotatePanel templateId={ID} context={{ ...first, activations: 1 }} view={VIEW} show={null} onShown={vi.fn()} onSent={vi.fn()} />,
    );

    const box = screen.getByRole("textbox", { name: /Your comment/ });
    await waitFor(() => expect(box).toHaveFocus());
    await waitFor(() => expect(box).toHaveAccessibleName("Your comment — Cell: column “Amount”, row “First”"));
  });

  it("stands the grid on a place a comment in the list asked to be shown, then says it is done", async () => {
    const ctx = context(2, 1);
    const onShown = vi.fn();
    const { rerender } = render(
      <AnnotatePanel templateId={ID} context={ctx} view={VIEW} show={null} onShown={onShown} onSent={vi.fn()} />,
    );

    rerender(
      <AnnotatePanel templateId={ID} context={ctx} view={VIEW} show={{ rowId: "r2", columnId: null }} onShown={onShown} onSent={vi.fn()} />,
    );

    await waitFor(() => expect(onShown).toHaveBeenCalledTimes(1));
    expect(ctx.select).toHaveBeenCalledWith({ rowId: "r2", columnId: null });
  });
});
