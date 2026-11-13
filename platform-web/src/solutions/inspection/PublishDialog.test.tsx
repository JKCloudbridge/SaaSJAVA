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
    ...extra,
  };
}

/** The tick boxes of the departments of the chosen location, not the two sheet options. */
const departmentBoxes = () => within(screen.getByRole("group", { name: "Departments" })).queryAllByRole("checkbox");

function draw(lifecycle = view()) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(<PublishDialog templateId={ID} lifecycle={lifecycle} onClose={onClose} onDone={onDone} />);
  return { onDone, onClose };
}

/** Chooses the location, ticks the departments by their words, and adds them to the list. */
async function choose(location: string, ...departments: string[]) {
  fireEvent.change(await screen.findByLabelText("Location"), { target: { value: location } });
  for (const department of departments) {
    fireEvent.click(screen.getByRole("checkbox", { name: new RegExp(`^${department}`) }));
  }
  fireEvent.click(screen.getByRole("button", { name: "Add the ticked departments" }));
}

describe("the publish dialog", () => {
  it("asks for the location first and offers a tick box for each department of it", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    await screen.findByLabelText("Location");
    expect(departmentBoxes()).toHaveLength(0);
    expect(screen.getByText("Choose a location first.")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "PLANT1" } });
    expect(departmentBoxes().map((box) => box.parentElement?.textContent?.trim())).toEqual([
      "Production",
      "Quality (no group set up yet)",
    ]);

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "HEAD" } });
    expect(departmentBoxes().map((box) => box.parentElement?.textContent?.trim())).toEqual([
      "Human resources",
    ]);
  });

  it("adds every ticked department at once, then another location, with one Publish at the end", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view({ audience: [] }) }),
    });
    const { onDone } = draw();

    await choose("PLANT1", "Production", "Quality");
    expect(screen.getByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByText("Plant 1 · Quality")).toBeInTheDocument();
    await choose("HEAD", "Human resources");
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const sent = JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;
    expect(sent).toEqual({
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

  it("will not publish to nobody, shows a department already in the list ticked and disabled, and adds it once", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    expect(await screen.findByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByText(/No department is chosen yet/)).toBeInTheDocument();
    await choose("PLANT1", "Production");
    const box = screen.getByRole("checkbox", { name: /^Production/ });
    expect(box).toBeChecked();
    expect(box).toBeDisabled();
    expect(screen.getByRole("button", { name: "Add the ticked departments" })).toBeDisabled();
    expect(screen.getAllByText("Plant 1 · Production")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Publish" })).toBeEnabled();
  });

  it("names the pair that cannot be published to, in the API's words, and keeps what was chosen", async () => {
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
    await choose("PLANT1", "Production", "Quality");

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    const refused = await screen.findByTestId("publish-refused");
    expect(refused).toHaveTextContent("Plant 1 · Quality: No group is set up for this department yet.");
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByText("Plant 1 · Quality")).toBeInTheDocument();
  });

  it("starts from the audience the template already has, and says the revision stays when only the audience changes", async () => {
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
        audience: [
          {
            location: "PLANT1",
            department: "PRD",
            locationLabel: "Plant 1",
            departmentLabel: "Production",
            versionNo: 3,
            publishedAt: "2026-10-22T09:00:00Z",
            groupAvailable: true,
          },
        ],
      }),
    );

    expect(await screen.findByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByText(/Revision 02 stays in use/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Remove Plant 1 · Production" }));
    expect(screen.queryByText("Plant 1 · Production")).toBeNull();
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
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

    await choose("PLANT1", "Production");
    fireEvent.click(screen.getByRole("checkbox", { name: "View only" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Allow download" }));
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const sent = JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;
    expect(sent).toEqual({
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

describe("the publish dialog with a suggestion made at approval", () => {
  it("starts from the suggested departments, which the person may change, and says where they came from", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view() }),
    });
    const { onDone } = draw(
      view({
        suggestedAudience: [
          { location: "PLANT1", locationLabel: "Plant 1", department: "PRD", departmentLabel: "Production" },
        ],
      }),
    );

    expect(await screen.findByTestId("publish-suggested")).toBeInTheDocument();
    expect(await screen.findByText("Plant 1 · Production")).toBeInTheDocument();
    await choose("PLANT1", "Quality");
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const sent = JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;
    expect(sent).toEqual({
      versionNo: 2,
      viewOnly: false,
      allowDownload: false,
      audiences: [
        { location: "PLANT1", department: "PRD" },
        { location: "PLANT1", department: "QLT" },
      ],
    });
  });

  it("has the suggested location chosen but cannot publish until a department is added", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ suggestedAudience: [{ location: "PLANT1", locationLabel: "Plant 1" }] }));

    const location = (await screen.findByLabelText("Location")) as HTMLSelectElement;
    expect(location.value).toBe("PLANT1");
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: /^Production/ }));
    fireEvent.click(screen.getByRole("button", { name: "Add the ticked departments" }));
    expect(screen.getByRole("button", { name: "Publish" })).toBeEnabled();
  });

  it("says in plain words that suggested places are no longer offered", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(view({ suggestionDropped: 2 }));

    expect(await screen.findByTestId("publish-suggestion-dropped")).toHaveTextContent(
      "2 suggested places are no longer offered and were left out.",
    );
    expect(screen.queryByTestId("publish-suggested")).not.toBeInTheDocument();
  });

  it("starts from the audience the template already has when nothing was suggested", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(
      view({
        audience: [
          {
            location: "HEAD",
            department: "HR",
            locationLabel: "Head office",
            departmentLabel: "Human resources",
            versionNo: 1,
            publishedAt: "2026-10-24T08:00:00Z",
            groupAvailable: true,
          },
        ],
      }),
    );

    expect(await screen.findByText("Head office · Human resources")).toBeInTheDocument();
    expect(screen.queryByTestId("publish-suggested")).not.toBeInTheDocument();
  });
});

describe("the publish dialog when departments were suggested", () => {
  it("has the suggested location chosen and its suggested departments shown ticked", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw(
      view({
        suggestedAudience: [
          { location: "PLANT1", locationLabel: "Plant 1", department: "PRD", departmentLabel: "Production" },
        ],
      }),
    );

    const location = (await screen.findByLabelText("Location")) as HTMLSelectElement;
    expect(location.value).toBe("PLANT1");
    expect(screen.getByRole("checkbox", { name: /^Production/ })).toBeChecked();
    expect(screen.getByRole("button", { name: "Publish" })).toBeEnabled();
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

    const places = await screen.findByTestId("publish-places");
    expect(screen.queryByLabelText("Location")).toBeNull();
    expect(screen.queryByRole("group", { name: "Departments" })).toBeNull();
    expect(within(places).getAllByRole("checkbox").map((box) => box.parentElement?.textContent?.trim())).toEqual([
      "Plant 1",
      "Plant 2 (no group set up yet)",
    ]);

    fireEvent.click(within(places).getByRole("checkbox", { name: /^Plant 1/ }));
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const sent = JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;
    expect(sent).toEqual({
      versionNo: 2,
      viewOnly: false,
      allowDownload: false,
      audiences: [{ location: "PLANT1", department: "PLANTHEAD" }],
    });
  });

  it("keeps Publish off until a place is ticked, and takes a place back off", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(PLACES) });
    draw();

    const places = await screen.findByTestId("publish-places");
    const publish = screen.getByRole("button", { name: "Publish" });
    expect(publish).toBeDisabled();
    const one = within(places).getByRole("checkbox", { name: /^Plant 1/ });
    fireEvent.click(one);
    expect(publish).toBeEnabled();
    fireEvent.click(one);
    expect(publish).toBeDisabled();
  });
});
