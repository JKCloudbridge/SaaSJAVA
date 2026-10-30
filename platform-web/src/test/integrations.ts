import type { ConnectorType, Integration } from "@/lib/integrations/model";

/**
 * A connector type and a connection for the tests of the integration screens. The keys are made up on purpose: the
 * screens hold no list of connector types, settings or events, so a test must not rely on the names of the real one.
 */
export const KIND: ConnectorType = {
  key: "kind-a",
  label: "Service A",
  description: "A service that answers a plain question.",
  version: 1,
  authKinds: [
    { key: "NONE", label: "No sign-in" },
    { key: "BEARER_TOKEN", label: "Bearer token" },
  ],
  settings: [
    { key: "checkPath", label: "Check path", hint: "The path to ask.", kind: "PATH", required: false, defaultValue: "/", choices: [] },
    {
      key: "mode",
      label: "Mode",
      hint: "How to ask.",
      kind: "CHOICE",
      required: false,
      defaultValue: "first",
      choices: [
        { key: "first", label: "First way" },
        { key: "second", label: "Second way" },
      ],
    },
    { key: "wait", label: "Seconds to wait", hint: "From 1 to 10.", kind: "INTEGER", required: false, defaultValue: "5", choices: [], min: 1, max: 10 },
  ],
  platformEvents: [{ key: "event-1", label: "Something happens" }],
};

export const CONNECTION: Integration = {
  id: "11111111-1111-4111-8111-111111111111",
  name: "link-a",
  label: "Link A",
  connectorType: "kind-a",
  connectorTypeLabel: "Service A",
  connectorVersion: 1,
  baseUrl: "https://api.example.com",
  authKind: "NONE",
  settings: { checkPath: "/", mode: "first", wait: "5" },
  subscriptions: [],
  scope: [],
  state: "UNTESTED",
  stateLabel: "Not tested",
  canEnable: false,
  canChange: true,
  version: 0,
};

/** The connection as it is in another state, with the API's own flags for what can be done. */
export function inState(state: string, stateLabel: string, extra: Partial<Integration> = {}): Integration {
  return { ...CONNECTION, state, stateLabel, ...extra };
}
