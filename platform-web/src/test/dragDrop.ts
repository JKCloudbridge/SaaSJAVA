import { fireEvent } from "@testing-library/react";

/** A stand-in for the browser's drag data store (jsdom has none): the data set at the start is what the drop reads. */
export function makeTransfer(): DataTransfer {
  const store = new Map<string, string>();
  return {
    effectAllowed: "uninitialized",
    dropEffect: "none",
    get types() {
      return Array.from(store.keys());
    },
    setData: (type: string, value: string) => void store.set(type, value),
    getData: (type: string) => store.get(type) ?? "",
  } as unknown as DataTransfer;
}

/** Drags `from` and drops it on `to`, with the events a browser fires, in order. */
export function dragAndDrop(from: Element, to: Element): void {
  const dataTransfer = makeTransfer();
  fireEvent.dragStart(from, { dataTransfer });
  fireEvent.dragEnter(to, { dataTransfer });
  fireEvent.dragOver(to, { dataTransfer });
  fireEvent.drop(to, { dataTransfer });
  fireEvent.dragEnd(from, { dataTransfer });
}
