import { useId, type ReactNode } from "react";

export interface PageFrameProps {
  title: string;
  /** A quiet sentence under the title band (what the page is for). */
  intro?: ReactNode;
  /** The trail back to the list, drawn above the title (a detail page). */
  breadcrumb?: ReactNode;
  /** The search box, the New button and the screen's own actions: the right end of the title band. */
  tools?: ReactNode;
  /** Notices and refusals of the last action, right under the band. */
  notices?: ReactNode;
  children?: ReactNode;
  testId?: string;
}

/**
 * The frame of every Home screen: a quiet title band (breadcrumb, title, tools), then the body. A list, a detail page and
 * a page that is neither (counts, a timeline) all sit in it, so they look and behave alike (ADR-0168). It draws what it is
 * given and decides nothing.
 */
export function PageFrame({ title, intro, breadcrumb, tools, notices, children, testId }: PageFrameProps) {
  const headingId = useId();
  return (
    <section className="page-frame" aria-labelledby={headingId} data-testid={testId}>
      <header className="page-head">
        {breadcrumb}
        <div className="page-head-row">
          <h1 id={headingId}>{title}</h1>
          {tools ? <div className="page-head-tools">{tools}</div> : null}
        </div>
      </header>
      <div className="page-body">
        {intro ? <p className="page-intro">{intro}</p> : null}
        {notices}
        {children}
      </div>
    </section>
  );
}
