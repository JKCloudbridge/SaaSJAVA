import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    expect(screen.getByText("Choose a location first.")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "PLANT1" } });
    expect(screen.getAllByRole("checkbox").map((box) => box.parentElement?.textContent?.trim())).toEqual([
      "Production",
      "Quality (no group set up yet)",
    ]);

    fireEvent.change(screen.getByLabelText("Location"), { target: { value: "HEAD" } });
    expect(screen.getAllByRole("checkbox").map((box) => box.parentElement?.textContent?.trim())).toEqual([
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
