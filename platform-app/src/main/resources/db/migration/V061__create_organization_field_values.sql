-- The organization's own list of values for a picklist the platform defines (Functional track FA2A, ADR-0182). Expand
-- only; nothing is removed or renamed.
--
--   organization_field_values  One row per organization, object and field: the values (and, for a dependent picklist, the
--                              pairs) the organization's administrator chose for a platform-defined picklist that is
--                              flagged "organizationValues" in its definition file. The platform ships a starting list in
--                              the file; it is used for an organization that has no row, or whose row is empty. The row is
--                              merged into the metadata snapshot when it is built, so it lives and dies with the metadata
--                              version (ADR-0061): the trigger below raises the version in the same transaction as every
--                              change, exactly as for the organization's own fields.
--
-- A platform field is a file and has no row of its own, which is why this table exists. It holds the same JSON the
-- configuration of an organization's own picklist holds ({"values": [...], "dependency": {...}}), or {} for "use the
-- platform's starting list" (a reset keeps the row so that its version only ever goes up).
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * one live row per organization, object and field (partial unique index; it also leads with the tenant);
--   * names are shaped like the API names of the platform's own definitions, and the text is bounded.
-- It feeds no answer of the permission module, so it needs no security-version trigger.

create table organization_field_values (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name  text       not null,
    field_api_name   text       not null,
    config           jsonb       not null default '{}'::jsonb,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint organization_field_values_object_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9]{0,39}$'),
    constraint organization_field_values_field_shape check (field_api_name ~ '^[a-z][A-Za-z0-9]{0,39}$'),
    constraint organization_field_values_config_is_object check (jsonb_typeof(config) = 'object'
        and octet_length(config::text) <= 1000000)
);

create unique index organization_field_values_tenant_field
    on organization_field_values (tenant_id, object_api_name, field_api_name) where deleted_at is null;

create trigger organization_field_values_row_guard before insert or update on organization_field_values
    for each row execute function platform_row_guard();
create trigger organization_field_values_tenant_guard before update on organization_field_values
    for each row execute function platform_tenant_guard();
create trigger organization_field_values_metadata_version after insert or update or delete on organization_field_values
    for each row execute function platform_metadata_version_bump();

alter table organization_field_values enable row level security;
alter table organization_field_values force row level security;
create policy organization_field_values_insert on organization_field_values for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy organization_field_values_select on organization_field_values for select
    using (tenant_id = (select platform_current_tenant()));
create policy organization_field_values_update on organization_field_values for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy organization_field_values_delete on organization_field_values for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table organization_field_values is
    'The organization''s own values (and pairs) for a platform-defined picklist flagged organizationValues. Published metadata; {} means the platform''s starting list (ADR-0182).';
