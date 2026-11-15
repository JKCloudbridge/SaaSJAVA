import { describe, expect, it } from "vitest";
import {
  addReviewer,
  isPlantChosen,
  plantsWithoutReviewer,
  refusedPlace,
  removeReviewer,
  sameAsHeld,
  selectionFrom,
  togglePlant,
  toRequest,
  type PlantSelection,
  type PlantView,
} from "./plantPublication";

const USER_A = { kind: "USER" as const, id: "u-a", name: "Reviewer A" };
const USER_B = { kind: "USER" as const, id: "u-b", name: "Reviewer B" };
const GROUP = { kind: "GROUP" as const, id: "g-1", name: "Review group" };

describe("the choices of the dialog that publishes a document to its plants", () => {
  it("ticks a plant with no reviewer and unticks it again", () => {
    const ticked = togglePlant([], "PLANT1");

    expect(ticked).toEqual([{ plant: "PLANT1", reviewers: [] }]);
    expect(isPlantChosen(ticked, "PLANT1")).toBe(true);
    expect(plantsWithoutReviewer(ticked)).toEqual(["PLANT1"]);
    expect(togglePlant(ticked, "PLANT1")).toEqual([]);
  });

  it("adds a person or a group to one plant only and never lists the same one twice for a plant", () => {
    let selection = togglePlant(togglePlant([], "PLANT1"), "PLANT2");
    selection = addReviewer(selection, "PLANT1", USER_A);
    selection = addReviewer(selection, "PLANT1", USER_A);
    selection = addReviewer(selection, "PLANT1", GROUP);
    selection = addReviewer(selection, "PLANT2", USER_A);

    expect(selection[0]?.reviewers).toEqual([USER_A, GROUP]);
    expect(selection[1]?.reviewers).toEqual([USER_A]);
    expect(plantsWithoutReviewer(selection)).toEqual([]);
    expect(addReviewer(selection, "NOWHERE", USER_B)).toEqual(selection);
  });

  it("takes a reviewer off one plant and leaves the others as they are", () => {
    let selection = addReviewer(addReviewer(togglePlant([], "PLANT1"), "PLANT1", USER_A), "PLANT1", USER_B);

    selection = removeReviewer(selection, "PLANT1", "USER", "u-a");

    expect(selection[0]?.reviewers).toEqual([USER_B]);
    expect(removeReviewer(selection, "PLANT1", "GROUP", "u-b")[0]?.reviewers).toEqual([USER_B]);
  });

  it("makes the request from the order ticked and sends the kind and the identifier and never a name", () => {
    const selection = addReviewer(addReviewer(togglePlant(togglePlant([], "PLANT2"), "PLANT1"), "PLANT2", USER_A), "PLANT1", GROUP);

    expect(toRequest(selection)).toEqual({
      plants: [
        { plant: "PLANT2", reviewers: [{ kind: "USER", id: "u-a" }] },
        { plant: "PLANT1", reviewers: [{ kind: "GROUP", id: "g-1" }] },
      ],
    });
    expect(JSON.stringify(toRequest(selection))).not.toContain("Reviewer A");
  });

  it("starts from what the document has, and a reviewer who is gone is shown without a name", () => {
    const plants: PlantView[] = [
      {
        plant: "PLANT1",
        label: "Plant 1",
        publishedAt: "2026-10-22T09:00:00Z",
        groupExists: true,
        reviewers: [
          { kind: "USER", id: "u-a", name: "Reviewer A" },
          { kind: "GROUP", id: "g-gone", name: undefined },
        ],
      },
    ];

    const selection = selectionFrom(plants);

    expect(selection[0]?.reviewers[0]).toEqual(USER_A);
    expect(selection[0]?.reviewers[1]?.kind).toBe("GROUP");
    expect(selection[0]?.reviewers[1]?.name).toMatch(/no longer available/);
  });

  it("knows when nothing was changed, whatever the order of the plants and reviewers", () => {
    const held: PlantSelection[] = [
      { plant: "PLANT1", reviewers: [USER_A, USER_B] },
      { plant: "PLANT2", reviewers: [GROUP] },
    ];
    const reordered: PlantSelection[] = [
      { plant: "PLANT2", reviewers: [GROUP] },
      { plant: "PLANT1", reviewers: [USER_B, USER_A] },
    ];

    expect(sameAsHeld(reordered, held)).toBe(true);
    expect(sameAsHeld(held.slice(0, 1), held)).toBe(false);
    expect(sameAsHeld(addReviewer(held, "PLANT2", USER_A), held)).toBe(false);
  });

  it("reads the place the server names as the plant and the reviewer of the request that was sent", () => {
    const selection: PlantSelection[] = [
      { plant: "PLANT1", reviewers: [USER_A] },
      { plant: "PLANT2", reviewers: [USER_B, GROUP] },
    ];

    expect(refusedPlace("plants[1]", selection)).toEqual({ plant: "PLANT2", reviewer: undefined });
    expect(refusedPlace("plants[1].reviewers[1]", selection)).toEqual({ plant: "PLANT2", reviewer: "Review group" });
    expect(refusedPlace("plants[7]", selection)).toEqual({});
    expect(refusedPlace("audiences[0]", selection)).toEqual({});
  });
});
