-- Membership: the connection between a user and an organization (Sprint 4 minimal version, ADR-0025).
--
-- Tenant-scoped (ADR-0015): tenant_id, row level security enabled and forced, the tenant guard, a tenant-first index.
-- The user is a global identity (platform_user); the membership is the tenant's own fact about that person. Sprint 4
-- creates exactly one kind of membership: the founding administrator, written when a signed-in person creates an
-- organization. No role or permission is stored here (Sprint 7 attaches them to the membership). Sprint 5 extends the
-- statuses (invited, deactivated) and adds the invitation flow; extending a check constraint is a backward-compatible
-- change.

create table membership (
    id                     uuid        primary key default uuidv7(),
    tenant_id              uuid        not null default platform_current_tenant() references tenant (id),
    user_id                uuid        not null references platform_user (id),
    status                 text        not null default 'ACTIVE',
    founding_administrator boolean     not null default false,
    version                bigint      not null default 0,
    created_at             timestamptz not null default now(),
    created_by             uuid        not null,
    updated_at             timestamptz not null default now(),
    updated_by             uuid        not null,
    deleted_at             timestamptz,
    deleted_by             uuid,
    constraint membership_status_known check (status in ('ACTIVE'))
);

create trigger membership_row_guard before insert or update on membership
    for each row execute function platform_row_guard();
create trigger membership_tenant_guard before update on membership
    for each row execute function platform_tenant_guard();

-- A person is a member of an organization once.
create unique index membership_tenant_user_live on membership (tenant_id, user_id) where deleted_at is null;

alter table membership enable row level security;
alter table membership force row level security;

create policy membership_insert on membership for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy membership_select on membership for select
    using (tenant_id = (select platform_current_tenant()));
create policy membership_update on membership for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy membership_delete on membership for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table membership is
    'A user''s membership of an organization. Tenant-scoped (ADR-0015). Sprint 4: the founding administrator only (ADR-0025).';
