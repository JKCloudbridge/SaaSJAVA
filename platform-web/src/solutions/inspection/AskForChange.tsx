"use client";

import { useState } from "react";
import { createPortal } from "react-dom";
import { ActionNotice } from "@/components/ui/ActionNotice";
import { Modal } from "@/components/ui/Modal";
import type { ChangeRequestsView } from "./changeRequests";
import { RequestPanel, useRequestDraft } from "./RequestPanel";

/**
 * The way in to asking for a change (FA6D; FA10): a plain button in the toolbar of the Sheet tab that opens the request as a
 * popup. A request is the register alone; it points at no place on the sheet.
 *
 * <p>What was written is kept here, outside the popup, so closing the popup never throws it away: if something unsent
 * is there, a close asks first whether to keep it for later or drop it. Nothing here decides who may ask: the screen only
 * draws this when the API says so, and the server judges every request again.
 */
export function AskForChange({
  templateId,
  versionNo,
  view,
  onSent,
  toolbarSlot,
}: {
  templateId: string;
  /** The revision in use that the request is about. */
  versionNo: number;
  view: ChangeRequestsView | undefined;
  onSent(): void;
  /** Where the button is drawn (the row of the tabs); without it the button is drawn where this is placed. */
  toolbarSlot?: HTMLElement | null;
}) {
  const draft = useRequestDraft();
  const [open, setOpen] = useState(false);
  const [asking, setAsking] = useState(false);
  const [sent, setSent] = useState(0);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="request-unavailable">
        {view.inProgress
          ? "A change is already in progress on this template. You can ask for another when it has been published or declined."
          : "Nobody can ask for a change to this template right now."}
      </p>
    );
  }

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
      <button type="button" className="button" onClick={() => setOpen(true)} data-testid="ask-for-change">
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
            <RequestPanel
              templateId={templateId}
              versionNo={versionNo}
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
