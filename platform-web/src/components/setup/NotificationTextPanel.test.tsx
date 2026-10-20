import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { NotificationTextPanel } from "./NotificationTextPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  act(() => setUrl("/setup/notification-text"));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const SUMMARY = "Something happens that a member should know.";
const LIST = "GET /api/v1/metadata/notification-templates";
const CHECK = "POST /api/v1/metadata/notification-templates/check";
const CREATE = "POST /api/v1/metadata/notification-templates";
const UPDATE = "PUT /api/v1/metadata/notification-templates/SOME_KIND";
const RESET = "DELETE /api/v1/metadata/notification-templates/SOME_KIND";

function template(extra: Record<string, unknown> = {}) {
  return {
    kind: "SOME_KIND",
    category: "SOME_CATEGORY",
    summary: "Something happens that a member should know.",
    custom: false,
    title: "The platform title",
    body: "The platform body about {thing}.",
    defaultTitle: "The platform title",
    defaultBody: "The platform body about {thing}.",
    placeholders: [
      { name: "thing", help: "What it is about." },
      { name: "place", help: "Where." },
    ],
    ...extra,
  };
}

function view(templates = [template()]) {
  return { data: { templates, titleMax: 50, bodyMax: 300 } };
}

/** Opens the kind from the list, by its name in the first column, and returns its page. */
async function openKind() {
  fireEvent.click(await screen.findByRole("button", { name: SUMMARY }));
  return screen.findByTestId("template-SOME_KIND");
}

describe("the text of notices", () => {
  it("lists every kind with its category and whether the platform's text or the organization's is in use", async () => {
    fakeApi({ [LIST]: () => json(view([template(), template({ kind: "OTHER", summary: "Another thing.", custom: true })])) });

    render(<NotificationTextPanel />);

    const rows = await screen.findAllByTestId("template-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Platform text");
    expect(rows[0]).toHaveTextContent("SOME_CATEGORY");
    expect(rows[1]).toHaveTextContent("Your text");
    fireEvent.change(screen.getByRole("searchbox", { name: "Search notification text" }), { target: { value: "another" } });
    expect(screen.getAllByTestId("template-row")).toHaveLength(1);
  });

  it("draws every kind the API lists with the text in use, the placeholders and the limits", async () => {
    fakeApi({ [LIST]: () => json(view()) });

    render(<NotificationTextPanel />);

    const card = await openKind();
    expect(card).toHaveTextContent("Something happens that a member should know.");
    expect(card).toHaveTextContent("Platform text");
    expect(within(card).getByLabelText("Title")).toHaveValue("The platform title");
    expect(within(card).getByLabelText("Title")).toHaveAttribute("maxlength", "50");
    expect(within(card).getByLabelText("Text")).toHaveAttribute("maxlength", "300");
    expect(within(card).getByRole("button", { name: "{thing}" })).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "{place}" })).toBeInTheDocument();
  });

  it("adds a placeholder at the end of the text when it is clicked", async () => {
    fakeApi({ [LIST]: () => json(view()) });

    render(<NotificationTextPanel />);
    const card = await openKind();
    fireEvent.click(within(card).getByRole("button", { name: "{place}" }));

    expect(within(card).getByLabelText("Text")).toHaveValue("The platform body about {thing}.{place}");
  });

  it("checks the text with the server and shows the problems with their places, or the preview", async () => {
    let answer: "problems" | "preview" = "problems";
    fakeApi({
      [LIST]: () => json(view()),
      [CHECK]: () =>
        answer === "problems"
          ? json({ data: { problems: { body: ["{x} is not available here."] } } })
          : json({ data: { problems: {}, previewTitle: "A sample title", previewBody: "A sample body." } }),
    });

    render(<NotificationTextPanel />);
    const card = await openKind();
    fireEvent.click(within(card).getByRole("button", { name: "Check" }));
    expect(await screen.findByTestId("problems-SOME_KIND")).toHaveTextContent("body: {x} is not available here.");

    answer = "preview";
    fireEvent.change(within(card).getByLabelText("Text"), { target: { value: "Fixed {thing}" } });
    fireEvent.click(within(card).getByRole("button", { name: "Check" }));
    expect(await screen.findByTestId("preview-SOME_KIND")).toHaveTextContent("A sample title");
    expect(screen.queryByTestId("problems-SOME_KIND")).not.toBeInTheDocument();
  });

  it("makes a text for a kind that has none, and changes it with the version it read", async () => {
    let created = "";
    let changed = "";
    let current = template();
    fakeApi({
      [LIST]: () => json(view([current])),
      [CREATE]: async (request) => {
        created = await request.text();
        current = template({ custom: true, title: "Mine", body: "Mine {thing}", version: 0 });
        return json({ data: current }, 201);
      },
      [UPDATE]: async (request) => {
        changed = await request.text();
        return json({ data: current });
      },
    });

    render(<NotificationTextPanel />);
    let card = await openKind();
    fireEvent.change(within(card).getByLabelText("Title"), { target: { value: "Mine" } });
    fireEvent.change(within(card).getByLabelText("Text"), { target: { value: "Mine {thing}" } });
    fireEvent.click(within(card).getByRole("button", { name: "Save" }));
    expect(await screen.findByTestId("notification-text-notice")).toHaveTextContent("The text was saved.");
    expect(JSON.parse(created)).toEqual({ kind: "SOME_KIND", title: "Mine", body: "Mine {thing}" });

    card = await screen.findByTestId("template-SOME_KIND");
    await waitFor(() => expect(card).toHaveTextContent("Your text"));
    fireEvent.change(within(card).getByLabelText("Title"), { target: { value: "Mine again" } });
    fireEvent.click(within(card).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(changed).not.toBe(""));
    expect(JSON.parse(changed)).toEqual({ title: "Mine again", body: "Mine {thing}", version: 0 });
  });

  it("goes back to the platform's text", async () => {
    const calls = fakeApi({
      [LIST]: () => json(view([template({ custom: true, title: "Mine", body: "Mine", version: 2 })])),
      [RESET]: () => new Response(null, { status: 204 }),
    });

    render(<NotificationTextPanel />);
    const card = await openKind();
    fireEvent.click(within(card).getByRole("button", { name: /Go back to the platform/ }));

    await waitFor(() => expect(calls.some((one) => one.key === RESET)).toBe(true));
  });

  it("drafts the change in the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      [LIST]: () => json(view()),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });

    render(<NotificationTextPanel />);
    const card = await openKind();
    fireEvent.click(within(card).getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("notification-text-notice")).toHaveTextContent("change set");
    expect(calls.some((one) => one.key === CREATE)).toBe(false);
    const draft = calls.find((one) => one.key === "POST /api/v1/metadata/change-sets/set-1/changes");
    expect(JSON.parse(draft?.body ?? "{}").kind).toBe("CREATE_NOTIFICATION_TEMPLATE");
  });

  it("shows the API's words when it refuses", async () => {
    fakeApi({
      [LIST]: () => json(view()),
      [CREATE]: () => refusal("VALIDATION_ERROR", "The body is at most 300 characters.", 400),
    });

    render(<NotificationTextPanel />);
    const card = await openKind();
    fireEvent.click(within(card).getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("notification-text-problem")).toHaveTextContent("at most 300 characters");
  });
});
