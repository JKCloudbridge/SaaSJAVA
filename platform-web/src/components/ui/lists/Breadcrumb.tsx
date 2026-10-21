import Link from "next/link";
import { Fragment } from "react";

/** One step of a breadcrumb: a link, a button that goes back in the same pane, or (the last one) the page itself. */
export interface Crumb {
  label: string;
  href?: string;
  onClick?: () => void;
}

/**
 * The trail above a title (ADR-0168, UA3): every step but the last takes the person back, the last one is the page
 * they are on. It draws what it is given and decides nothing.
 */
export function Breadcrumb({ crumbs }: { crumbs: readonly Crumb[] }) {
  return (
    <nav className="breadcrumb" aria-label="Breadcrumb">
      {crumbs.map((crumb, index) => {
        const last = index === crumbs.length - 1;
        return (
          <Fragment key={`${index}-${crumb.label}`}>
            {index > 0 ? <span aria-hidden="true">/</span> : null}
            {last ? (
              <span aria-current="page">{crumb.label}</span>
            ) : crumb.href ? (
              <Link className="breadcrumb-link" href={crumb.href}>
                {crumb.label}
              </Link>
            ) : (
              <button type="button" className="breadcrumb-link" onClick={crumb.onClick}>
                {crumb.label}
              </button>
            )}
          </Fragment>
        );
      })}
    </nav>
  );
}
