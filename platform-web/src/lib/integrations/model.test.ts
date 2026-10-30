import { describe, expect, it } from "vitest";
import { CONNECTION, KIND } from "@/test/integrations";
import { changed, defaultSettings, draftOf, newDraft, placeWords, requestOf } from "./model";

describe("the form of a connection", () => {
  it("starts at the connector type's own defaults and its first way of signing in", () => {
    expect(defaultSettings(KIND)).toEqual({ checkPath: "/", mode: "first", wait: "5" });
    const draft = newDraft(KIND);
    expect(draft).toMatchObject({ connectorType: "kind-a", authKind: "NONE", version: 0, name: "", baseUrl: "" });
    expect(newDraft(undefined)).toMatchObject({ connectorType: "", authKind: "", settings: {} });
  });

  it("holds what a connection has and sends it back the same", () => {
    const draft = draftOf({
      ...CONNECTION,
      authKind: "BEARER_TOKEN",
      credentialName: "partner-token",
      subscriptions: [{ event: "event-1", object: "Order__c" }],
      scope: [{ object: "Order__c", read: true, create: false, update: true }],
      version: 3,
    });

    expect(requestOf(draft)).toEqual({
      name: "link-a",
      label: "Link A",
      connectorType: "kind-a",
      baseUrl: "https://api.example.com",
      authKind: "BEARER_TOKEN",
      authHeaderName: undefined,
      credentialName: "partner-token",
      settings: { checkPath: "/", mode: "first", wait: "5" },
      subscriptions: [{ event: "event-1", object: "Order__c" }],
      scope: [{ object: "Order__c", read: true, create: false, update: true }],
      version: 3,
    });
  });

  it("leaves out blank settings, blank names and half-filled rows, and trims what is typed", () => {
    const draft = {
      ...newDraft(KIND),
      name: " link-b ",
      label: " Link B ",
      baseUrl: " https://api.example.com ",
      credentialName: "  ",
      authHeaderName: " ",
      settings: { checkPath: " /up ", mode: "", wait: "  " },
      subscriptions: [
        { event: "event-1", object: "" },
        { event: "event-1", object: "Order__c" },
      ],
      scope: [{ object: "", read: true, create: false, update: false }],
    };

    const request = requestOf(draft);

    expect(request).toMatchObject({ name: "link-b", label: "Link B", baseUrl: "https://api.example.com" });
    expect(request.credentialName).toBeUndefined();
    expect(request.authHeaderName).toBeUndefined();
    expect(request.settings).toEqual({ checkPath: "/up" });
    expect(request.subscriptions).toEqual([{ event: "event-1", object: "Order__c" }]);
    expect(request.scope).toEqual([]);
  });

  it("knows when it differs from where it started, and not for a stray space", () => {
    const start = draftOf(CONNECTION);

    expect(changed(start, start)).toBe(false);
    expect(changed({ ...start, label: "Link A " }, start)).toBe(false);
    expect(changed({ ...start, baseUrl: "https://other.example.com" }, start)).toBe(true);
    expect(changed({ ...start, settings: { ...start.settings, wait: "6" } }, start)).toBe(true);
  });

  it("names the place of a problem in the form's words, and a setting by the connector type's label", () => {
    expect(placeWords("baseUrl", KIND)).toBe("Address");
    expect(placeWords("credentialName", KIND)).toBe("Credential");
    expect(placeWords("settings.wait", KIND)).toBe("Seconds to wait");
    expect(placeWords("settings.unknown", KIND)).toBe("Settings");
    expect(placeWords("something-else", KIND)).toBe("Connection");
    expect(placeWords("settings.wait", undefined)).toBe("Settings");
  });
});
