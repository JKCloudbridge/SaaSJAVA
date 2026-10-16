-- Which record page an object uses for a profile and a record type (Sprint 20, ADR-0131). Shaped exactly like
-- layout_assignment (V034), reusing its guard and version-bump functions: the identity of an assignment never
-- changes, and any table of a custom object must name one that still exists. Unlike a layout, a record type carries
-- no record page of its own, so the record-type-alone rule is also a row here (profile_id null, record_type_api_name
-- set) instead of a property of record_type.

create table record_page_assignment (
    id                    uuid        primary key default uuidv7(),
    tenant_id             uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name       text        not null,
    profile_id            uuid,
    record_type_api_name  text,
    record_page_api_name  text        not null,
    version               bigint      not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid        not null,
    updated_at            timestamptz not null default now(),
    updated_by            uuid        not null,
    deleted_at            timestamptz,
    deleted_by            uuid,
    constraint record_page_assignment_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_page_assignment_page_name_shape check (record_page_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint record_page_assignment_record_type_shape
        check (record_type_api_name is null or record_type_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$')
);

-- One assignment per object, profile and record type; a missing profile or record type is part of the key (a
-- record-type-alone rule and the default rule are both "no profile", told apart by the record type column).
create unique index record_page_assignment_tenant_key
    on record_page_assignment (tenant_id, lower(object_api_name),
        coalesce(profile_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(coalesce(record_type_api_name, ''))) where deleted_at is null;

create trigger record_page_assignment_row_guard before insert or update on record_page_assignment
    for each row execute function platform_row_guard();
create trigger record_page_assignment_tenant_guard before update on record_page_assignment
    for each row execute function platform_tenant_guard();
create trigger record_page_assignment_identity_guard before insert or update on record_page_assignment
    for each row execute function platform_definition_identity_guard();
create trigger record_page_assignment_metadata_version after insert or update or delete on record_page_assignment
    for each row execute function platform_metadata_version_bump();

alter table record_page_assignment enable row level security;
alter table record_page_assignment force row level security;
create policy record_page_assignment_insert on record_page_assignment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_page_assignment_select on record_page_assignment for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_page_assignment_update on record_page_assignment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_page_assignment_delete on record_page_assignment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_page_assignment is
    'Which record page an object uses for a profile and a record type (Sprint 20, ADR-0131).';
