import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { StartFillIn } from "./StartFillIn";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const TEMPLATE = "0198f5c0-0000-7000-8000-000000000042";
const FILL = "0198f5c0-0000-7000-8000-000000000051";
const OPTIONS = `GET /api/v1/templates/${TEMPLATE}/fill-in-options`;
const START = `POST /api/v1/templates/${TEMPLATE}/fill-ins`;

beforeEach(() => {
  setUrl(`/apps/inspection/s-templates?open=${TEMPLATE}`);
});

describe("starting a fill-in from a template", () => {
  it("offers Start when the API says the member may, with the version it will be made on", async () => {
    fakeApi({ [OPTIONS]: () => json({ data: { canStart: true, activeVersion: 3 } }) });

    render(<StartFillIn templateId={TEMPLATE} refresh={0} />);

    expect(await screen.findByRole("button", { name: "Start a fill-in" })).toBeInTheDocument();
    expect(screen.getByText(/made on the version in use, version 3/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See the fill-ins of this template" })).toHaveAttribute(
      "href",
      `/apps/inspection/s-fill-ins?template=${TEMPLATE}`,
    );
  });

  it("starts the fill-in and opens it in the fill-ins page", async () => {
    const calls = fakeApi({
      [OPTIONS]: () => json({ data: { canStart: true, activeVersion: 3 } }),
      [START]: () => json({ data: { fillId: FILL, templateId: TEMPLATE, versionNo: 3 } }, 201),
    });
    render(<StartFillIn templateId={TEMPLATE} refresh={0} />);

    fireEvent.click(await screen.findByRole("button", { name: "Start a fill-in" }));

    await waitFor(() => expect(currentUrl()).toBe(`/apps/inspection/s-fill-ins?open=${FILL}`));
    expect(calls.filter((call) => call.key === START)).toHaveLength(1);
  });

  it("offers no button when the API says the member may not, and gives the reason in its words", async () => {
    fakeApi({
      [OPTIONS]: () =>
        json({ data: { canStart: false, activeVersion: 0, reason: "You do not have the right to fill in templates." } }),
    });

    render(<StartFillIn templateId={TEMPLATE} refresh={0} />);

    expect(await screen.findByTestId("fill-in-reason")).toHaveTextContent("You do not have the right to fill in templates.");
    expect(screen.queryByRole("button", { name: "Start a fill-in" })).not.toBeInTheDocument();
  });

  it("shows the API's refusal when the start is refused and stays where it is", async () => {
    fakeApi({
      [OPTIONS]: () => json({ data: { canStart: true, activeVersion: 3 } }),
      [START]: () => refusal("CONFLICT", "The template changed while you started. Try again.", 409),
    });
    render(<StartFillIn templateId={TEMPLATE} refresh={0} />);

    fireEvent.click(await screen.findByRole("button", { name: "Start a fill-in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The template changed while you started. Try again.");
    expect(currentUrl()).toBe(`/apps/inspection/s-templates?open=${TEMPLATE}`);
    expect(screen.getByRole("button", { name: "Start a fill-in" })).toBeEnabled();
  });

  it("says in the API's words when it cannot tell", async () => {
    fakeApi({ [OPTIONS]: () => refusal("NOT_FOUND", "This template does not exist.", 404) });

    render(<StartFillIn templateId={TEMPLATE} refresh={0} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("This template does not exist.");
  });
});
