"use client";

/**
 * The keyboard and touch way to do what a drag does: move an item one place up or down, and take it out. Each button
 * names the item, so a screen reader hears "Move Account up", not "Move up".
 */
export function MoveButtons({
  name,
  onUp,
  onDown,
  onRemove,
  upLabel = "up",
  downLabel = "down",
}: {
  name: string;
  onUp?: () => void;
  onDown?: () => void;
  onRemove?: () => void;
  upLabel?: string;
  downLabel?: string;
}) {
  return (
    <span className="move-buttons">
      {onUp || onDown ? (
        <>
          <button type="button" disabled={!onUp} onClick={onUp} aria-label={`Move ${name} ${upLabel}`}>
            ↑
          </button>
          <button type="button" disabled={!onDown} onClick={onDown} aria-label={`Move ${name} ${downLabel}`}>
            ↓
          </button>
        </>
      ) : null}
      {onRemove ? (
        <button type="button" onClick={onRemove} aria-label={`Take out ${name}`}>
          ✕
        </button>
      ) : null}
    </span>
  );
}
