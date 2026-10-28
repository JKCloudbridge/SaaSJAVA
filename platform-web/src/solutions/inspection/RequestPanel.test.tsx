import { configure, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import type { ChangeRequestsView } from "./changeRequests";
import { RequestPanel } from "./RequestPanel";

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

function draw(view: ChangeRequestsView | undefined = VIEW) {
  const onSent = vi.fn();
  const utils = render(<RequestPanel templateId={ID} versionNo={1} view={view} onSent={onSent} />);
  return { onSent, ...utils };
}

const requestNo = (text: string) => fireEvent.change(screen.getByTestId("register-request-no"), { target: { value: text } });
const changeAsked = (text: string) => fireEvent.change(screen.getByTestId("register-change"), { target: { value: text } });
const send = () => fireEvent.click(screen.getByRole("button", { name: "Send the request" }));

describe("the request written in the popup (FA5, FA6D, FA8, FA10)", () => {
  it("shows the register form first, with the Excel view and the file buttons beside it, and nothing about places", () => {
    fakeApi(DRAWN);
    draw();

    expect(screen.getByTestId("register-form")).toBeInTheDocument();
    expect(screen.getByTestId("register-tab-excel")).toBeInTheDocument();
    expect(screen.getByTestId("register-download")).toBeInTheDocument();
    expect(screen.getByTestId("register-upload")).toBeInTheDocument();
    expect(screen.getByLabelText("Choose the filled register")).toBeInTheDocument();
    expect(screen.queryByText(/Where on the sheet/)).toBeNull();
    expect(screen.queryByTestId("drafted")).toBeNull();
  });

  it("sends the register boxes and the revision that was read, with no place", async () => {
    const calls = fakeApi({ ...DRAWN, [`POST ${PATH}`]: () => json({ data: { id: "x" } }, 201) });
    const { onSent } = draw();
    requestNo("DCN-12");
    changeAsked("Fix the first row");
    fireEvent.change(screen.getByTestId("register-basis"), { target: { value: "An audit finding" } });

    send();

    await screen.findByText("Your request was sent to the people who look after this template.");
    const body = JSON.parse(calls.find((c) => c.key === `POST ${PATH}`)?.body ?? "null") as {
      versionNo: number;
      register: { requestNo: string; changeRequested: string; basisForChange: string };
      comments: unknown[];
    };
    expect(body.versionNo).toBe(1);
    expect(body.register).toMatchObject({
      requestNo: "DCN-12",
      changeRequested: "Fix the first row",
      basisForChange: "An audit finding",
    });
    expect(body.comments).toEqual([]);
    expect(onSent).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("register-request-no")).toHaveValue("");
  });

  it("asks for the request number and for what is asked before it sends anything", () => {
    const calls = fakeApi(DRAWN);
    draw();

    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Write the request number.");
    requestNo("DCN-1");
    send();
    expect(screen.getByRole("alert")).toHaveTextContent("Write what change you ask for.");
    expect(calls.filter((c) => c.key === `POST ${PATH}`)).toHaveLength(0);
  });

  it("shows the API's words and the boxes that are wrong, and keeps the person's work", async () => {
    fakeApi({
      ...DRAWN,
      [`POST ${PATH}`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "register.changeRequested": ["Say what you want changed."] },
            },
          },
          400,
        ),
    });
    const { onSent } = draw();
    requestNo("DCN-1");
    changeAsked("Lost");

    send();

    expect(await screen.findByText("The request is not valid.")).toBeInTheDocument();
    expect(screen.getByText("Say what you want changed.")).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
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

  it("says that nobody can ask when the API says so, and that a change is in progress when it is", () => {
    fakeApi(DRAWN);
    const first = draw({ ...VIEW, canRequest: false });
    expect(screen.getByTestId("request-unavailable")).toHaveTextContent("Nobody can ask for a change");
    expect(screen.queryByRole("button", { name: "Send the request" })).toBeNull();
    first.unmount();

    draw({ ...VIEW, canRequest: false, inProgress: true });
    expect(screen.getByTestId("request-unavailable")).toHaveTextContent("A change is already in progress");
  });
});
