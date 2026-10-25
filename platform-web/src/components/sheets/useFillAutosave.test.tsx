import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { sampleDoc } from "@/lib/sheets/fixtures";
import type { FillDoc } from "@/lib/sheets/fillTypes";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { useFillAutosave } from "./useFillAutosave";

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const owner = { object: "Subject", id: "fill-1" };
const initial: FillDoc = { formatVersion: 1, rows: [] };
const changed = (value: string): FillDoc => ({ formatVersion: 1, rows: [{ id: "r1", cells: { c2: value } }] });
const url = "/api/v1/sheet-fills/Subject/fill-1/cells";
const grid = sampleDoc();

it("debounces for two seconds and keeps a refusal paused until an explicit save", async () => {
  vi.useFakeTimers();
  const accepted = vi.fn(); const refused = vi.fn();
  const calls = fakeApi({ [`PUT ${url}`]: () => refusal("CONCURRENT_MODIFICATION", "This record changed.", 409) });
  const { result } = renderHook(() => useFillAutosave({
    owner, grid, initial, fill: changed("1"), version: 7, dirty: true, enabled: true, accepted, refused,
  }));
  await act(async () => { await vi.advanceTimersByTimeAsync(1999); });
  expect(calls).toHaveLength(0);
  await act(async () => { await vi.advanceTimersByTimeAsync(1); });
  expect(refused).toHaveBeenCalledWith(changed("1"), expect.objectContaining({ message: "This record changed." }));
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(calls).toHaveLength(1);
  expect(accepted).not.toHaveBeenCalled();
  await act(async () => { await result.current.flush(true); });
  expect(calls).toHaveLength(2);
});

it("serializes edits made during a save and uses the returned version for the next request", async () => {
  vi.useFakeTimers();
  let release: (response: Response) => void = () => {};
  const versions: string[] = [];
  const calls = fakeApi({ [`PUT ${url}`]: (request) => {
    versions.push(new URL(request.url).search);
    return versions.length === 1 ? new Promise<Response>((resolve) => { release = resolve; })
      : json({ data: { version: 9 } });
  } });
  const accepted = vi.fn(); const refused = vi.fn();
  const { result, rerender } = renderHook(({ fill }) => useFillAutosave({
    owner, grid, initial, fill, version: 7, dirty: true, enabled: true, accepted, refused,
  }), { initialProps: { fill: changed("1") } });
  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  expect(result.current.saving).toBe(true);
  rerender({ fill: changed("2") });
  await act(async () => { release(json({ data: { version: 8 } })); await vi.advanceTimersByTimeAsync(1); });
  expect(versions).toEqual(["?version=7", "?version=8"]);
  expect(calls).toHaveLength(2);
  expect(accepted).toHaveBeenLastCalledWith(changed("2"), expect.objectContaining({ version: 9 }));
  expect(result.current.saved).toBe(true);
});

it("never autosaves when the lifecycle disables it", async () => {
  vi.useFakeTimers();
  const calls = fakeApi();
  const { result } = renderHook(() => useFillAutosave({ owner, grid, initial, fill: changed("1"), version: 7,
    dirty: true, enabled: false, accepted: vi.fn(), refused: vi.fn() }));
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  await act(async () => { expect(await result.current.flush()).toBe(false); });
  expect(calls).toHaveLength(0);
});

it("allows an explicit correction Save when autosave is disabled", async () => {
  const calls = fakeApi({ [`PUT ${url}`]: () => json({ data: { version: 8 } }) });
  const { result } = renderHook(() => useFillAutosave({ owner, grid, initial, fill: changed("1"), version: 7,
    dirty: true, enabled: false, accepted: vi.fn(), refused: vi.fn() }));
  await act(async () => { expect(await result.current.flush(true)).toBe(true); });
  expect(calls).toHaveLength(1);
});
