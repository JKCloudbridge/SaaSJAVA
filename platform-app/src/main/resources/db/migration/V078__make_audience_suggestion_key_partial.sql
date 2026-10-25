-- Makes the key of a suggested place partial like every other unique index (FA6G, ADR-0200). Expand only: the V076
-- index is replaced by one that ignores a soft-deleted row, so a row that is gone can never block its key. Nothing
-- is removed that data depends on; the new index is built before the old one is dropped.

create unique index inspection_audience_suggestion_place_live
    on inspection_audience_suggestion (tenant_id, request_id, location, coalesce(department, ''))
    where deleted_at is null;
drop index inspection_audience_suggestion_place;
