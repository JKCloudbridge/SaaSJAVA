-- The user table: a person's global identity (ADR-0019, ADR-0022).
--
-- A user is not tenant-scoped. One person may belong to several organizations (memberships arrive in Sprint 5), so the
-- identity exists once for the whole platform and is looked up before any tenant context is known. The table therefore
-- has no tenant_id and no row level security; the schema-conventions test lists it as a platform-level table on
-- purpose, and ADR-0022 records why that is safe: it holds no tenant data, the application never returns another
-- person's row to a caller, and what a user may do inside an organization is decided by the membership and the
-- authorization engine, not by this table.
--
-- Lifecycle:  INVITED -> ACTIVE <-> SUSPENDED,  INVITED, ACTIVE or SUSPENDED -> DEACTIVATED (final).
-- The module enforces it; the trigger below enforces it again so that no other code path, including a hand-written
-- statement, can skip a step. A test proves that the two agree for every pair of states.
--
-- security_version is the "revoke everything of this user at once" counter (ADR-0019). Every token and login session
-- remembers the version it was issued under and stops working when the user's version is higher. The trigger raises
-- it whenever a user leaves ACTIVE, so that a suspended and later reinstated user does not get old tokens back, even
-- if some code path forgot to raise it.

create table platform_user (
    id                uuid        primary key default uuidv7(),
    email             text        not null,
    display_name      text        not null,
    status            text        not null default 'INVITED',
    status_changed_at timestamptz not null default now(),
    security_version  bigint      not null default 0,
    email_verified_at timestamptz,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint platform_user_email_format check (
        email = lower(btrim(email)) and char_length(email) between 3 and 254
        and email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    constraint platform_user_display_name_length check (
        display_name = btrim(display_name) and char_length(display_name) between 1 and 200),
    constraint platform_user_status_known check (status in ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    constraint platform_user_security_version_not_negative check (security_version >= 0)
);

create trigger platform_user_row_guard before insert or update on platform_user
    for each row execute function platform_row_guard();

-- An address names one live user. Soft-deleted rows do not block it (ADR-0010).
create unique index platform_user_email_live on platform_user (email) where deleted_at is null;

create function platform_user_status_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'INVITED' then
            raise exception 'user status guard: a user starts as INVITED' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.status <> old.status then
        if not ((old.status = 'INVITED' and new.status in ('ACTIVE', 'DEACTIVATED'))
                or (old.status = 'ACTIVE' and new.status in ('SUSPENDED', 'DEACTIVATED'))
                or (old.status = 'SUSPENDED' and new.status in ('ACTIVE', 'DEACTIVATED'))) then
            raise exception 'user status guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
        new.status_changed_at := now();
        if old.status = 'ACTIVE' then
            new.security_version := greatest(new.security_version, old.security_version + 1);
        end if;
    end if;
    if new.security_version < old.security_version then
        raise exception 'user status guard: security_version never goes down' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_user_status_guard() is
    'Enforces the user lifecycle at the database (ADR-0019): the first status, the legal transitions, and a security version that only rises, and rises whenever a user leaves ACTIVE.';

create trigger platform_user_status_guard before insert or update on platform_user
    for each row execute function platform_user_status_guard();

comment on table platform_user is
    'People (global identities). Platform-level: not tenant-scoped and without row level security (ADR-0022).';
