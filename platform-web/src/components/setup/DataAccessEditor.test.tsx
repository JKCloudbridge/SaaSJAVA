import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { DataAccessEditor } from "./DataAccessEditor";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const PROFILE = "33333333-3333-4333-8333-333333333333";
const CATALOGUE = {
  data: {
    objects: [
      {
        key: "object-a",
        label: "Object A",
        fields: [
          { key: "field-a", label: "Field A" },
          { key: "field-b", label: "Field B" },
        ],
      },
    ],
    objectActions: [
      { key: "read", title: "Read", implies: [] },
      { key: "create", title: "Create", implies: ["read"] },
      { key: "update", title: "Update", implies: ["read"] },
      { key: "delete", title: "Delete", implies: ["read"] },
      { key: "view-all", title: "View all", implies: ["read"] },
      { key: "modify-all", title: "Modify all", implies: ["read", "create", "update", "delete", "view-all"] },
    ],
    fieldActions: [
      { key: "read", title: "Read", implies: [] },
      { key: "edit", title: "Edit", implies: ["read"] },
    ],
  },
};
const MATRIX = { data: { everything: false, objects: [{ key: "object-a", actions: ["update"] }], fields: [] } };

describe("DataAccessEditor", () => {
  it("shows what the API says is allowed, with the implied actions ticked and fixed", async () => {
    fakeApi({
      "GET /api/v1/data-catalogue": () => json(CATALOGUE),
      [`GET /api/v1/profiles/${PROFILE}/data-access`]: () => json(MATRIX),
    });
    render(<DataAccessEditor target={{ kind: "profile", id: PROFILE }} title="Permissions" />);

    const update = await screen.findByLabelText("Object A: Update");
    expect(update).toBeChecked();
    const read = screen.getByLabelText("Object A: Read");
    expect(read).toBeChecked();
    expect(read).toBeDisabled();
    expect(screen.getByLabelText("Object A: Delete")).not.toBeChecked();
    expect(screen.getByLabelText("Object A / Field A: Edit")).not.toBeChecked();
  });

  it("sends the whole matrix when saved and shows the API's notice", async () => {
    const calls = fakeApi({
      "GET /api/v1/data-catalogue": () => json(CATALOGUE),
      [`GET /api/v1/profiles/${PROFILE}/data-access`]: () => json(MATRIX),
      [`PUT /api/v1/profiles/${PROFILE}/data-access`]: () => json(MATRIX),
    });
    render(<DataAccessEditor target={{ kind: "profile", id: PROFILE }} title="Permissions" />);
    await screen.findByLabelText("Object A: Update");

    fireEvent.click(screen.getByLabelText("Object A / Field A: Edit"));
    fireEvent.click(screen.getByLabelText("Object A: Delete"));
    fireEvent.click(screen.getByRole("button", { name: "Save the permissions" }));

    expect(await screen.findByTestId("data-access-notice")).toHaveTextContent("The permissions were saved.");
    const put = calls.find((call) => call.key === `PUT /api/v1/profiles/${PROFILE}/data-access`);
    expect(JSON.parse(put?.body ?? "{}")).toEqual({
      objects: [{ key: "object-a", actions: ["update", "delete"] }],
      fields: [{ key: "object-a.field-a", actions: ["edit"] }],
    });
  });

  it("shows the API's own words when a save is refused", async () => {
    fakeApi({
      "GET /api/v1/data-catalogue": () => json(CATALOGUE),
      [`GET /api/v1/profiles/${PROFILE}/data-access`]: () => json(MATRIX),
      [`PUT /api/v1/profiles/${PROFILE}/data-access`]: () =>
        refusal("CONFLICT", "The administrator profile always has full access and cannot be changed.", 409),
    });
    render(<DataAccessEditor target={{ kind: "profile", id: PROFILE }} title="Permissions" />);
    await screen.findByLabelText("Object A: Update");

    fireEvent.click(screen.getByRole("button", { name: "Save the permissions" }));

    expect(await screen.findByTestId("data-access-problem")).toHaveTextContent("cannot be changed");
  });

  it("says so for the administrator profile and offers nothing to change", async () => {
    fakeApi({
      "GET /api/v1/data-catalogue": () => json(CATALOGUE),
      [`GET /api/v1/profiles/${PROFILE}/data-access`]: () => json({ data: { everything: true, objects: [], fields: [] } }),
    });
    render(<DataAccessEditor target={{ kind: "profile", id: PROFILE }} title="Permissions" />);

    expect(await screen.findByTestId("data-access-everything")).toHaveTextContent("every permission");
    expect(screen.queryByRole("button", { name: "Save the permissions" })).toBeNull();
  });

  it("says that objects come later when the catalogue is empty", async () => {
    fakeApi({
      "GET /api/v1/data-catalogue": () => json({ data: { objects: [], objectActions: [], fieldActions: [] } }),
      "GET /api/v1/access-policies/44444444-4444-4444-8444-444444444444/data-access": () => json(MATRIX),
    });
    render(
      <DataAccessEditor target={{ kind: "policy", id: "44444444-4444-4444-8444-444444444444" }} title="Permissions" />,
    );

    expect(await screen.findByTestId("data-access-empty")).toHaveTextContent("Sprint 10");
  });

  it("shows the API's refusal of the page itself and loads once under strict mode", async () => {
    const calls = fakeApi({
      "GET /api/v1/data-catalogue": () => refusal("FORBIDDEN", "You are not allowed to do this.", 403),
      [`GET /api/v1/members/${PROFILE}/data-access`]: () => json(MATRIX),
    });
    render(
      <StrictMode>
        <DataAccessEditor target={{ kind: "member", id: PROFILE }} title="Permissions" />
      </StrictMode>,
    );

    expect(await screen.findByTestId("data-access-failed")).toHaveTextContent("not allowed");
    await waitFor(() => expect(calls.filter((call) => call.key === "GET /api/v1/data-catalogue").length).toBeLessThanOrEqual(2));
  });
});
