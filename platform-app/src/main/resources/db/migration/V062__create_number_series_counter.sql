-- The counters of number series (Functional track FA2A, ADR-0176, ADR-0184). Expand only; nothing is removed or renamed.
--
--   number_series_counter  One row per organization, series and scope: the next number to hand out. A series is declared
--                          by a shipped solution (a closed format of fixed text, values of the record and one counter);
--                          the scope is the value that chooses the format followed by the values of the fields the format
--                          reads, joined with slashes (for example 'WI/PRD'). A scope that was never numbered has no row
--                          and starts at 1, so a newly added abbreviation starts again at one by itself.
--
-- Taking a number is one statement that makes the row or moves it on and returns the number; the row stays locked until
-- the transaction ends, so two instances numbering one scope at once queue, and different scopes do not wait for each
-- other. A number taken in a transaction that rolls back is never issued (the counter goes back with it); a number that
-- was issued is never issued again. The field that holds the number is unique, which is the backstop (ADR-0111).
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * one live row per organization, series and scope (partial unique index; it also leads with the tenant);
--   * the key, the scope and the counter are shaped: a scope cannot hold anything but codes and slashes.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. A scope holds codes of the organization, so it is never written to a log, an audit record or an event.

create table number_series_counter (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    series_key  text        not null,
    scope_key   text        not null,
    next_value  bigint      not null,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint number_series_counter_key_shape check (series_key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint number_series_counter_scope_shape check (scope_key ~ '^[A-Za-z0-9_/-]{0,100}$'),
    constraint number_series_counter_next_positive check (next_value >= 2)
);

create unique index number_series_counter_tenant_scope
    on number_series_counter (tenant_id, series_key, scope_key) where deleted_at is null;

create trigger number_series_counter_row_guard before insert or update on number_series_counter
    for each row execute function platform_row_guard();
create trigger number_series_counter_tenant_guard before update on number_series_counter
    for each row execute function platform_tenant_guard();

alter table number_series_counter enable row level security;
alter table number_series_counter force row level security;
create policy number_series_counter_insert on number_series_counter for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy number_series_counter_select on number_series_counter for select
    using (tenant_id = (select platform_current_tenant()));
create policy number_series_counter_update on number_series_counter for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy number_series_counter_delete on number_series_counter for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table number_series_counter is
    'The next number of a number series for one scope of one organization. Taken with one upsert, locked until commit, never reused (ADR-0176, ADR-0184).';
