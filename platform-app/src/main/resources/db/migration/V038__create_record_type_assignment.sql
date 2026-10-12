-- Which profiles may use a record type (Sprint 15A, ADR-0098).
--
-- A record type (ADR-0064) is a variant of an object. Until now every member who may create in the object could choose
-- any type that is in use. This table lets an organization say which profiles may use which types, and which type a
-- profile gets when a new record names none.
--
--   record_type_assignment  One row says "this profile may use this record type of this object". The rule (ADR-0098):
--                           a record type with NO row is open to every profile, so everything built before this
--                           migration keeps working; with at least one row it is open only to the profiles named.
--                           is_default marks the type a new record of that profile gets when it names none; a profile
--                           has at most one default per object.
--
-- Like a layout assignment (ADR-0071) this is published metadata: it changes only through the change-set lifecycle,
-- raises the metadata version, and the profile is only an identifier with no foreign key, so deleting a profile is never
-- blocked by an assignment (a profile that no longer exists is never anyone's profile, so its rows simply never apply).
--
-- What must hold even if the application had a bug:
--   * the object, record type and profile of an assignment never change (the definition guard of V034);
--   * one assignment per object, record type and profile, and one default per object and profile (partial unique
--     indexes, tenant first);
--   * a default assignment is an assignment (it is a row), and a tenant sees and changes only its own rows (forced row
--     level security).

create table record_type_assignment (
    id                   uuid        primary key default uuidv7(),
    tenant_id            uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name      text        not null,
    record_type_api_name text        not null,
    profile_id           uuid        not null,
    is_default           boolean     not null default false,
    version              bigint      not null default 0,
    created_at           timestamptz not null default now(),
    created_by           uuid        not null,
    updated_at           timestamptz not null default now(),
    updated_by           uuid        not null,
    deleted_at           timestamptz,
    deleted_by           uuid,
    constraint record_type_assignment_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_type_assignment_record_type_shape
        check (record_type_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$')
);

-- One assignment per object, record type and profile.
create unique index record_type_assignment_tenant_key
    on record_type_assignment (tenant_id, lower(object_api_name), lower(record_type_api_name), profile_id)
    where deleted_at is null;

-- One default per object and profile.
create unique index record_type_assignment_tenant_default
    on record_type_assignment (tenant_id, lower(object_api_name), profile_id)
    where is_default and deleted_at is null;

-- The rows of an object, which is how the catalogue reads them.
create index record_type_assignment_object
    on record_type_assignment (tenant_id, lower(object_api_name)) where deleted_at is null;

create trigger record_type_assignment_row_guard before insert or update on record_type_assignment
    for each row execute function platform_row_guard();
create trigger record_type_assignment_tenant_guard before update on record_type_assignment
    for each row execute function platform_tenant_guard();
create trigger record_type_assignment_identity_guard before insert or update on record_type_assignment
    for each row execute function platform_definition_identity_guard();
create trigger record_type_assignment_metadata_version after insert or update or delete on record_type_assignment
    for each row execute function platform_metadata_version_bump();

alter table record_type_assignment enable row level security;
alter table record_type_assignment force row level security;
create policy record_type_assignment_insert on record_type_assignment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_type_assignment_select on record_type_assignment for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_type_assignment_update on record_type_assignment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_type_assignment_delete on record_type_assignment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_type_assignment is
    'Which profiles may use a record type of an object, and which type a profile gets by default. A record type with no row is open to every profile. Published metadata, tenant-scoped (ADR-0015, ADR-0098).';
