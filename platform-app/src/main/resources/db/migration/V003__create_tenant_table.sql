-- The tenant (organization) table (ADR-0014).
--
-- A tenant is not tenant-scoped: it is the thing the other tables are scoped by, and the platform must be able to
-- look it up by host name before any tenant context exists. It therefore has no tenant_id and no row level security;
-- the schema-conventions test lists it as a platform-level table on purpose.
--
-- Lifecycle:  PROVISIONING -> ACTIVE <-> SUSPENDED,  and any of those -> DEACTIVATED (final).
-- The module enforces it; the trigger below enforces it again so that no other code path, including a hand-written
-- statement, can skip a step. A test proves that the two agree for every pair of states.

create table tenant (
    id                uuid        primary key default uuidv7(),
    slug              text        not null,
    display_name      text        not null,
    status            text        not null default 'PROVISIONING',
    status_changed_at timestamptz not null default now(),
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint tenant_slug_format check (
        slug ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$' and char_length(slug) between 3 and 40),
    constraint tenant_display_name_length check (
        display_name = btrim(display_name) and char_length(display_name) between 1 and 200),
    constraint tenant_status_known check (status in ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

create trigger tenant_row_guard before insert or update on tenant
    for each row execute function platform_row_guard();

-- A slug names one live tenant. Soft-deleted rows do not block it (ADR-0010).
create unique index tenant_slug_live on tenant (slug) where deleted_at is null;

create function platform_tenant_status_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'PROVISIONING' then
            raise exception 'tenant status guard: a tenant starts as PROVISIONING' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.status <> old.status then
        if not ((old.status = 'PROVISIONING' and new.status in ('ACTIVE', 'DEACTIVATED'))
                or (old.status = 'ACTIVE' and new.status in ('SUSPENDED', 'DEACTIVATED'))
                or (old.status = 'SUSPENDED' and new.status in ('ACTIVE', 'DEACTIVATED'))) then
            raise exception 'tenant status guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
        new.status_changed_at := now();
    end if;
    return new;
end;
$guard$;

comment on function platform_tenant_status_guard() is
    'Enforces the tenant lifecycle at the database (ADR-0014): the first status and the legal transitions.';

create trigger tenant_status_guard before insert or update on tenant
    for each row execute function platform_tenant_status_guard();

comment on table tenant is
    'Organizations. Platform-level: not tenant-scoped and without row level security, looked up by host name before a tenant context exists (ADR-0014).';
