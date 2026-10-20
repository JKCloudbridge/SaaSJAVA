import { useSyncExternalStore } from "react";
import { vi } from "vitest";

/**
 * A stand-in for `next/navigation` with an address that changes when the page pushes one, so a test can click a row and see
 * the detail open. Use it as `vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock)`.
 */
let url = "/";
const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Moves the fake address (a test's starting point, or what the page asks for). */
export function setUrl(next: string) {
  url = next;
  listeners.forEach((listener) => listener());
}

export const currentUrl = () => url;

export const fakeRouter = {
  push: vi.fn((next: string) => setUrl(next)),
  replace: vi.fn((next: string) => setUrl(next)),
  back: vi.fn(),
  prefetch: vi.fn(),
};

export const navigationMock = {
  useRouter: () => fakeRouter,
  usePathname: () => useSyncExternalStore(subscribe, () => url).split("?")[0],
  useSearchParams: () => {
    const current = useSyncExternalStore(subscribe, () => url);
    return new URLSearchParams(current.split("?")[1] ?? "");
  },
  useParams: () => ({}),
};
