-- Subscriptions, trials and per-organization feature overrides (Sprint 6, ADR-0031, ADR-0033).
--
-- Both tables are PLATFORM-LEVEL on purpose (ADR-0031). They are the vendor's commercial facts about an organization
-- (which plan it is on, when its trial ends, which feature the vendor switched on or off for it), not the organization's
-- own data, and the console must be able to LIST them across organizations (expiring trials, plans) without any
-- privileged connection or broad system scope. The organization is named in bound_tenant_id, never tenant_id, so the
-- schema scanner and every reader see that these rows are not protected by row level security. The trade-off, stated in
-- ADR-0031: a bug in the licensing code that asked for the wrong organization could read another organization's plan or
-- feature switch (commercial facts), never any of its business data. Every read by the organization's own code
-- goes through the licensing module, which takes the organization only from the tenant context.
--
-- No payment processor is coupled here: a subscription is a plan, a status and dates. Nothing ends a trial by itself
-- (the scheduler of Sprint 25 does); the console shows a trial as expiring or expired.

create table subscription (
    id              uuid        primary key default uuidv7(),
    bound_tenant_id uuid        not null references tenant (id),
    plan_id         uuid        not null references plan (id),
    status          text        not null,
    started_at      timestamptz not null default now(),
    trial_ends_at   timestamptz,
    period_ends_at  timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint subscription_status_known check (status in ('TRIAL', 'ACTIVE', 'SUSPENDED', 'CANCELLED')),
    constraint subscription_trial_has_end check (status <> 'TRIAL' or trial_ends_at is not null)
);
create trigger subscription_row_guard before insert or update on subscription
    for each row execute function platform_row_guard();
-- An organization has one live subscription; changing the plan updates it.
create unique index subscription_tenant_live on subscription (bound_tenant_id) where deleted_at is null;
create index subscription_trial_end on subscription (trial_ends_at) where status = 'TRIAL' and deleted_at is null;

-- A feature the vendor switched on or off for one organization, over the plan's default.
create table entitlement_override (
    id              uuid        primary key default uuidv7(),
    bound_tenant_id uuid        not null references tenant (id),
    feature_id      uuid        not null references feature (id),
    enabled         boolean     not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid
);
create trigger entitlement_override_row_guard before insert or update on entitlement_override
    for each row execute function platform_row_guard();
create unique index entitlement_override_tenant_feature on entitlement_override (bound_tenant_id, feature_id)
    where deleted_at is null;

comment on table subscription is
    'The plan, status and dates of an organization''s subscription. Platform-level on purpose (ADR-0031): vendor facts, listed across organizations; no payment processor (ADR-0033).';
comment on table entitlement_override is
    'A feature switched on or off for one organization over its plan''s default. Platform-level on purpose (ADR-0031).';
