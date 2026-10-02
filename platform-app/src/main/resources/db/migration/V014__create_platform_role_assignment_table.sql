-- Platform roles (Sprint 6, ADR-0030): who may operate the platform itself, separate from any organization.
--
-- Platform-level on purpose (ADR-0015): a platform role belongs to a person (platform_user), not to an organization, and the
-- console answers on the platform host where there is no tenant. A platform role gives no authority inside an
-- organization: that comes from an active membership only (ADR-0026).
--
-- The three roles: PLATFORM_ADMIN (organizations, provisioning, plans, pools, entitlements, platform roles),
-- PLATFORM_SUPPORT (reads the state of an organization, never its members or business data; asks for support access;
-- resends a first-administrator invitation; signs a user out) and PLATFORM_BILLING (plans, subscriptions, trials).
--
-- The last platform administrator stays: the database refuses to end the last live PLATFORM_ADMIN assignment of an
-- active account, also when two administrators step down at the same moment (the advisory lock serializes them), so the
-- platform cannot lock itself out through the console. The first one is created by a documented manual step (M002), never
-- by a default account.

create table platform_role_assignment (
    id         uuid        primary key default uuidv7(),
    user_id    uuid        not null references platform_user (id),
    role       text        not null,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint platform_role_assignment_role_known
        check (role in ('PLATFORM_ADMIN', 'PLATFORM_SUPPORT', 'PLATFORM_BILLING'))
);

create trigger platform_role_assignment_row_guard before insert or update on platform_role_assignment
    for each row execute function platform_row_guard();

-- A person holds a role once.
create unique index platform_role_assignment_user_role on platform_role_assignment (user_id, role)
    where deleted_at is null;
create index platform_role_assignment_role on platform_role_assignment (role) where deleted_at is null;

create function platform_role_assignment_guard() returns trigger
    language plpgsql
as $guard$
declare
    ending boolean;
begin
    if tg_op = 'DELETE' then
        ending := old.deleted_at is null;
    else
        ending := old.deleted_at is null and new.deleted_at is not null;
    end if;
    if ending and old.role = 'PLATFORM_ADMIN' then
        perform pg_advisory_xact_lock(hashtextextended('platform-administrators', 0));
        if not exists (select 1
                         from platform_role_assignment a
                         join platform_user u on u.id = a.user_id
                        where a.role = 'PLATFORM_ADMIN' and a.id <> old.id and a.deleted_at is null
                          and u.status = 'ACTIVE' and u.deleted_at is null) then
            raise exception 'platform role guard: the last platform administrator cannot be removed'
                using errcode = 'check_violation';
        end if;
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$guard$;

comment on function platform_role_assignment_guard() is
    'The last live PLATFORM_ADMIN assignment of an active account cannot be removed (ADR-0030); an advisory lock serializes concurrent removals.';

create trigger platform_role_assignment_last_admin before update or delete on platform_role_assignment
    for each row execute function platform_role_assignment_guard();

comment on table platform_role_assignment is
    'A platform role held by a person. Platform-level (ADR-0030); grants no authority inside any organization.';
