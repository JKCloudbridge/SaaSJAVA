"use client";

import type { VersionSummary } from "@/lib/sheets/api";
import { statusTag } from "@/lib/sheets/tags";
import { Tag, type TagTone } from "@/components/ui/lists/Tag";

const TONES: Record<ReturnType<typeof statusTag>["tone"], TagTone> = {
  active: "ok",
  draft: "on",
  review: "on",
  done: "quiet",
  stopped: "error",
};

/** "12.4 KB" for a size in bytes. */
export function sizeWords(bytes: number): string {
  if (bytes < 1000) {
    return `${bytes} bytes`;
  }
  return bytes < 1_000_000 ? `${(bytes / 1000).toFixed(1)} KB` : `${(bytes / 1_000_000).toFixed(2)} MB`;
}

export interface VersionListProps {
  versions: readonly VersionSummary[];
  /** The number of the version shown now. */
  current: number;
  onOpen(versionNo: number): void;
  /** Starts a new draft from a version; offered only when the caller may change the sheet and no draft exists. */
  onStartFrom?: (versionNo: number) => void;
}

/**
 * The history of a sheet: every version the member may see, newest first, with its tag ("Active" for the version in
 * use), its size and when it last changed (FA3, ADR-0174). It never shows a grid; opening a version asks for that one.
 * It draws what the API listed and decides nothing.
 */
export function VersionList({ versions, current, onOpen, onStartFrom }: VersionListProps) {
  if (versions.length === 0) {
    return <p className="hint">There is no version yet.</p>;
  }
  return (
    <table className="table" aria-label="Versions">
      <thead>
        <tr>
          <th scope="col">Version</th>
          <th scope="col">Status</th>
          <th scope="col">Size</th>
          <th scope="col">Last changed</th>
          <th scope="col">
            <span className="visually-hidden">Actions</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {versions.map((version) => {
          const tag = statusTag(version.status);
          return (
            <tr key={version.versionNo} aria-current={version.versionNo === current ? "true" : undefined}>
              <th scope="row">Version {version.versionNo}</th>
              <td>
                <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>
              </td>
              <td>{sizeWords(version.gridBytes)}</td>
              <td>
                <time dateTime={version.updatedAt}>{new Date(version.updatedAt).toLocaleString()}</time>
              </td>
              <td>
                <button type="button" className="link-button" onClick={() => onOpen(version.versionNo)}>
                  {version.versionNo === current ? `Version ${version.versionNo} is open` : `Open version ${version.versionNo}`}
                </button>
                {onStartFrom ? (
                  <button type="button" className="link-button" onClick={() => onStartFrom(version.versionNo)}>
                    New version from version {version.versionNo}
                  </button>
                ) : null}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
