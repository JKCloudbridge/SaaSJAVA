-- Organization-specific number formats (FA6B, ADR-0197). Existing declarations and counters remain the base.
create table numbering_format (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    series_key      text        not null,
    setting_kind    text        not null,
    selector_value  text        not null default '',
    format_text     text        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint numbering_format_series_shape check (series_key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint numbering_format_kind check (setting_kind in ('CATEGORY', 'DEFAULT', 'WITHOUT_OPTIONAL')),
    constraint numbering_format_selector_shape check (selector_value ~ '^[A-Za-z0-9_-]{0,10}$'),
    constraint numbering_format_default_selector check (setting_kind = 'CATEGORY' or selector_value = ''),
    constraint numbering_format_text_bound check (length(format_text) between 1 and 100)
);

create unique index numbering_format_tenant_setting
    on numbering_format (tenant_id, series_key, setting_kind, selector_value) where deleted_at is null;
create trigger numbering_format_row_guard before insert or update on numbering_format
    for each row execute function platform_row_guard();
create trigger numbering_format_tenant_guard before update on numbering_format
    for each row execute function platform_tenant_guard();
alter table numbering_format enable row level security;
alter table numbering_format force row level security;
create policy numbering_format_insert on numbering_format for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy numbering_format_select on numbering_format for select
    using (tenant_id = (select platform_current_tenant()));
create policy numbering_format_update on numbering_format for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy numbering_format_delete on numbering_format for delete
    using (tenant_id = (select platform_current_tenant()));

-- Named fields distinguish new scopes even when different fields contain identical codes. Existing keys stay valid.
-- (The pattern has no repeat count: PostgreSQL allows at most 255, so the length is checked on its own.)
alter table number_series_counter drop constraint number_series_counter_scope_shape;
alter table number_series_counter add constraint number_series_counter_scope_shape
    check (length(scope_key) <= 512 and scope_key ~ '^[A-Za-z0-9_/-]*$');

comment on table numbering_format is
    'Organization overrides of closed number formats. Read afresh under the series lock; no cache (ADR-0197).';
