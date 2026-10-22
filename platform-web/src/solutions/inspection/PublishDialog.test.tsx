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
    ...extra,
  };
}

function draw(lifecycle = view()) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(<PublishDialog templateId={ID} lifecycle={lifecycle} onClose={onClose} onDone={onDone} />);
  return { onDone, onClose };
}

async function choose(location: string, department: string) {
  fireEvent.change(await screen.findByLabelText("Location"), { target: { value: location } });
  fireEvent.change(screen.getByLabelText("Department"), { target: { value: department } });
  fireEvent.click(screen.getByRole("button", { name: "Add this department" }));
}

describe("the publish dialog", () => {
  it("asks for the location first and narrows the departments to it", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    const department = await screen.findByLabelText("Department");
    expect(department).toBeDisabled();
    expect(screen.getByRole("option", { name: "Choose a location first" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "PLANT1" } });
    expect(department).toBeEnabled();
    expect(within(department).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Choose a department",
      "Production",
      "Quality (no group set up yet)",
    ]);

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "HEAD" } });
    expect(within(department).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Choose a department",
      "Human resources",
    ]);
  });

  it("publishes the version waiting, to every department added, and hands over the answer", async () => {
    const calls = fakeApi({
      "GET /api/v1/templates/audience-options": () => json(OPTIONS),
      [`POST /api/v1/templates/${ID}/publish`]: () => json({ data: view({ audience: [] }) }),
    });
    const { onDone } = draw();

    await choose("PLANT1", "PRD");
    await choose("HEAD", "HR");
    expect(screen.getByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByText("Head office · Human resources")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const sent = JSON.parse(calls.filter((call) => call.key.endsWith("/publish")).at(-1)?.body ?? "null") as unknown;
    expect(sent).toEqual({
      versionNo: 2,
      audiences: [
        { location: "PLANT1", department: "PRD" },
        { location: "HEAD", department: "HR" },
      ],
    });
  });

  it("will not publish to nobody, and does not add the same department twice", async () => {
    fakeApi({ "GET /api/v1/templates/audience-options": () => json(OPTIONS) });
    draw();

    expect(await screen.findByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByText(/No department is chosen yet/)).toBeInTheDocument();
    await choose("PLANT1", "PRD");
    fireEvent.change(screen.getByLabelText("Department"), { target: { value: "PRD" } });
    expect(screen.getByRole("button", { name: "Add this department" })).toBeDisabled();
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
    await choose("PLANT1", "PRD");
    await choose("PLANT1", "QLT");

    fireEvent.click(screen.getByRole("button", { name: "Publish" }));

    const refused = await screen.findByTestId("publish-refused");
    expect(refused).toHaveTextContent("Plant 1 · Quality: No group is set up for this department yet.");
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByText("Plant 1 · Production")).toBeInTheDocument();
    expect(screen.getByText("Plant 1 · Quality")).toBeInTheDocument();
  });

  it("starts from the audience the template already has, and says the version stays when only the audience changes", async () => {
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
    expect(screen.getByText(/Version 3 stays in use/)).toBeInTheDocument();
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

    expect(await screen.findByText(/Version 4 becomes the one in use and replaces version 3/)).toBeInTheDocument();
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
