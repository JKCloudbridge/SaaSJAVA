"use client";

import { useId, type ReactNode } from "react";
import { Breadcrumb, type Crumb } from "./Breadcrumb";
import { PageFrame } from "./PageFrame";

export interface DetailPageProps {
  /** The list this page belongs to (its name in the breadcrumb) and the way back: a callback or an address. */
  listLabel: string;
  /** Steps before the list in the trail (the object a tab belongs to): "Object settings / Account / Fields / Label". */
  parents?: readonly Crumb[];
  onBack?: () => void;
  backHref?: string;
  title: string;
  /** A status chip or two beside the title. */
  titleExtra?: ReactNode;
  /** Leave the trail out: the page sits under a strip tab that already leads back to the list (the applications). */
  noBreadcrumb?: boolean;
  /** Edit or Save and the screen's own actions: the right end of the title band. */
  actions?: ReactNode;
  notices?: ReactNode;
  children?: ReactNode;
  testId?: string;
}

/**
 * A detail page in the same pane as its list (ADR-0168): a breadcrumb back to the list, the title with its buttons at the
 * right, then sections. A new item opens the same page as a form. Never a page of stacked panels.
 */
export function DetailPage(props: DetailPageProps) {
  const list: Crumb = props.backHref
    ? { label: props.listLabel, href: props.backHref }
    : { label: props.listLabel, onClick: props.onBack };
  const breadcrumb = props.noBreadcrumb ? undefined : (
    <Breadcrumb crumbs={[...(props.parents ?? []), list, { label: props.title }]} />
  );
  return (
    <PageFrame
      title={props.title}
      titleExtra={props.titleExtra}
      breadcrumb={breadcrumb}
      tools={props.actions}
      notices={props.notices}
      testId={props.testId}
    >
      {props.children}
    </PageFrame>
  );
}

/** A section of a detail page: a quiet caps title over a line, then its content. */
export function DetailSection({
  title,
  actions,
  stack,
  children,
}: {
  title: string;
  actions?: ReactNode;
  /** Space the section's children apart (a section made of several blocks). */
  stack?: boolean;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <div className="detail-section" role="group" aria-labelledby={id}>
      <div className="detail-section-head">
        <h2 id={id} className="detail-section-title">
          {title}
        </h2>
        {actions}
      </div>
      {stack ? <div className="stack">{children}</div> : children}
    </div>
  );
}

/** Label and value pairs in two columns (one on a narrow screen). A form puts its `.field` blocks in it too. */
export function DetailGrid({ children }: { children: ReactNode }) {
  return <div className="detail-grid">{children}</div>;
}

/** One label and value pair of a detail grid. */
export function DetailItem({
  label,
  children,
  wide,
  tone,
}: {
  label: string;
  children: ReactNode;
  wide?: boolean;
  tone?: "muted" | "mono";
}) {
  return (
    <div className={wide ? "detail-item span-2" : "detail-item"}>
      <div className="dl-label">{label}</div>
      <div className={tone ? `dl-value ${tone}` : "dl-value"}>{children}</div>
    </div>
  );
}

/** The page of a row that is not (or no longer) in the list: said plainly, with the way back. */
export function MissingDetail({ listLabel, onBack, what }: { listLabel: string; onBack: () => void; what: string }) {
  return (
    <DetailPage listLabel={listLabel} onBack={onBack} title="Not found">
      <p>{what}</p>
    </DetailPage>
  );
}
