/**
 * What a builder carries while something is dragged. The drag state itself is user-interface state and never leaves the
 * browser: a drop only turns into a change of the draft, and the draft is what is saved (ADR-0132).
 *
 * The payload is read only at the drop. During the drag the browser lets a page see the types of the data, not the data,
 * so highlighting a target uses {@link carriesDrag}.
 */
export const DRAG_MIME = "application/x-platform-builder";

export type DragPayload =
  /** A chip of the palette: `key` names it inside the builder that made it. */
  | { origin: "palette"; key: string }
  /** An item already placed: the group it sits in and its position there. */
  | { origin: "canvas"; group: string; index: number };

interface DragEventLike {
  dataTransfer: DataTransfer | null;
}

export function startDrag(event: DragEventLike, payload: DragPayload): void {
  const transfer = event.dataTransfer;
  if (!transfer) {
    return;
  }
  transfer.effectAllowed = "move";
  transfer.setData(DRAG_MIME, JSON.stringify(payload));
  // Some browsers refuse to start a drag that carries no plain text.
  transfer.setData("text/plain", "");
}

export function carriesDrag(event: DragEventLike): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes(DRAG_MIME);
}

/** The payload of a drop, or nothing when the drop did not come from a builder or is not in the expected shape. */
export function readDrag(event: DragEventLike): DragPayload | undefined {
  const raw = event.dataTransfer?.getData(DRAG_MIME);
  if (!raw) {
    return undefined;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) {
      return undefined;
    }
    const candidate = value as Record<string, unknown>;
    if (candidate.origin === "palette" && typeof candidate.key === "string") {
      return { origin: "palette", key: candidate.key };
    }
    if (candidate.origin === "canvas" && typeof candidate.group === "string" && Number.isInteger(candidate.index)) {
      return { origin: "canvas", group: candidate.group, index: candidate.index as number };
    }
    return undefined;
  } catch {
    return undefined;
  }
}
