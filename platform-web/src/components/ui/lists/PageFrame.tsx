import { useId, type ReactNode } from "react";

export interface PageFrameProps {
  title: string;
  /** Stands in the title's place (the view menu of a list); the title still names the page for assistive technology. */
  titleControl?: ReactNode;
  /** A status chip or two drawn beside the title. */
  titleExtra?: ReactNode;
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
export function PageFrame({ title, titleControl, titleExtra, breadcrumb, tools, notices, children, testId }: PageFrameProps) {
  const headingId = useId();
  return (
    <section
      className="page-frame"
      aria-labelledby={titleControl ? undefined : headingId}
      aria-label={titleControl ? title : undefined}
      data-testid={testId}
    >
      <header className="page-head">
        {breadcrumb}
        <div className="page-head-row">
          <h1 id={headingId}>{titleControl ?? title}</h1>
          {titleExtra ? <div className="page-head-extra">{titleExtra}</div> : null}
          {tools ? <div className="page-head-tools">{tools}</div> : null}
        </div>
      </header>
      <div className="page-body">
        {notices}
        {children}
      </div>
    </section>
  );
}
