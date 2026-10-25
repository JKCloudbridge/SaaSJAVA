"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ActionNotice } from "@/components/ui/ActionNotice";
import { Modal } from "@/components/ui/Modal";
import type { AnnotateContext, Place } from "@/lib/sheets/annotate";
import { AnnotatePanel, useAnnotateDraft } from "./AnnotatePanel";
import type { ChangeRequestsView } from "./changeRequests";

/**
 * The way in to asking for a change (FA6D): a button in the sheet's toolbar that opens the request as a popup. The person
 * stands on a place in the grid first (the popup cannot be pointed through, since the shared popup covers the page) and
 * the popup says which place that is. Pressing Enter on a cell opens it too.
 *
 * <p>What was written is kept here, outside the popup, so closing the popup never throws it away: if something unsent
 * is there, a close asks first whether to keep it for later or drop it. A place a comment in the list asked to be shown is
 * stood on in the grid. Nothing here decides who may ask: the screen only draws this when the API says so, and the server
 * judges every request again.
 */
export function AskForChange({
  templateId,
  context,
  view,
  show,
  onShown,
  onSent,
  toolbarSlot,
}: {
  templateId: string;
  context: AnnotateContext;
  view: ChangeRequestsView | undefined;
  /** A place a comment in the list asked to be shown: the grid is stood on it, then `onShown` is called. */
  show: Place | null;
  onShown(): void;
  onSent(): void;
  /** Where the button is drawn (the row of the tabs); without it the button is drawn where this is placed. */
  toolbarSlot?: HTMLElement | null;
}) {
  const draft = useAnnotateDraft();
  const [open, setOpen] = useState(false);
  const [asking, setAsking] = useState(false);
  const [sent, setSent] = useState(0);

  // Enter on a cell of the grid opens the popup, standing the comment on that cell.
  const seen = useRef(context.activations);
  useEffect(() => {
    if (context.activations === seen.current) {
      return;
    }
    seen.current = context.activations;
    // In a microtask, like the other screens that react to the page around them, so that no state is set while the effect runs.
    void Promise.resolve().then(() => {
      draft.setTarget(context.places.cell ? "cell" : "column");
      setOpen(true);
    });
    // `draft` is a new object on every render; only a press of Enter should run this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [context.activations, context.places.cell]);

  const { select } = context;
  useEffect(() => {
    if (show) {
      void Promise.resolve().then(() => {
        select(show);
        onShown();
      });
    }
    // `select` is a new function on every render of the grid; only a place asked for should run this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [show]);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="annotate-unavailable">
        Nobody can ask for a change to this template right now.
      </p>
    );
  }

  const openIt = () => {
    // Standing on a cell means asking about it; otherwise the popup offers the column and the whole template.
    if (!draft.dirty) {
      draft.setTarget(context.places.cell ? "cell" : context.places.column ? "column" : "template");
    }
    setOpen(true);
  };

  const close = () => {
    if (draft.dirty && !asking) {
      setAsking(true);
      return;
    }
    setAsking(false);
    setOpen(false);
  };

  const button = (
    <>
      <button type="button" className="button" onClick={openIt} data-testid="ask-for-change">
        Ask for a change
      </button>
      {sent > 0 ? (
        <ActionNotice
          key={sent}
          text="Your request was sent to the people who look after this template."
          className="hint"
          occurrence={sent}
          testId="ask-for-change-sent"
        />
      ) : null}
    </>
  );

  return (
    <>
      {toolbarSlot === undefined ? button : toolbarSlot ? createPortal(button, toolbarSlot) : null}
      {open ? (
        <Modal title="Ask for a change" onClose={close} testId="ask-for-change-popup">
          {asking ? (
            <div className="stack" role="alertdialog" aria-label="You have not sent your request" data-testid="ask-for-change-confirm">
              <p>You have not sent your request. What should happen to what you wrote?</p>
              <div className="split">
                <button
                  type="button"
                  className="button"
                  autoFocus
                  onClick={() => {
                    setAsking(false);
                    // Back to the box that was being written in, once it is shown again.
                    void Promise.resolve().then(() =>
                      document.querySelector<HTMLElement>('[data-testid="ask-for-change-popup"] textarea')?.focus(),
                    );
                  }}
                >
                  Keep writing
                </button>
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => {
                    setAsking(false);
                    setOpen(false);
                  }}
                >
                  Close and keep my words
                </button>
                <button
                  type="button"
                  className="button button-danger"
                  onClick={() => {
                    draft.clear();
                    setAsking(false);
                    setOpen(false);
                  }}
                >
                  Discard my words
                </button>
              </div>
            </div>
          ) : null}
          <div hidden={asking}>
            <AnnotatePanel
              templateId={templateId}
              context={context}
              view={view}
              draft={draft}
              onSent={() => {
                setOpen(false);
                setAsking(false);
                setSent((now) => now + 1);
                onSent();
              }}
            />
          </div>
        </Modal>
      ) : null}
    </>
  );
}
