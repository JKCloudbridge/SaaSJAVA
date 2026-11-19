import { configure, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import type { TemplateLifecycleView } from "./api";
import { PublishDialog } from "./PublishDialog";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";

const OPTIONS = {
  data: {
    locations: [
      {
        value: "PLANT1",
        label: "Plant 1",
        departments: [
          { value: "PRD", label: "Production", configured: true },
          { value: "QLT", label: "Quality", configured: false },
        ],
      },
      { value: "PLANT2", label: "Plant 2", departments: [{ value: "PRD", label: "Production", configured: true }] },
      { value: "HEAD", label: "Head office", departments: [{ value: "HR", label: "Human resources", configured: true }] },
    ],
  },
};

function view(extra: Partial<TemplateLifecycleView> = {}): TemplateLifecycleView {
  return {
    id: ID,
    retired: false,
    lifecycle: {
      state: "APPROVED",
      activeVersion: 0,
      approvedVersion: 2,
      inReviewVersion: 0,
      draftVersion: 0,
      retiredVersion: 0,
      highestVersion: 2,
    },
    audience: [],
    canPublish: true,
    canUnpublish: false,
    canRetire: true,
    numberPreviewComplete: false,
    openChangeRequests: 0,
    versionReviews: [],
    suggestedAudience: [],
    suggestionDropped: 0,
    viewOnly: false,
    allowDownload: false,
    canDownload: false,
    canSubmit: true,
    canSetOptions: true,
    footerDate: false,
    limitedToSuggested: false,
    noPlaceLeft: false,
    unpublishedPlaces: [],
    ...extra,
  };
}

const published = (location: string, locationLabel: string, department: string, departmentLabel: string, versionNo = 3) => ({
  location,
  department,
  locationLabel,
  departmentLabel,
  versionNo,
  publishedAt: "2026-10-22T09:00:00Z",
  groupAvailable: true,
});

function draw(lifecycle = view()) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(<PublishDialog templateId={ID} lifecycle={lifecycle} onClose={onClose} onDone={onDone} />);
  return { onDone, onClose };
}

/** The header button of a location's section. */
const header = async (name: string) => screen.findByRole("button", { name: new RegExp(name) });

/** Opens the section of a location and ticks the departments by their words, directly. */
async function choose(location: string, ...departments: string[]) {
  const button = await header(location);
  if (button.getAttribute("aria-expanded") !== "true") {
    fireEvent.click(button);
  }
  for (const department of departments) {
    fireEvent.click(screen.getByRole("checkbox", { name: new RegExp(`^${department}`) }));
  }
}

const sentBody = (calls: { key: string; body?: string }[]) =>
  JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;

describe("the publish dialog", () => {
  it("shows one section for each location with its status, and a column of tick boxes under an open one", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    expect(await header("Plant 1")).toHaveAttribute("aria-expanded", "false");
    expect(await header("Plant 2")).toHaveTextContent("None chosen");
    expect(await header("Head office")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add the ticked departments" })).toBeNull();
    expect(screen.queryByLabelText("Location")).toBeNull();

    fireEvent.click(await header("Plant 1"));
    const group = screen.getByRole("group", { name: "Departments of Plant 1" });
    expect(within(group).getAllByRole("checkbox").map((box) => box.parentElement?.textContent?.trim())).toEqual([
      "Production",
      "Quality (no group set up yet)",
    ]);
  });

  it("ticks directly across locations and publishes once, in the order of the organization's lists", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    const { onDone } = draw();

    await choose("Head office", "Human resources");
    await choose("Plant 1", "Production", "Quality");
    expect(screen.getByTestId("publish-summary")).toHaveTextContent("Publishing to 2 locations, 3 departments.");
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(sentBody(calls)).toEqual({
      versionNo: 2,
      viewOnly: false,
      allowDownload: false,
      audiences: [
        { location: "PLANT1", department: "PRD" },
        { location: "PLANT1", department: "QLT" },
        { location: "HEAD", department: "HR" },
      ],
    });
    expect(calls.filter((call) => call.key.endsWith("/publish"))).toHaveLength(1);
  });

  it("will not publish to nobody and says why beside the button", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    await header("Plant 1");
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByTestId("publish-why-disabled")).toHaveTextContent("Tick at least one department to publish.");
    await choose("Plant 1", "Production");
    expect(screen.getByRole("button", { name: "Publish" })).toBeEnabled();
    expect(screen.queryByTestId("publish-why-disabled")).toBeNull();
  });

  it("names the pair that cannot be published to, in the API's words, and keeps what was ticked", async () => {
    fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "audiences[1]": ["No group is set up for this department yet."] },
            },
          },
          422,
        ),
    });
    const { onDone } = draw();
    await choose("Plant 1", "Production", "Quality");

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    const refused = await screen.findByTestId("publish-refused");
    expect(refused).toHaveTextContent("Plant 1 · Quality: No group is set up for this department yet.");
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByRole("checkbox", { name: /^Quality/ })).toBeChecked();
  });

  it("starts with what is already published ticked and marked, and says what unticking will take back", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(
      view({
        lifecycle: {
          state: "ACTIVE",
          activeVersion: 3,
          approvedVersion: 0,
          inReviewVersion: 0,
          draftVersion: 0,
          retiredVersion: 0,
          highestVersion: 3,
        },
        audience: [published("PLANT1", "Plant 1", "PRD", "Production")],
      }),
    );

    expect(await screen.findByText(/Revision 02 stays in use/)).toBeInTheDocument();
    expect(await header("Plant 1")).toHaveTextContent("Published");
    expect(await header("Plant 1")).toHaveAttribute("aria-expanded", "true");
    const box = screen.getByRole("checkbox", { name: /^Production/ });
    expect(box).toBeChecked();
    expect(within(box.closest("label") as HTMLElement).getByText("Published")).toBeInTheDocument();
    fireEvent.click(box);
    expect(screen.getByTestId("publish-summary")).toHaveTextContent("Nothing is chosen yet.");
    expect(await header("Plant 1")).toHaveTextContent("Will be taken back");
    fireEvent.click(screen.getByRole("checkbox", { name: /^Quality/ }));
    expect(screen.getByTestId("publish-summary")).toHaveTextContent("Will be taken back: 1.");
  });

  it("says which version replaces which", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(
      view({
        lifecycle: {
          state: "ACTIVE",
          activeVersion: 3,
          approvedVersion: 4,
          inReviewVersion: 0,
          draftVersion: 0,
          retiredVersion: 0,
          highestVersion: 4,
        },
      }),
    );

    expect(await screen.findByText(/Revision 03 becomes the one in use and replaces revision 02/)).toBeInTheDocument();
  });

  it("says in the API's words when the choices cannot be loaded", async () => {
    fakeApi({
      "GET /api/v1/templates/audience-options": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    draw();

    expect(await screen.findByText("You are not allowed to perform this action.")).toBeInTheDocument();
  });

  it("closes without publishing when it is cancelled", async () => {
    const calls = fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    const { onClose } = draw();

    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(calls.some((call) => call.key.endsWith("/publish"))).toBe(false);
  });

  it("moves between the headers with the arrow keys", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    const first = await header("Plant 1");
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    expect(await header("Plant 2")).toHaveFocus();
    fireEvent.keyDown(await header("Plant 2"), { key: "End" });
    expect(await header("Head office")).toHaveFocus();
    fireEvent.keyDown(await header("Head office"), { key: "Home" });
    expect(first).toHaveFocus();
  });
});

describe("the sheet options of the publish dialog (FA7)", () => {
  it("shows View only and Allow download in one row, both unticked on a template that never had them set", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    const row = await screen.findByTestId("publish-sheet-options");
    const boxes = within(row).getAllByRole("checkbox");
    expect(boxes.map((box) => box.parentElement?.textContent?.trim())).toEqual(["View only", "Allow download"]);
    expect(boxes.every((box) => !(box as HTMLInputElement).checked)).toBe(true);
  });

  it("sends what was ticked together with the departments", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    const { onDone } = draw();

    await choose("Plant 1", "Production");
    fireEvent.click(screen.getByRole("checkbox", { name: "View only" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Allow download" }));
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(sentBody(calls)).toEqual({
      versionNo: 2,
      viewOnly: true,
      allowDownload: true,
      audiences: [{ location: "PLANT1", department: "PRD" }],
    });
  });

  it("starts from the options the template already has", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ viewOnly: true, allowDownload: false }));

    await screen.findByTestId("publish-sheet-options");
    expect(screen.getByRole("checkbox", { name: "View only" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Allow download" })).not.toBeChecked();
  });
});

describe("the publish dialog with locations chosen at approval (FA15)", () => {
  const SUGGESTED = [
    { location: "PLANT1", locationLabel: "Plant 1", department: "PRD", departmentLabel: "Production" },
    { location: "PLANT2", locationLabel: "Plant 2" },
  ];

  it("lists every suggested location first, marked, with its departments pre-ticked as hints and the gap said", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    const { onDone } = draw(view({ suggestedAudience: SUGGESTED }));

    expect(await screen.findByTestId("publish-suggested")).toBeInTheDocument();
    expect(await header("Plant 1")).toHaveTextContent("Chosen by Corporate QMS");
    expect(await header("Plant 1")).toHaveTextContent("1 of 2 departments");
    expect(await header("Plant 2")).toHaveTextContent("No department yet");
    expect(screen.getByText("Places Corporate QMS chose")).toBeInTheDocument();
    expect(screen.getByText("Other locations")).toBeInTheDocument();
    expect(screen.getByTestId("publish-gaps")).toHaveTextContent("Not published yet: Plant 2.");
    expect(screen.getByTestId("publish-summary")).toHaveTextContent(
      "Publishing to 1 location, 1 department. Not yet chosen: Plant 2.",
    );
    expect(screen.getByRole("checkbox", { name: /^Production/ })).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(sentBody(calls)).toMatchObject({ audiences: [{ location: "PLANT1", department: "PRD" }] });
  });

  it("keeps what is published and lets a second publish add a location without taking the first back", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    draw(
      view({
        suggestedAudience: SUGGESTED,
        audience: [published("PLANT1", "Plant 1", "PRD", "Production")],
        unpublishedPlaces: [{ location: "PLANT2", locationLabel: "Plant 2" }],
      }),
    );

    await choose("Plant 2", "Production");
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/publish"))).toBe(true));
    expect(sentBody(calls)).toMatchObject({
      audiences: [
        { location: "PLANT1", department: "PRD" },
        { location: "PLANT2", department: "PRD" },
      ],
    });
  });

  it("shows a limited publisher only the chosen locations, notes what is published elsewhere and never sends it", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    draw(
      view({
        limitedToSuggested: true,
        suggestedAudience: SUGGESTED,
        audience: [published("HEAD", "Head office", "HR", "Human resources")],
      }),
    );

    expect(await header("Plant 1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Head office/ })).toBeNull();
    expect(screen.queryByText("Other locations")).toBeNull();
    expect(screen.getByTestId("publish-elsewhere")).toHaveTextContent("Head office · Human resources");
    expect(screen.getByTestId("publish-suggested")).toHaveTextContent("You can publish to the locations chosen");
    expect(screen.getByRole("checkbox", { name: /^Production/ })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/publish"))).toBe(true));
    expect(sentBody(calls)).toMatchObject({ audiences: [{ location: "PLANT1", department: "PRD" }] });
  });

  it("names a refused location by its place, in the server's words", async () => {
    fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "audiences[0]": ["This location was not chosen for this document."] },
            },
          },
          422,
        ),
    });
    draw(view({ suggestedAudience: SUGGESTED }));
    await header("Plant 1");

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    expect(await screen.findByTestId("publish-refused")).toHaveTextContent(
      "Plant 1 · Production: This location was not chosen for this document.",
    );
  });

  it("offers a person who may publish anywhere every location, the chosen ones first", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ suggestedAudience: SUGGESTED, limitedToSuggested: false }));

    await header("Plant 1");
    const names = screen.getAllByRole("button").map((button) => button.textContent ?? "");
    const order = ["Plant 1", "Plant 2", "Head office"].map((name) => names.findIndex((text) => text.includes(name)));
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(order.every((at) => at >= 0)).toBe(true);
    expect(await header("Head office")).not.toHaveTextContent("Chosen by Corporate QMS");
  });

  it("cannot publish when no chosen place is offered any more", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ limitedToSuggested: true, noPlaceLeft: true, suggestionDropped: 2 }));

    expect(await screen.findByTestId("publish-no-place-left")).toHaveTextContent("No location chosen for this document");
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByTestId("publish-suggestion-dropped")).toHaveTextContent(
      "2 suggested places are no longer offered and were left out.",
    );
  });

  it("starts from the audience the template already has when nothing was suggested", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ audience: [published("HEAD", "Head office", "HR", "Human resources", 1)] }));

    expect(await header("Head office")).toHaveTextContent("Published");
    expect(screen.queryByTestId("publish-suggested")).not.toBeInTheDocument();
    expect(screen.queryByText("Other locations")).toBeNull();
  });
});

// An application that publishes to places alone (MC1, ADR-0257).
describe("the publish dialog of an application that publishes to places alone", () => {
  const PLACES = {
    data: {
      placesOnly: true,
      placeDepartment: "PLANTHEAD",
      locations: [
        { value: "PLANT1", label: "Plant 1", departments: [{ value: "PLANTHEAD", label: "Plant head", configured: true }] },
        { value: "PLANT2", label: "Plant 2", departments: [{ value: "PLANTHEAD", label: "Plant head", configured: false }] },
      ],
    },
  };

  it("lists the places, asks for no department and sends the one the server named", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(PLACES),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view({ audience: [] }) }),
    });
    const { onDone } = draw();

    fireEvent.click(await header("Plant 2"));
    expect(screen.getByRole("checkbox", { name: "Plant 2 (no group set up yet)" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Location")).toBeNull();
    await choose("Plant 1");
    fireEvent.click(screen.getByRole("checkbox", { name: "Plant 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(sentBody(calls)).toEqual({
      versionNo: 2,
      viewOnly: false,
      allowDownload: false,
      audiences: [{ location: "PLANT1", department: "PLANTHEAD" }],
    });
  });

  it("keeps Publish off until a place is ticked, and takes a place back off", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(PLACES) });
    draw();

    await choose("Plant 1");
    const publish = screen.getByRole("button", { name: "Publish" });
    expect(publish).toBeDisabled();
    const one = screen.getByRole("checkbox", { name: "Plant 1" });
    fireEvent.click(one);
    expect(publish).toBeEnabled();
    fireEvent.click(one);
    expect(publish).toBeDisabled();
  });
});
