import type { components } from "@/lib/api/generated/schema";

export type Integration = components["schemas"]["IntegrationView"];
export type ConnectorType = components["schemas"]["ConnectorTypeView"];
export type SaveRequest = components["schemas"]["SaveIntegrationRequest"];
export type SettingSpec = ConnectorType["settings"][number];

/** A subscription or a scope row as the form holds it. */
export interface SubscriptionDraft {
  event: string;
  object: string;
}

export interface ScopeDraft {
  object: string;
  read: boolean;
  create: boolean;
  update: boolean;
}

/** Everything the form holds. Interface state only: nothing here is a rule, the server checks every part again. */
export interface Draft {
  name: string;
  label: string;
  connectorType: string;
  baseUrl: string;
  authKind: string;
  authHeaderName: string;
  credentialName: string;
  settings: Record<string, string>;
  subscriptions: SubscriptionDraft[];
  scope: ScopeDraft[];
  version: number;
}

/** The settings a connector type asks for, each with the value it starts at: the type's own default. */
export function defaultSettings(type: ConnectorType | undefined): Record<string, string> {
  const result: Record<string, string> = {};
  for (const setting of type?.settings ?? []) {
    if (setting.defaultValue != null) {
      result[setting.key] = setting.defaultValue;
    }
  }
  return result;
}

/** A form for a new connection of the type: the type's first way of signing in, its defaults, nothing else. */
export function newDraft(type: ConnectorType | undefined): Draft {
  return {
    name: "",
    label: "",
    connectorType: type?.key ?? "",
    baseUrl: "",
    authKind: type?.authKinds[0]?.key ?? "",
    authHeaderName: "",
    credentialName: "",
    settings: defaultSettings(type),
    subscriptions: [],
    scope: [],
    version: 0,
  };
}

/** A form holding what a connection has now. */
export function draftOf(item: Integration): Draft {
  return {
    name: item.name,
    label: item.label,
    connectorType: item.connectorType,
    baseUrl: item.baseUrl,
    authKind: item.authKind,
    authHeaderName: item.authHeaderName ?? "",
    credentialName: item.credentialName ?? "",
    settings: { ...item.settings },
    subscriptions: item.subscriptions.map((one) => ({ event: one.event, object: one.object })),
    scope: item.scope.map((one) => ({ object: one.object, read: one.read, create: one.create, update: one.update })),
    version: item.version,
  };
}

/**
 * The request for the form. Blank settings are left out so that the type's own default applies; a half-filled row of a
 * list is left out too (it is not a thing yet). The server checks everything again and answers in its own words.
 */
export function requestOf(draft: Draft): SaveRequest {
  const settings: Record<string, string> = {};
  for (const [key, value] of Object.entries(draft.settings)) {
    if (value.trim() !== "") {
      settings[key] = value.trim();
    }
  }
  return {
    name: draft.name.trim(),
    label: draft.label.trim(),
    connectorType: draft.connectorType,
    baseUrl: draft.baseUrl.trim(),
    authKind: draft.authKind,
    authHeaderName: draft.authHeaderName.trim() === "" ? undefined : draft.authHeaderName.trim(),
    credentialName: draft.credentialName.trim() === "" ? undefined : draft.credentialName.trim(),
    settings,
    subscriptions: draft.subscriptions.filter((row) => row.event !== "" && row.object !== ""),
    scope: draft.scope.filter((row) => row.object !== ""),
    version: draft.version,
  };
}

/** Whether the form differs from what it started with, for the "unsaved" note. */
export function changed(draft: Draft, start: Draft): boolean {
  return JSON.stringify(requestOf(draft)) !== JSON.stringify(requestOf(start));
}

const FIELD_WORDS: Record<string, string> = {
  name: "Name",
  label: "Label",
  connectorType: "Kind",
  baseUrl: "Address",
  authKind: "Sign-in",
  authHeaderName: "Header",
  credentialName: "Credential",
  settings: "Settings",
  subscriptions: "Events",
  scope: "What it may touch",
  version: "Version",
};

/** The place a problem is about, in the form's own words; a setting is named by the connector type's label. */
export function placeWords(field: string, type: ConnectorType | undefined): string {
  if (field.startsWith("settings.")) {
    const key = field.slice("settings.".length);
    return type?.settings.find((setting) => setting.key === key)?.label ?? "Settings";
  }
  return FIELD_WORDS[field] ?? "Connection";
}
