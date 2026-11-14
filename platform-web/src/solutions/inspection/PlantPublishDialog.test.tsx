import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { PlantPublishDialog } from "./PlantPublishDialog";
import type { PlantState } from "./plantPublication";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000077";

const OPTIONS = {
  data: {
    placesOnly: true,
    placeDepartment: "PLANTHEAD",
    locations: [
      { value: "PLANT1", label: "Plant 1", departments: [{ value: "PLANTHEAD", label: "Plant head", configured: true }] },
      { value: "PLANT2", label: "Plant 2", departments: [{ value: "PLANTHEAD", label: "Plant head", configured: true }] },
      { value: "HEAD", label: "Head office", departments: [{ value: "PLANTHEAD", label: "Plant head", configured: false }] },
    ],
  },
};

function state(extra: Partial<PlantState> = {}): PlantState {
  return { available: true, canPublish: true, plants: [], ...extra };
}

const CHOICES = {
  data: {
    people: [{ kind: "USER", id: "u-1", label: "Reviewer 1" }],
    groups: [{ kind: "GROUP", id: "g-1", label: "Review group" }],
  },
};

function draw(held = state()) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(<PlantPublishDialog templateId={ID} state={held} onClose={onClose} onDone={onDone} />);
  return { onDone, onClose };
}

async function addReviewerTo(plantLabel: string, search: string, name: string) {
  fireEvent.change(await screen.findByLabelText(`Find a reviewer for ${plantLabel}`), { target: { value: search } });
  fireEvent.click((await screen.findAllByRole("button", { name: "Add" }, { timeout: 4000 }))[0]!);
  await screen.findByText(name);
}

describe("the dialog that publishes a document to its plants", () => {
  it("offers the plants, shows one with no group of plant heads but does not let it be ticked", async () => {
    fakeApi({ ["GET /api/v1/templates/" + ID + "/plants/options"]: () => json(OPTIONS) });
    draw();

    expect(await screen.findByRole("checkbox", { name: "Plant 1" })).not.toBeDisabled();
    expect(screen.getByRole("checkbox", { name: /Head office \(no group of plant heads set up yet\)/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("asks for at least one reviewer for each plant ticked before it can be saved", async () => {
    fakeApi({
      ["GET /api/v1/templates/" + ID + "/plants/options"]: () => json(OPTIONS),
      ["POST /api/v1/templates/" + ID + "/plants/reviewer-choices"]: () => json(CHOICES),
    });
    draw();
    fireEvent.click(await screen.findByRole("checkbox", { name: "Plant 1" }));

    expect(screen.getByText("Choose at least one reviewer for this plant.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await addReviewerTo("Plant 1", "rev", "Reviewer 1");

    expect(screen.queryByText("Choose at least one reviewer for this plant.")).toBeNull();
    expect(screen.getByRole("button", { name: "Save" })).not.toBeDisabled();
  });

  it("sends the kind and the identifier of each reviewer of each plant and hands the answer on", async () => {
    const answer: PlantState = state({
      plants: [{ plant: "PLANT1", label: "Plant 1", publishedAt: "2026-10-22T09:00:00Z", groupExists: true, reviewers: [] }],
    });
    const calls = fakeApi({
      ["GET /api/v1/templates/" + ID + "/plants/options"]: () => json(OPTIONS),
      ["POST /api/v1/templates/" + ID + "/plants/reviewer-choices"]: () => json(CHOICES),
      ["PUT /api/v1/templates/" + ID + "/plants"]: () => json({ data: answer }),
    });
    const { onDone } = draw();
    fireEvent.click(await screen.findByRole("checkbox", { name: "Plant 1" }));
    await addReviewerTo("Plant 1", "rev", "Reviewer 1");

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(answer));
    const sent = calls.find((call) => call.key === "PUT /api/v1/templates/" + ID + "/plants");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ plants: [{ plant: "PLANT1", reviewers: [{ kind: "USER", id: "u-1" }] }] });
  });

  it("says which plant and which reviewer the server refused, in the server's words, and keeps the choices", async () => {
    fakeApi({
      ["GET /api/v1/templates/" + ID + "/plants/options"]: () => json(OPTIONS),
      ["POST /api/v1/templates/" + ID + "/plants/reviewer-choices"]: () => json(CHOICES),
      ["PUT /api/v1/templates/" + ID + "/plants"]: () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is not valid.",
              fields: { "plants[0].reviewers[0]": ["This person is not an active member of the organization."] },
            },
          },
          400,
        ),
    });
    const { onDone } = draw();
    fireEvent.click(await screen.findByRole("checkbox", { name: "Plant 1" }));
    await addReviewerTo("Plant 1", "rev", "Reviewer 1");

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const refused = await screen.findByTestId("plant-publish-refused");
    expect(refused).toHaveTextContent("Plant 1 · Reviewer 1: This person is not an active member of the organization.");
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByRole("checkbox", { name: "Plant 1" })).toBeChecked();
  });

  it("starts from what the document has and says that saving with no plant takes it back from all of them", async () => {
    fakeApi({ ["GET /api/v1/templates/" + ID + "/plants/options"]: () => json(OPTIONS) });
    draw(
      state({
        plants: [
          {
            plant: "PLANT1",
            label: "Plant 1",
            publishedAt: "2026-10-22T09:00:00Z",
            groupExists: true,
            reviewers: [{ kind: "USER", id: "u-1", name: "Reviewer 1" }],
          },
        ],
      }),
    );

    expect(await screen.findByRole("checkbox", { name: "Plant 1" })).toBeChecked();
    expect(screen.getByText("Reviewer 1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.click(screen.getByRole("checkbox", { name: "Plant 1" }));

    expect(screen.getByTestId("plant-take-all")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).not.toBeDisabled();
  });

  it("shows the server's own words when it cannot load the plants", async () => {
    fakeApi({
      ["GET /api/v1/templates/" + ID + "/plants/options"]: () => refusal("FORBIDDEN", "You may not do that.", 403),
    });
    draw();

    expect(await screen.findByRole("alert")).toHaveTextContent("You may not do that.");
  });
});
