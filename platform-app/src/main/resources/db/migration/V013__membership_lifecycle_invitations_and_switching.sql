-- Membership proper, invitations and organization switching (Sprint 5, ADR-0026 to ADR-0028).
--
-- Every change here is backward compatible (expand): a column with a default, a wider check, new tables, new triggers.
-- Rows written by Sprint 4 (ACTIVE founders) satisfy everything below.
--
-- 1. membership: a status that can move between ACTIVE and DEACTIVATED, and an `administrator` marker that is separate
--    from the historical `founding_administrator` fact. The marker is the stop-gap that lets someone invite and
--    deactivate before access policies exist (Sprint 7 replaces it). The database refuses an illegal status move and
--    refuses to leave an organization without an active administrator, even under concurrency.
-- 2. invitation: the server-side record an e-mailed link resolves to. Tenant-scoped (ADR-0015). It names the
--    organization, so the organization never comes from the browser. A membership exists only after acceptance.
-- 3. account_token: may now belong to an invitation (the organization and the invitation it resolves to).
-- 4. organization_handoff: platform-level, 60-second, single-use, hashed proof that a signed-in person asked to move to
--    another organization's host (cookies are per host).
-- 5. A narrow system scope, `membership_lookup`, lets the identity module ask which organizations one person belongs
--    to (the switcher). It can only read membership rows.

-- ---------------------------------------------------------------------------------------------------------------
-- 1. membership
-- ---------------------------------------------------------------------------------------------------------------

alter table membership add column administrator boolean not null default false;
alter table membership add column status_changed_at timestamptz not null default now();

-- Backfill: the founders of Sprint 4 administer their organization. Forced row level security binds the owner too,
-- and a migration has no tenant, so it is switched off for this one statement and back on in the same transaction.
alter table membership no force row level security;
update membership
   set administrator = true, version = version + 1, updated_by = created_by
 where founding_administrator and not administrator;
alter table membership force row level security;

alter table membership drop constraint membership_status_known;
alter table membership add constraint membership_status_known check (status in ('ACTIVE', 'DEACTIVATED'));
-- A deactivated member administers nothing (the lifecycle guard clears the marker; this states the rule).
alter table membership add constraint membership_administrator_is_active
    check (not administrator or status = 'ACTIVE');

create function platform_membership_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'ACTIVE' then
            raise exception 'membership guard: a membership starts as ACTIVE' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if new.status <> old.status then
        if not ((old.status = 'ACTIVE' and new.status = 'DEACTIVATED')
                or (old.status = 'DEACTIVATED' and new.status = 'ACTIVE')) then
            raise exception 'membership guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
        new.status_changed_at := now();
    end if;
    -- Leaving the active state ends the administrator marker; a returning member is named again on purpose.
    if new.status <> 'ACTIVE' then
        new.administrator := false;
    end if;

    -- The last active administrator cannot lose the marker, be deactivated or be deleted. The lock serializes the
    -- check per organization, so two administrators stepping down at the same moment cannot both pass it.
    if old.administrator and old.status = 'ACTIVE' and old.deleted_at is null
            and (not new.administrator or new.status <> 'ACTIVE' or new.deleted_at is not null) then
        perform pg_advisory_xact_lock(hashtextextended('membership-administrators:' || old.tenant_id::text, 0));
        if not exists (select 1 from membership m
                        where m.tenant_id = old.tenant_id and m.id <> old.id and m.administrator
                          and m.status = 'ACTIVE' and m.deleted_at is null) then
            raise exception 'membership guard: the last administrator of an organization cannot be removed'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_membership_guard() is
    'Enforces the membership lifecycle (ADR-0026): legal status moves, the administrator marker ends with the active state, and the last active administrator of an organization stays.';

create trigger membership_lifecycle_guard before insert or update on membership
    for each row execute function platform_membership_guard();

-- Administrators are looked up per organization.
create index membership_tenant_admin on membership (tenant_id) where administrator and status = 'ACTIVE' and deleted_at is null;

-- The narrow cross-organization read (ADR-0027): the select policy also admits the membership_lookup system scope.
drop policy membership_select on membership;
create policy membership_select on membership for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('membership_lookup'));

comment on table membership is
    'A user''s membership of an organization. Tenant-scoped (ADR-0015). ACTIVE or DEACTIVATED; the administrator marker is a Sprint 5 stop-gap until access policies (Sprint 7); founding_administrator is a historical fact. The membership_lookup system scope may read across tenants (ADR-0027).';

-- ---------------------------------------------------------------------------------------------------------------
-- 2. invitation
-- ---------------------------------------------------------------------------------------------------------------

create table invitation (
    id                     uuid        primary key default uuidv7(),
    tenant_id              uuid        not null default platform_current_tenant() references tenant (id),
    email                  text        not null,
    administrator          boolean     not null default false,
    founding_administrator boolean     not null default false,
    status                 text        not null default 'OPEN',
    expires_at             timestamptz not null,
    sent_count             integer     not null default 1,
    resolved_at            timestamptz,
    resolved_by            uuid,
    membership_id          uuid,
    version                bigint      not null default 0,
    created_at             timestamptz not null default now(),
    created_by             uuid        not null,
    updated_at             timestamptz not null default now(),
    updated_by             uuid        not null,
    deleted_at             timestamptz,
    deleted_by             uuid,
    constraint invitation_status_known check (status in ('OPEN', 'ACCEPTED', 'REVOKED')),
    constraint invitation_email_format check (email = lower(btrim(email)) and char_length(email) between 3 and 254),
    constraint invitation_resolved_when_closed check ((status = 'OPEN') = (resolved_at is null)),
    constraint invitation_accepted_has_membership check (status <> 'ACCEPTED' or membership_id is not null)
);

create function platform_invitation_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'OPEN' then
            raise exception 'invitation guard: an invitation starts as OPEN' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.status <> old.status and not (old.status = 'OPEN' and new.status in ('ACCEPTED', 'REVOKED')) then
        raise exception 'invitation guard: % to % is not a legal transition', old.status, new.status
            using errcode = 'check_violation';
    end if;
    if old.status <> 'OPEN' and (new.email is distinct from old.email
            or new.administrator is distinct from old.administrator
            or new.founding_administrator is distinct from old.founding_administrator) then
        raise exception 'invitation guard: a closed invitation is read-only' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_invitation_guard() is
    'Enforces the invitation lifecycle (ADR-0028): OPEN to ACCEPTED or REVOKED, nothing else, and a closed invitation does not change.';

create trigger invitation_row_guard before insert or update on invitation
    for each row execute function platform_row_guard();
create trigger invitation_lifecycle_guard before insert or update on invitation
    for each row execute function platform_invitation_guard();
create trigger invitation_tenant_guard before update on invitation
    for each row execute function platform_tenant_guard();

-- One open invitation per address and organization. The index starts with tenant_id (ADR-0015).
create unique index invitation_tenant_email_open on invitation (tenant_id, email)
    where status = 'OPEN' and deleted_at is null;
create index invitation_tenant_created on invitation (tenant_id, created_at desc) where deleted_at is null;

alter table invitation enable row level security;
alter table invitation force row level security;

create policy invitation_insert on invitation for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy invitation_select on invitation for select
    using (tenant_id = (select platform_current_tenant()));
create policy invitation_update on invitation for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy invitation_delete on invitation for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table invitation is
    'An organization''s invitation of an e-mail address. Tenant-scoped (ADR-0015). Grants nothing until accepted; the e-mailed link resolves to it through account_token (ADR-0028).';

-- ---------------------------------------------------------------------------------------------------------------
-- 3. account_token: a token may belong to an invitation
-- ---------------------------------------------------------------------------------------------------------------

alter table account_token add column context_tenant_id uuid references tenant (id);
alter table account_token add column invitation_id uuid;

alter table account_token drop constraint account_token_purpose_known;
alter table account_token add constraint account_token_purpose_known
    check (purpose in ('SIGN_UP', 'PASSWORD_RESET', 'INVITATION'));
alter table account_token add constraint account_token_invitation_has_context
    check ((purpose = 'INVITATION') = (context_tenant_id is not null and invitation_id is not null)
           and (purpose = 'INVITATION' or (context_tenant_id is null and invitation_id is null)));

-- Cancelling the older links of one invitation when it is sent again, revoked or accepted.
create index account_token_invitation_open on account_token (invitation_id)
    where invitation_id is not null and used_at is null and revoked_at is null;

comment on table account_token is
    'One-time sign-up, password-reset and invitation link tokens, stored only as hashes. Platform-level (ADR-0023); an invitation token names its organization in context_tenant_id (ADR-0028).';

-- ---------------------------------------------------------------------------------------------------------------
-- 4. organization_handoff
-- ---------------------------------------------------------------------------------------------------------------

create table organization_handoff (
    id              uuid        primary key default uuidv7(),
    token_hash      text        not null,
    user_id         uuid        not null references platform_user (id),
    bound_tenant_id uuid        not null references tenant (id),
    expires_at      timestamptz not null,
    used_at         timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint organization_handoff_hash_format check (token_hash ~ '^sha256:[0-9a-f]{64}$')
);

create trigger organization_handoff_row_guard before insert or update on organization_handoff
    for each row execute function platform_row_guard();

create unique index organization_handoff_hash_live on organization_handoff (token_hash) where deleted_at is null;
create index organization_handoff_user on organization_handoff (user_id) where used_at is null;
create index organization_handoff_expires on organization_handoff (expires_at);

comment on table organization_handoff is
    'A signed-in person''s one-time, 60-second request to continue on another organization''s host, stored only as a hash. Platform-level (ADR-0029).';
