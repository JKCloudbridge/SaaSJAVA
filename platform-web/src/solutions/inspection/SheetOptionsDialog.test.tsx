import { configure, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import type { TemplateLifecycleView } from "./api";
import { SheetOptionsDialog } from "./SheetOptionsDialog";

configure({ asyncUtilTimeout: 5000 });

const ID = "0198f5c0-0000-7000-8000-000000000042";

function view(extra: Partial<TemplateLifecycleView> = {}): TemplateLifecycleView {
  return {
    id: ID,
    retired: false,
    lifecycle: {
      state: "ACTIVE",
      activeVersion: 2,
      approvedVersion: 0,
      inReviewVersion: 0,
      draftVersion: 0,
      retiredVersion: 0,
      highestVersion: 2,
    },
    audience: [],
    canPublish: true,
    canUnpublish: true,
    canRetire: true,
    numberPreviewComplete: false,
    openChangeRequests: 0,
    versionReviews: [],
    suggestedAudience: [],
    suggestionDropped: 0,
    viewOnly: false,
    allowDownload: false,
    canDownload: true,
    canSubmit: true,
    footerDate: false,
    limitedToSuggested: false,
    noPlaceLeft: false,
    unpublishedPlaces: [],
    canSetOptions: true,
    ...extra,
  };
}

function draw(lifecycle = view()) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(<SheetOptionsDialog templateId={ID} lifecycle={lifecycle} onClose={onClose} onDone={onDone} />);
  return { onDone, onClose };
}

// The "Sheet options" control (FA7, ADR-0201): the same two tick boxes as Publish, changed later without publishing again.
describe("the sheet options dialog", () => {
  it("shows both boxes in one row, as the template has them", () => {
    draw(view({ viewOnly: true, allowDownload: false }));

    expect(screen.getByRole("checkbox", { name: "View only" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Allow download" })).not.toBeChecked();
    expect(screen.getByRole("group", { name: "Sheet options" })).toBeInTheDocument();
  });

  it("sends both values to the server and reports the answer", async () => {
    const calls = fakeApi({ [`PUT /api/v1/templates/${ID}/options`]: () => json({ data: view({ viewOnly: true, allowDownload: true }) }) });
    const { onDone } = draw();

    fireEvent.click(screen.getByRole("checkbox", { name: "View only" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Allow download" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(JSON.parse(calls.at(-1)?.body ?? "null")).toEqual({ viewOnly: true, allowDownload: true });
  });

  it("shows the API's words when it refuses and does not report success", async () => {
    fakeApi({ [`PUT /api/v1/templates/${ID}/options`]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });
    const { onDone } = draw();

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("sheet-options-refused")).toHaveTextContent("You are not allowed to perform this action.");
    expect(onDone).not.toHaveBeenCalled();
  });

  it("closes without saving when it is cancelled", () => {
    const calls = fakeApi({});
    const { onClose } = draw();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(0);
  });
});
