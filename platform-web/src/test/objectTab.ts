import { vi } from "vitest";
import type { Authorship } from "@/components/setup/authorship";
import type { ObjectTabProps } from "@/components/setup/objectTab";

/**
 * The props the page of an object hands to a tab (UA3), for a test of one tab on its own: the object as given, no field
 * types, no other objects, a `reload` that does nothing and an authorship that knows nobody. Override what a test cares about.
 */
export function tabProps(object: unknown, overrides: Partial<ObjectTabProps> = {}): ObjectTabProps & { reload: ReturnType<typeof vi.fn> } {
  const nobody: Authorship = { of: () => undefined };
  return {
    object: object as ObjectTabProps["object"],
    types: [],
    objects: [],
    reload: vi.fn(async () => undefined),
    refreshToken: 0,
    authorship: nobody,
    ...overrides,
  } as ObjectTabProps & { reload: ReturnType<typeof vi.fn> };
}
