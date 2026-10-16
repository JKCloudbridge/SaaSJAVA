"use client";

import { useState, type DragEvent, type ElementType, type ReactNode } from "react";
import { carriesDrag, readDrag, type DragPayload } from "@/lib/builder/dragPayload";

/**
 * A place something can be dropped. It only answers "what was dropped here": the builder that owns the draft decides what
 * that changes. While a builder drag is over it, it gets the class `drag-over` (the dashed outline); a drag from
 * anywhere else (a file, a link) is not accepted, so the browser's own behaviour stays for those.
 *
 * A drop is handled by the innermost zone only, so a chip inside a card can mean "before this chip" while the card
 * around it means "at the end".
 */
export function DropZone({
  as: Tag = "div",
  className,
  onDropPayload,
  children,
  ...rest
}: {
  as?: ElementType;
  className?: string;
  onDropPayload: (payload: DragPayload) => void;
  children?: ReactNode;
  [attribute: string]: unknown;
}) {
  const [over, setOver] = useState(false);

  function allow(event: DragEvent) {
    if (carriesDrag(event)) {
      event.preventDefault();
      event.stopPropagation();
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = "move";
      }
      setOver(true);
    }
  }

  return (
    <Tag
      {...rest}
      className={[className, over ? "drag-over" : ""].filter(Boolean).join(" ")}
      onDragEnter={allow}
      onDragOver={allow}
      onDragLeave={(event: DragEvent) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setOver(false);
        }
      }}
      onDrop={(event: DragEvent) => {
        setOver(false);
        const payload = readDrag(event);
        if (payload) {
          event.preventDefault();
          event.stopPropagation();
          onDropPayload(payload);
        }
      }}
    >
      {children}
    </Tag>
  );
}
