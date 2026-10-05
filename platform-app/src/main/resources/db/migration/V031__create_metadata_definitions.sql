-- Object and field definitions of an organization (Sprint 10, ADR-0058 to ADR-0062).
--
-- A custom object is a kind of record an organization defines for itself (Employee, Vehicle), and a custom field is a
-- value such an object, or a standard object, carries (a salary, a plate number). Both are METADATA: rows that say what
-- exists, never the business data itself (records arrive in Milestone 4).
--
-- What is NOT in the database: the standard objects (Account, Contact, User ...) and their fields. They belong to the
-- platform, are the same for every organization and are read from versioned files inside the application (ADR-0059), so
-- no tenant, and no bug in the tenant-facing code, has a way to write them.
--
-- Names. Every object and field has a LABEL (what people read, changeable) and an API NAME (permanent, used by code and
-- integrations). An organization's own names always end in '__c', which a platform name never does, so a standard object
-- or field added in a later release can never clash with something an organization already made (ADR-0058). Object names
-- start with an upper case letter ('Employee__c'), field names with a lower case letter ('salary__c'). The same name is
-- the key of permissions on data (object_permission, field_permission), see V032.
--
-- Three tables, all tenant-scoped (ADR-0015): object_definition, field_definition and metadata_version. Every table has
-- tenant_id, row level security enabled and forced, a policy on platform_current_tenant(), the tenant guard trigger and a
-- tenant-first index. A field names its object by API NAME and not by identifier, because the object may be a standard one
-- that has no row; the guard below checks the custom case.
--
-- What must hold even if the application had a bug:
--   * an API name, the object of a field and the data type of a field never change after the row exists;
--   * a field of a custom object names a live custom object of the same organization;
--   * an object is not removed while it still has a live field (the service removes the fields first);
--   * names are unique in an organization without regard to upper or lower case.
--
-- The version counters. metadata_version holds one number per organization that goes up, in the same transaction, with
-- every change of these two tables; the cache of the object catalogue keeps its snapshot together with that number and
-- serves it only while the number is unchanged (ADR-0061). The same two tables also raise the SECURITY version of V028,
-- because what a member may do with an object or field depends on which objects and fields exist.

-- ---------------------------------------------------------------------------------------------------------------
-- tables
-- ---------------------------------------------------------------------------------------------------------------

create table object_definition (
    id           uuid        primary key default uuidv7(),
    tenant_id    uuid        not null default platform_current_tenant() references tenant (id),
    api_name     text        not null,
    label        text        not null,
    plural_label text        not null,
    description  text        not null default '',
    version      bigint      not null default 0,
    created_at   timestamptz not null default now(),
    created_by   uuid        not null,
    updated_at   timestamptz not null default now(),
    updated_by   uuid        not null,
    deleted_at   timestamptz,
    deleted_by   uuid,
    constraint object_definition_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint object_definition_label_length check (char_length(label) between 1 and 80),
    constraint object_definition_plural_label_length check (char_length(plural_label) between 1 and 80),
    constraint object_definition_description_length check (char_length(description) <= 500)
);

create unique index object_definition_tenant_api_name on object_definition (tenant_id, lower(api_name))
    where deleted_at is null;

create table field_definition (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    data_type       text        not null,
    required        boolean     not null default false,
    is_unique       boolean     not null default false,
    default_value   text,
    config          jsonb       not null default '{}'::jsonb,
    sort_order      integer     not null default 0,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint field_definition_api_name_shape
        check (api_name ~ '^[a-z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint field_definition_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint field_definition_label_length check (char_length(label) between 1 and 80),
    constraint field_definition_description_length check (char_length(description) <= 500),
    constraint field_definition_type_known check (data_type in (
        'TEXT', 'LONG_TEXT', 'NUMBER', 'DECIMAL', 'CURRENCY', 'PERCENT', 'BOOLEAN', 'DATE', 'DATETIME', 'TIME',
        'EMAIL', 'PHONE', 'URL', 'PICKLIST', 'MULTI_PICKLIST', 'LOOKUP', 'MASTER_DETAIL', 'FORMULA',
        'AUTO_NUMBER')),
    constraint field_definition_default_length check (default_value is null or char_length(default_value) <= 4000),
    constraint field_definition_config_is_object check (jsonb_typeof(config) = 'object')
);

create unique index field_definition_tenant_object_api_name
    on field_definition (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;
create index field_definition_tenant_object on field_definition (tenant_id, lower(object_api_name), sort_order)
    where deleted_at is null;

create table metadata_version (
    id         uuid        primary key default uuidv7(),
    tenant_id  uuid        not null default platform_current_tenant() references tenant (id),
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid
);

create unique index metadata_version_tenant on metadata_version (tenant_id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards
-- ---------------------------------------------------------------------------------------------------------------

create function platform_object_definition_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.api_name is distinct from old.api_name then
            raise exception 'object guard: the API name of an object never changes' using errcode = 'check_violation';
        end if;
        if new.deleted_at is not null and old.deleted_at is null and exists (
                select 1 from field_definition f
                 where f.tenant_id = old.tenant_id and lower(f.object_api_name) = lower(old.api_name)
                   and f.deleted_at is null) then
            raise exception 'object guard: an object with live fields cannot be removed'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_object_definition_guard() is
    'The API name of an object never changes and an object with live fields is not removed (ADR-0058).';

create function platform_field_definition_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.api_name is distinct from old.api_name
            or new.object_api_name is distinct from old.object_api_name
            or new.data_type is distinct from old.data_type) then
        raise exception 'field guard: the API name, the object and the data type of a field never change'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        return new;
    end if;
    -- A name ending in __c is a custom object, which must exist here. Anything else is a standard object, which is
    -- known to the application only (ADR-0059): the service checks it.
    if new.object_api_name like '%\_\_c' and not exists (
            select 1 from object_definition o
             where o.tenant_id = new.tenant_id and lower(o.api_name) = lower(new.object_api_name)
               and o.deleted_at is null) then
        raise exception 'field guard: the object must be a custom object of this organization'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_field_definition_guard() is
    'The identity of a field never changes and a field of a custom object belongs to a live custom object of the same organization (ADR-0058).';

create function platform_metadata_version_bump() returns trigger
    language plpgsql
as $bump$
declare
    organization uuid;
begin
    organization := case when tg_op = 'DELETE' then old.tenant_id else new.tenant_id end;
    insert into metadata_version (tenant_id, created_by, updated_by)
        values (organization, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
        on conflict (tenant_id) where deleted_at is null
        do update set version = metadata_version.version + 1,
                      updated_by = '00000000-0000-0000-0000-000000000000';
    return null;
end;
$bump$;

comment on function platform_metadata_version_bump() is
    'Adds one to the metadata version of the changed row''s organization (ADR-0061). Attached as <table>_metadata_version to the definition tables.';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger object_definition_row_guard before insert or update on object_definition
    for each row execute function platform_row_guard();
create trigger object_definition_tenant_guard before update on object_definition
    for each row execute function platform_tenant_guard();
create trigger object_definition_identity_guard before insert or update on object_definition
    for each row execute function platform_object_definition_guard();
create trigger object_definition_metadata_version after insert or update or delete on object_definition
    for each row execute function platform_metadata_version_bump();
create trigger object_definition_security_version after insert or update or delete on object_definition
    for each row execute function platform_security_version_bump();

create trigger field_definition_row_guard before insert or update on field_definition
    for each row execute function platform_row_guard();
create trigger field_definition_tenant_guard before update on field_definition
    for each row execute function platform_tenant_guard();
create trigger field_definition_identity_guard before insert or update on field_definition
    for each row execute function platform_field_definition_guard();
create trigger field_definition_metadata_version after insert or update or delete on field_definition
    for each row execute function platform_metadata_version_bump();
create trigger field_definition_security_version after insert or update or delete on field_definition
    for each row execute function platform_security_version_bump();

create trigger metadata_version_row_guard before insert or update on metadata_version
    for each row execute function platform_row_guard();
create trigger metadata_version_tenant_guard before update on metadata_version
    for each row execute function platform_tenant_guard();

alter table object_definition enable row level security;
alter table object_definition force row level security;
create policy object_definition_insert on object_definition for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy object_definition_select on object_definition for select
    using (tenant_id = (select platform_current_tenant()));
create policy object_definition_update on object_definition for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy object_definition_delete on object_definition for delete
    using (tenant_id = (select platform_current_tenant()));

alter table field_definition enable row level security;
alter table field_definition force row level security;
create policy field_definition_insert on field_definition for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy field_definition_select on field_definition for select
    using (tenant_id = (select platform_current_tenant()));
create policy field_definition_update on field_definition for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy field_definition_delete on field_definition for delete
    using (tenant_id = (select platform_current_tenant()));

alter table metadata_version enable row level security;
alter table metadata_version force row level security;
create policy metadata_version_insert on metadata_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_version_select on metadata_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy metadata_version_update on metadata_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_version_delete on metadata_version for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table object_definition is
    'A custom object an organization defines for itself. Standard objects are not here (ADR-0059). Tenant-scoped (ADR-0015, ADR-0058).';
comment on table field_definition is
    'A custom field of a custom or a standard object, with its type, constraints and type settings. Tenant-scoped (ADR-0015, ADR-0058, ADR-0060).';
comment on table metadata_version is
    'One counter per organization, raised in the same transaction by every change of its object and field definitions (ADR-0061). Tenant-scoped.';
