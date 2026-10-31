import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { appBase, DEFAULT_APPLICATION } from "./appPaths";
import { useApplication } from "./application";
import { activityHref, homeLinks, HOME_LINKS } from "./home/model";
import { changeRequestsHref } from "./changeRequests";
import { fillInsHref } from "./fillIns";

const params = vi.hoisted(() => ({ current: {} as Record<string, string> }));
vi.mock("next/navigation", () => ({ useParams: () => params.current }));

describe("the application the pages of the solution are shown in (FA13A)", () => {
  it("is the one the address names, decoded", () => {
    params.current = { application: "document-management" };
    expect(renderHook(() => useApplication()).result.current).toBe("document-management");
    params.current = { application: "a%20b" };
    expect(renderHook(() => useApplication()).result.current).toBe("a b");
  });

  it("is the first application that shows these pages when the address names none", () => {
    params.current = {};
    expect(renderHook(() => useApplication()).result.current).toBe(DEFAULT_APPLICATION);
    expect(DEFAULT_APPLICATION).toBe("inspection");
  });

  it("starts every address inside the application it was given", () => {
    expect(appBase("document-management")).toBe("/apps/document-management");
    expect(fillInsHref(undefined, "document-management")).toBe("/apps/document-management/s-fill-ins");
    expect(fillInsHref("f1", "document-management")).toBe("/apps/document-management/s-fill-ins?open=f1");
    expect(changeRequestsHref("t1", "r1", "document-management")).toBe(
      "/apps/document-management/s-templates?open=t1&tab=change-requests&request=r1",
    );
    const links = homeLinks("document-management");
    expect(links.requests).toBe("/apps/document-management/s-change-requests");
    expect(links.templates()).toBe("/apps/document-management/s-templates");
    expect(links.templates("IN_REVIEW")).toBe("/apps/document-management/s-templates?state=IN_REVIEW");
    expect(links.fills("SUBMITTED")).toBe("/apps/document-management/s-fill-ins?status=SUBMITTED");
    expect(links.fillsOfTemplate("t 1")).toBe("/apps/document-management/s-fill-ins?template=t%201");
    expect(links.approvals).toBe("/approvals");
    expect(activityHref({ about: "fill-in", id: "f1" }, "document-management")).toBe(
      "/apps/document-management/s-fill-ins?open=f1",
    );
    expect(activityHref({ about: "template", id: "t1" }, "document-management")).toBe(
      "/apps/document-management/s-templates?open=t1",
    );
  });

  it("keeps the addresses of the first application when none is given", () => {
    expect(fillInsHref("f1")).toBe("/apps/inspection/s-fill-ins?open=f1");
    expect(HOME_LINKS.requests).toBe("/apps/inspection/s-change-requests");
    expect(activityHref({ about: "template", id: "t1" })).toBe("/apps/inspection/s-templates?open=t1");
  });
});
