/**
 * The page of the "Templates" entry (Functional track FA1). It draws no data on purpose: the framework ships first, and the
 * master list arrives with the lifecycle sprint. Nothing here asks the API, so it decides and shows nothing about the
 * member's rights; the entry that leads here was listed by the API for a member who may read the templates.
 */
export function TemplatesPage() {
  return (
    <div className="empty-state" role="status" data-testid="solution-page-placeholder">
      <p>
        <strong>Templates: nothing here yet.</strong>
      </p>
    </div>
  );
}
