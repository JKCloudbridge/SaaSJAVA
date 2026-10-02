-- Controlled support access (Sprint 6, ADR-0035): request, approval, time limit, audit. No automatic access.
--
-- Platform support staff get no standing access to an organization. They ask (a REQUESTED row, with a bounded reason and
-- the time they need); an administrator of THE ORGANIZATION approves or denies from the Members area; an approval opens a
-- window of at most 4 hours; the organization may revoke at any time; the window ends by itself. Access past the window
-- needs a new request.
--
-- Tenant-scoped (ADR-0015): the grant is a record of the organization, seen and decided by its own administrators. A
-- platform person creates a request by opening that one organization's context, so row level security limits the write
-- to it. The enforcement point that later sprints must call before a support person reads tenant data is
-- SupportAccess.require(...) (shared kernel contract, implemented by the platform administration module); this sprint
-- builds the record, its lifecycle and the check, not any read of tenant business data (none exists yet).
--
-- The database refuses an illegal move (APPROVED only from REQUESTED, a window longer than 4 hours, a closed grant that
-- changes) and allows only one open request per person and organization.

create table support_access_grant (
    id                 uuid        primary key default uuidv7(),
    tenant_id          uuid        not null default platform_current_tenant() references tenant (id),
    requested_by       uuid        not null references platform_user (id),
    reason             text        not null,
    requested_minutes  integer     not null,
    status             text        not null default 'REQUESTED',
    request_expires_at timestamptz not null,
    decided_by         uuid,
    decided_at         timestamptz,
    access_expires_at  timestamptz,
    revoked_by         uuid,
    revoked_at         timestamptz,
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),
    created_by         uuid        not null,
    updated_at         timestamptz not null default now(),
    updated_by         uuid        not null,
    deleted_at         timestamptz,
    deleted_by         uuid,
    constraint support_access_grant_status_known
        check (status in ('REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED', 'REVOKED')),
    constraint support_access_grant_reason_length check (char_length(reason) between 1 and 200),
    constraint support_access_grant_minutes_range check (requested_minutes between 15 and 240),
    constraint support_access_grant_approved_has_window
        check (status not in ('APPROVED', 'REVOKED') or access_expires_at is not null)
);

create function platform_support_access_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'REQUESTED' then
            raise exception 'support access guard: a request starts as REQUESTED' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if old.status in ('DENIED', 'CANCELLED', 'REVOKED') then
        raise exception 'support access guard: a closed grant does not change' using errcode = 'check_violation';
    end if;
    if new.status <> old.status then
        if not ((old.status = 'REQUESTED' and new.status in ('APPROVED', 'DENIED', 'CANCELLED'))
                or (old.status = 'APPROVED' and new.status = 'REVOKED')) then
            raise exception 'support access guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
    end if;
    if new.status = 'APPROVED' and old.status = 'REQUESTED' then
        -- The window is at most 4 hours from the approval (a small allowance for the clock between statements).
        if new.access_expires_at is null or new.access_expires_at > now() + interval '241 minutes'
                or new.access_expires_at <= now() then
            raise exception 'support access guard: the access window must be in the future and at most 4 hours'
                using errcode = 'check_violation';
        end if;
    end if;
    if new.requested_by is distinct from old.requested_by or new.reason is distinct from old.reason
            or new.requested_minutes is distinct from old.requested_minutes then
        raise exception 'support access guard: the request does not change' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_support_access_guard() is
    'Enforces the support-access lifecycle (ADR-0035): legal moves, a window of at most 4 hours, and a request that does not change.';

create trigger support_access_grant_row_guard before insert or update on support_access_grant
    for each row execute function platform_row_guard();
create trigger support_access_grant_tenant_guard before update on support_access_grant
    for each row execute function platform_tenant_guard();
create trigger support_access_grant_lifecycle_guard before insert or update on support_access_grant
    for each row execute function platform_support_access_guard();

-- One open request per person and organization.
create unique index support_access_grant_open_request on support_access_grant (tenant_id, requested_by)
    where status = 'REQUESTED' and deleted_at is null;
create index support_access_grant_tenant on support_access_grant (tenant_id, created_at desc)
    where deleted_at is null;
create index support_access_grant_tenant_person on support_access_grant (tenant_id, requested_by, status)
    where deleted_at is null;

alter table support_access_grant enable row level security;
alter table support_access_grant force row level security;
create policy support_access_grant_insert on support_access_grant for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy support_access_grant_select on support_access_grant for select
    using (tenant_id = (select platform_current_tenant()));
create policy support_access_grant_update on support_access_grant for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy support_access_grant_delete on support_access_grant for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table support_access_grant is
    'A platform person''s request for time-limited access to one organization, and the organization''s decision. Tenant-scoped (ADR-0015); no automatic access (ADR-0035).';
