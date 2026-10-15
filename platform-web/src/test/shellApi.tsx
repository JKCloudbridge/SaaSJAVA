import type { ReactNode } from "react";
import { ApplicationsProvider } from "@/lib/applications/ApplicationsProvider";
import type { MyApplication } from "@/lib/applications/entries";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { fakeApi, json, refusal, type Call, type Handler } from "./fakeApi";

/** An application as the API lists it for a member, with a few kinds of entry. */
export const FIELD: MyApplication = {
  apiName: "Field__c",
  label: "Field Service",
  iconKey: "tool",
  items: [
    { type: "HEADING", label: "Work", available: true },
    {
      type: "CUSTOM",
      label: "Inspections",
      tabApiName: "Inspections__c",
      customPageApiName: "Inspection__c",
      assetId: "11111111-2222-3333-4444-555555555555",
      available: true,
    },
    { type: "OBJECT", label: "Deals", objectApiName: "Deal__c", available: true },
    { type: "LINK", label: "Members", path: "/members", available: true },
  ],
};

export const SALES: MyApplication = {
  apiName: "Sales__c",
  label: "Sales",
  iconKey: "briefcase",
  items: [{ type: "OBJECT", label: "Accounts", objectApiName: "Account__c", available: true }],
};

export interface ShellApiOptions {
  /** What the API lists for the member. */
  applications?: MyApplication[];
  /** The abilities the API lists for the signed-in member. */
  abilities?: string[];
  /** True for the platform host: the API answers "no organization" for the address. */
  platformHost?: boolean;
  /** Replaces the answer to the list of applications. */
  applicationsAnswer?: Handler;
  /** Further answers, by "METHOD /path". */
  more?: Record<string, Handler>;
}

/** Stands in for the API of an organization address with a signed-in member; returns the calls made. */
export function shellApi(options: ShellApiOptions = {}): Call[] {
  const applications = options.applications ?? [FIELD, SALES];
  return fakeApi({
    "GET /api/v1/tenant/current": () =>
      options.platformHost
        ? refusal("NOT_FOUND", "No organization exists.", 404)
        : json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } }),
    "GET /api/v1/auth/me": () =>
      json({
        data: {
          id: "user-1",
          email: "user-a@example.test",
          displayName: "User A",
          platformRoles: [],
          abilities: options.abilities ?? [],
        },
      }),
    "GET /api/v1/applications": options.applicationsAnswer ?? (() => json({ data: applications })),
    ...options.more,
  });
}

/** The providers the shell runs under. */
export function ShellProviders({ children }: { children: ReactNode }) {
  return (
    <SessionProvider>
      <ApplicationsProvider>{children}</ApplicationsProvider>
    </SessionProvider>
  );
}
