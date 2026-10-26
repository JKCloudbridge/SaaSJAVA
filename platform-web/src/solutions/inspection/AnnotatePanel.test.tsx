import { configure, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { describePlace, placesOfSelection, type AnnotateContext } from "@/lib/sheets/annotate";
import { sampleDoc, oneDimension } from "@/lib/sheets/fixtures";
import { displayRows } from "@/lib/sheets/geometry";
import type { GridDoc } from "@/lib/sheets/types";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
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
  inProgress: false,
};

/** What the server draws for the register while a person types: only the parts the platform writes matter here. */
const DRAWN: Record<string, Handler> = {
  [`POST ${PATH}/register/page`]: () =>
    json({
      data: {
        version: 0,
        documentName: "Fire exit template",
        documentNo: "WI/PRD/03",
        revNo: "00",
        requestedBy: "Reader A",
        canComplete: false,
        grid: {},
      },
    }),
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
  const utils = render(<AnnotatePanel templateId={ID} context={ctx} view={view} onSent={onSent} />);
  return { onSent, ...utils };
}

const note = (text: string) => fireEvent.change(screen.getByRole("textbox", { name: /Your note/ }), { target: { value: text } });
const add = () => fireEvent.click(screen.getByRole("button", { name: "Add the place to my request" }));
const requestNo = (text: string) => fireEvent.change(screen.getByTestId("register-request-no"), { target: { value: text } });
const changeAsked = (text: string) => fireEvent.change(screen.getByTestId("register-change"), { target: { value: text } });
const send = () => fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

describe("the request written in the popup (FA5, FA6D, FA8)", () => {
  it("shows the register form first, with the Excel view and the file buttons beside it", () => {
    fakeApi(DRAWN);
    draw();

    expect(screen.getByTestId("register-form")).toBeInTheDocument();
    expect(screen.getByTestId("register-tab-excel")).toBeInTheDocument();
    expect(screen.getByTestId("register-download")).toBeInTheDocument();
    expect(screen.getByTestId("register-upload")).toBeInTheDocument();
    expect(screen.getByLabelText("Choose the filled register")).toBeInTheDocument();
  });

  it("names the place the note is about and lets the person choose another", () => {
    fakeApi(DRAWN);
    draw();

    expect(screen.getByRole("textbox", { name: "Your note — Cell: column “Amount”, row “First”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "This row" }));
    expect(screen.getByRole("textbox", { name: "Your note — Row “First”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "This column" }));
    expect(screen.getByRole("textbox", { name: "Your note — Column “Amount”" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "The whole template" }));
    expect(screen.getByRole("textbox", { name: "Your note — The whole template" })).toBeInTheDocument();
  });

  it("offers only the column on a blank sample row, because a row the filler will add is not a place", () => {
    fakeApi(DRAWN);
    const doc = oneDimension();
    const blank = displayRows(doc).findIndex((row) => row.zone === "blank");

    draw(context(blank, 1, doc));

    expect(screen.getByRole("button", { name: "This cell" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "This row" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "This column" })).toBeEnabled();
    expect(screen.getByRole("textbox", { name: /Column “Amount”/ })).toBeInTheDocument();
  });

  it("sends the register boxes, the places and the revision that was read", async () => {
    const calls = fakeApi({ ...DRAWN, [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201) });
    const { onSent } = draw();
    requestNo("DCN-12");
    changeAsked("Fix the first row");
    fireEvent.change(screen.getByTestId("register-basis"), { target: { value: "An audit finding" } });
    note("This cell is wrong");
    add();
    fireEvent.click(screen.getByRole("button", { name: "This column" }));
    note("This column is too narrow");
    add();
    fireEvent.click(within(screen.getByTestId("drafted")).getAllByRole("button", { name: /Remove/ })[1] as HTMLElement);
    fireEvent.click(screen.getByRole("button", { name: "The whole template" }));
    note("In general");
    add();

    send();

    await screen.findByText("Your request was sent to the people who look after this template.");
    const body = JSON.parse(calls.find((c) => c.key === `POST ${PATH}`)?.body ?? "null") as {
      versionNo: number;
      register: { requestNo: string; changeRequested: string; basisForChange: string };
      comments: { rowId?: string; columnId?: string; text: string }[];
    };
    expect(body.versionNo).toBe(1);
    expect(body.register).toMatchObject({
      requestNo: "DCN-12",
      changeRequested: "Fix the first row",
      basisForChange: "An audit finding",
    });
    expect(body.comments).toEqual([
      { rowId: "r1", columnId: "c2", text: "This cell is wrong" },
      { text: "In general" },
    ]);
    expect(onSent).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("drafted")).toBeNull();
    expect(screen.getByTestId("register-request-no")).toHaveValue("");
  });

  it("may be sent with no place on the sheet at all", async () => {
    const calls = fakeApi({ ...DRAWN, [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201) });
    draw();
    requestNo("DCN-1");
    changeAsked("Please look");

    send();

    await screen.findByText("Your request was sent to the people who look after this template.");
    expect(JSON.parse(calls.find((c) => c.key === `POST ${PATH}`)?.body ?? "null")).toMatchObject({ comments: [] });
  });

  it("asks for the request number and for what is asked before it sends anything", () => {
    const calls = fakeApi(DRAWN);
    draw();

    add();
    expect(screen.getByRole("alert")).toHaveTextContent("Write the note first.");
    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Write the request number.");
    requestNo("DCN-1");
    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Write what change you ask for.");
    expect(calls.filter((c) => c.key === `POST ${PATH}`)).toHaveLength(0);
  });

  it("shows the API's words and the places that are wrong, and keeps the person's work", async () => {
    fakeApi({
      ...DRAWN,
      [`POST ${PATH}`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "comments[0].place": ["That row or column is not in the revision you read."] },
            },
          },
          400,
        ),
    });
    const { onSent } = draw();
    requestNo("DCN-1");
    changeAsked("Lost");
    note("Where?");
    add();

    send();

    expect(await screen.findByText("The request is not valid.")).toBeInTheDocument();
    expect(screen.getByText("That row or column is not in the revision you read.")).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
    expect(screen.getByTestId("drafted")).toBeInTheDocument();
    expect(screen.getByTestId("register-change")).toHaveValue("Lost");
    expect(screen.getByTestId("register-request-no")).toHaveValue("DCN-1");
  });

  it("shows a refusal that has no field in the API's words, such as a change already in progress", async () => {
    fakeApi({
      ...DRAWN,
      [`POST ${PATH}`]: () => refusal("CONFLICT", "A change is already in progress on this template.", 409),
    });
    draw();
    requestNo("DCN-1");
    changeAsked("One more");

    send();

    expect(await screen.findByText("A change is already in progress on this template.")).toBeInTheDocument();
  });

  it("stops at ten places in one request and says how to go on", () => {
    fakeApi(DRAWN);
    draw();
    for (let i = 0; i < 10; i++) {
      note(`Note ${i}`);
      add();
    }

    note("The eleventh");
    add();

    expect(screen.getByRole("alert")).toHaveTextContent("at most 10 places");
    expect(within(screen.getByTestId("drafted")).getAllByRole("listitem")).toHaveLength(10);
  });

  it("says that nobody can ask when the API says so, and that a change is in progress when it is", () => {
    fakeApi(DRAWN);
    const first = draw(context(2, 1), { ...VIEW, canRequest: false });
    expect(screen.getByTestId("annotate-unavailable")).toHaveTextContent("Nobody can ask for a change");
    expect(screen.queryByRole("button", { name: "Send the request" })).toBeNull();
    first.unmount();

    draw(context(2, 1), { ...VIEW, canRequest: false, inProgress: true });
    expect(screen.getByTestId("annotate-unavailable")).toHaveTextContent("A change is already in progress");
  });
});
