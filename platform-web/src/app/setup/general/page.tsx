import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "General",
};

/**
 * The place for the organization's general settings. Nothing is built here yet: branding needs storage and an
 * endpoint, which are backend work for a later sprint. The page says so, and holds no control.
 */
export default function GeneralPage() {
  return (
    <>
      <h1>General</h1>
      <div className="empty-state" data-testid="general-placeholder">
        <p>
          <strong>Nothing to set here yet.</strong>
        </p>
        <p>The organization&apos;s branding and general settings will be found on this page once they are built.</p>
      </div>
    </>
  );
}
