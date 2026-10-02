-- Licensing (Sprint 6, ADR-0031, ADR-0032): licence types, plans, feature keys, pools and assignments.
--
-- The model is generic on purpose. A licence TYPE is data (the first two are "user" and "admin"; Sprint 7 makes a profile
-- belong to a user-licence type and may add licence-bound access policies). A PLAN sets default quantities per licence
-- type and the features it includes. An organization has one POOL per licence type (a quantity) and one ASSIGNMENT per
-- member who holds a licence. A licence only counts and limits assignments: what a person may do is decided by
-- permissions, and which features exist is decided by entitlements; the three stay separate mechanisms.
--
-- Where the tables live (ADR-0031):
--   platform-level (no tenant column, no row level security): licence_type, plan, plan_licence, feature, plan_feature.
--     They are the vendor's catalogue: the same for every organization, edited only by platform administrators.
--   tenant-scoped (tenant_id, row level security enabled and forced): licence_pool, licence_assignment.
--     They are the organization's own records. A platform administrator changes a pool by opening that one
--     organization's context, so the policy limits the write to it.
--
-- The database refuses what must hold under concurrency: two administrators assigning the last free licence have one
-- winner (the pool row is locked), and a pool cannot be reduced below what is in use.

-- ---------------------------------------------------------------------------------------------------------------
-- Catalogue (platform-level)
-- ---------------------------------------------------------------------------------------------------------------

create table licence_type (
    id         uuid        primary key default uuidv7(),
    key        text        not null,
    name       text        not null,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint licence_type_key_format check (key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint licence_type_name_length check (char_length(name) between 1 and 80)
);
create trigger licence_type_row_guard before insert or update on licence_type
    for each row execute function platform_row_guard();
create unique index licence_type_key_live on licence_type (key) where deleted_at is null;

create table feature (
    id         uuid        primary key default uuidv7(),
    key        text        not null,
    name       text        not null,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint feature_key_format check (key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint feature_name_length check (char_length(name) between 1 and 80)
);
create trigger feature_row_guard before insert or update on feature
    for each row execute function platform_row_guard();
create unique index feature_key_live on feature (key) where deleted_at is null;

create table plan (
    id         uuid        primary key default uuidv7(),
    key        text        not null,
    name       text        not null,
    trial_days integer,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint plan_key_format check (key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint plan_name_length check (char_length(name) between 1 and 80),
    constraint plan_trial_days_range check (trial_days is null or trial_days between 1 and 365)
);
create trigger plan_row_guard before insert or update on plan
    for each row execute function platform_row_guard();
create unique index plan_key_live on plan (key) where deleted_at is null;

-- Default quantity of one licence type that an organization on the plan gets.
create table plan_licence (
    id              uuid        primary key default uuidv7(),
    plan_id         uuid        not null references plan (id),
    licence_type_id uuid        not null references licence_type (id),
    quantity        integer     not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint plan_licence_quantity_range check (quantity between 0 and 100000)
);
create trigger plan_licence_row_guard before insert or update on plan_licence
    for each row execute function platform_row_guard();
create unique index plan_licence_plan_type on plan_licence (plan_id, licence_type_id) where deleted_at is null;

-- A feature that an organization on the plan has by default.
create table plan_feature (
    id         uuid        primary key default uuidv7(),
    plan_id    uuid        not null references plan (id),
    feature_id uuid        not null references feature (id),
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid
);
create trigger plan_feature_row_guard before insert or update on plan_feature
    for each row execute function platform_row_guard();
create unique index plan_feature_plan_feature on plan_feature (plan_id, feature_id) where deleted_at is null;

comment on table licence_type is 'A kind of licence an organization holds a pool of (for example user, admin). Platform-level catalogue (ADR-0031).';
comment on table feature is 'A feature key that a plan may include and an organization may be given or denied. Platform-level catalogue (ADR-0031).';
comment on table plan is 'A commercial plan: default licence quantities and features; trial_days is set for a plan that starts as a trial. Platform-level catalogue (ADR-0031).';
comment on table plan_licence is 'The default quantity of one licence type for organizations on a plan. Platform-level (ADR-0031).';
comment on table plan_feature is 'A feature a plan includes by default. Platform-level (ADR-0031).';

-- Reference data every environment needs: the first two licence types, the four first feature keys, and the plan an
-- organization founded by a signed-in person starts on ("try for free", ADR-0033). The platform itself (the all-zero
-- identifier) is the author. Platform administrators change all of this through the console.
insert into licence_type (key, name, created_by, updated_by)
values ('user', 'User', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
       ('admin', 'Administrator', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');

insert into feature (key, name, created_by, updated_by)
values ('approvals', 'Approvals', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
       ('workflows', 'Workflows', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
       ('integrations', 'Integrations', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
       ('public-api', 'Public API', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');

insert into plan (key, name, trial_days, created_by, updated_by)
values ('trial', 'Trial', 30, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');

insert into plan_licence (plan_id, licence_type_id, quantity, created_by, updated_by)
select p.id, t.id, case t.key when 'user' then 5 else 2 end,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from plan p cross join licence_type t where p.key = 'trial';

insert into plan_feature (plan_id, feature_id, created_by, updated_by)
select p.id, f.id, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from plan p cross join feature f where p.key = 'trial';

-- ---------------------------------------------------------------------------------------------------------------
-- Pools and assignments (tenant-scoped, ADR-0015)
-- ---------------------------------------------------------------------------------------------------------------

create table licence_pool (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    licence_type_id uuid        not null references licence_type (id),
    quantity        integer     not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint licence_pool_quantity_range check (quantity between 0 and 100000)
);

create table licence_assignment (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    membership_id   uuid        not null references membership (id),
    licence_type_id uuid        not null references licence_type (id),
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid
);

-- One pool per licence type and organization; one licence per member (a released assignment is a soft-deleted row).
create unique index licence_pool_tenant_type on licence_pool (tenant_id, licence_type_id) where deleted_at is null;
create unique index licence_assignment_tenant_member on licence_assignment (tenant_id, membership_id)
    where deleted_at is null;
create index licence_assignment_tenant_type on licence_assignment (tenant_id, licence_type_id)
    where deleted_at is null;

-- A pool cannot be reduced below what is in use. The pool row is already locked by the update itself, and an
-- assignment locks the same row, so the two cannot interleave.
create function platform_licence_pool_guard() returns trigger
    language plpgsql
as $guard$
declare
    used bigint;
begin
    if new.deleted_at is null and new.quantity < old.quantity then
        select count(*) into used from licence_assignment a
         where a.tenant_id = new.tenant_id and a.licence_type_id = new.licence_type_id and a.deleted_at is null;
        if new.quantity < used then
            raise exception 'licence guard: a pool cannot be reduced below the licences in use'
                using errcode = 'check_violation';
        end if;
    end if;
    if new.deleted_at is not null and old.deleted_at is null then
        if exists (select 1 from licence_assignment a
                    where a.tenant_id = new.tenant_id and a.licence_type_id = new.licence_type_id
                      and a.deleted_at is null) then
            raise exception 'licence guard: a pool in use cannot be removed' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_licence_pool_guard() is
    'A licence pool cannot be reduced below the licences assigned from it, nor removed while any is assigned (ADR-0032).';

-- An assignment locks its pool row, then checks that a licence is free and that the member is active. Two
-- administrators assigning the last free licence are decided one after the other: one wins, the other is refused.
create function platform_licence_assignment_guard() returns trigger
    language plpgsql
as $guard$
declare
    pool_quantity integer;
    used bigint;
    member_status text;
begin
    if tg_op = 'UPDATE' then
        if new.membership_id is distinct from old.membership_id
                or new.licence_type_id is distinct from old.licence_type_id then
            raise exception 'licence guard: an assignment does not change owner or type'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    select quantity into pool_quantity from licence_pool
     where tenant_id = new.tenant_id and licence_type_id = new.licence_type_id and deleted_at is null
       for update;
    if pool_quantity is null then
        raise exception 'licence guard: the organization has no pool of this licence type'
            using errcode = 'check_violation';
    end if;
    select count(*) into used from licence_assignment a
     where a.tenant_id = new.tenant_id and a.licence_type_id = new.licence_type_id and a.deleted_at is null;
    if used >= pool_quantity then
        raise exception 'licence guard: no free licence of this type' using errcode = 'check_violation';
    end if;
    select status into member_status from membership
     where id = new.membership_id and tenant_id = new.tenant_id and deleted_at is null;
    if member_status is distinct from 'ACTIVE' then
        raise exception 'licence guard: only an active member can hold a licence' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_licence_assignment_guard() is
    'Locks the pool row, refuses an assignment when no licence is free or the member is not active (ADR-0032).';

create trigger licence_pool_row_guard before insert or update on licence_pool
    for each row execute function platform_row_guard();
create trigger licence_pool_tenant_guard before update on licence_pool
    for each row execute function platform_tenant_guard();
create trigger licence_pool_use_guard before update on licence_pool
    for each row execute function platform_licence_pool_guard();

create trigger licence_assignment_row_guard before insert or update on licence_assignment
    for each row execute function platform_row_guard();
create trigger licence_assignment_tenant_guard before update on licence_assignment
    for each row execute function platform_tenant_guard();
create trigger licence_assignment_free_guard before insert or update on licence_assignment
    for each row execute function platform_licence_assignment_guard();

alter table licence_pool enable row level security;
alter table licence_pool force row level security;
create policy licence_pool_insert on licence_pool for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy licence_pool_select on licence_pool for select
    using (tenant_id = (select platform_current_tenant()));
create policy licence_pool_update on licence_pool for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy licence_pool_delete on licence_pool for delete
    using (tenant_id = (select platform_current_tenant()));

alter table licence_assignment enable row level security;
alter table licence_assignment force row level security;
create policy licence_assignment_insert on licence_assignment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy licence_assignment_select on licence_assignment for select
    using (tenant_id = (select platform_current_tenant()));
create policy licence_assignment_update on licence_assignment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy licence_assignment_delete on licence_assignment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table licence_pool is
    'How many licences of one type an organization holds. Tenant-scoped (ADR-0015); the quantity is set from the plan or by a platform administrator (ADR-0032).';
comment on table licence_assignment is
    'A licence held by an active member; released by soft delete. Tenant-scoped (ADR-0015). Counts and limits assignments only; permissions are decided elsewhere (ADR-0032).';
