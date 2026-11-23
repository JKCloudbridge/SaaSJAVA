-- Connected applications: machine access to the public API (Sprint 31, ADR-0267 to ADR-0273). Expand only; nothing is
-- removed or renamed. (The numbers V095 and ADR-0267 were the next free ones when this sprint was built; the
-- several-sheets work that is planned beside it takes the next free number after whichever is built first.)
--
--   oauth_client         One row for each connected application an organization registered: a public client
--                        identifier, a name, the member it acts as (fixed for ever), its state, its scopes, an
--                        optional list of the only objects it may use and the life of its tokens. It never holds a
--                        secret. The organization that registered it is the only one that can see it or use it.
--   oauth_client_secret  The secrets of an application, as hashes only (the secret is shown once, when it is made,
--                        and kept nowhere). Several rows for one application so that a rotation can overlap: a
--                        secret works until it is ended or, when it was given an overlap, until that time.
--   oauth2_authorization gets two columns that mark a grant made by the client-credentials flow: the application and
--                        the secret that made it. They are how revoking a secret, switching an application off or
--                        removing it ends the matching tokens in the same transaction.
--
-- What must hold even if the application had a bug:
--   * both new tables are tenant-scoped with forced row level security, and there is no system scope on them: the
--     application finds a client only on the host of the organization that registered it;
--   * the client identifier, the organization and the member an application acts as never change, whoever asks (a
--     person who should be acted as is a new application); a removed application stays removed;
--   * a secret row never changes after it is written except for its marks: when it was last used (only forward), when
--     it stops (an overlap of at most 24 hours, only earlier) and its end, which is final; nothing is ever deleted;
--   * a grant of the client-credentials kind always names its application and secret, and no other grant does.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. Nothing in it is ever written to a log, an audit record or an event.

create table oauth_client (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    client_id       text        not null,
    name            text        not null,
    description     text        not null default '',
    membership_id   uuid        not null references membership (id),
    user_id         uuid        not null references platform_user (id),
    state           text        not null default 'ACTIVE',
    scopes          text[]      not null,
    objects         text[],
    token_seconds   integer     not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint oauth_client_id_shape check (client_id ~ '^ca_[A-Za-z0-9]{24}$'),
    constraint oauth_client_name_shape check (char_length(name) between 1 and 80),
    constraint oauth_client_description_size check (char_length(description) <= 500),
    constraint oauth_client_state check (state in ('ACTIVE', 'DISABLED')),
    constraint oauth_client_scopes_known
        check (cardinality(scopes) between 1 and 3 and scopes <@ array['objects.read', 'records.read', 'records.write']),
    constraint oauth_client_objects_shape
        check (objects is null or (cardinality(objects) between 1 and 50
            and array_to_string(objects, ',') ~ '^[A-Za-z][A-Za-z0-9_]{0,79}(,[A-Za-z][A-Za-z0-9_]{0,79})*$')),
    constraint oauth_client_token_life check (token_seconds between 300 and 3600)
);

-- A client identifier means one application of one organization; the index also leads with the tenant.
create unique index oauth_client_tenant_client on oauth_client (tenant_id, client_id) where deleted_at is null;
create index oauth_client_tenant_user on oauth_client (tenant_id, user_id) where deleted_at is null;

create trigger oauth_client_row_guard before insert or update on oauth_client
    for each row execute function platform_row_guard();
create trigger oauth_client_tenant_guard before update on oauth_client
    for each row execute function platform_tenant_guard();

create function platform_oauth_client_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'DELETE' then
        if old.deleted_at is null then
            raise exception 'client guard: a connected application is removed by marking it, never deleted'
                using errcode = 'check_violation';
        end if;
        raise exception 'client guard: a removed connected application is kept' using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' then
        if new.client_id is distinct from old.client_id then
            raise exception 'client guard: the identifier of a connected application is immutable'
                using errcode = 'check_violation';
        end if;
        if old.deleted_at is not null and new.deleted_at is null then
            raise exception 'client guard: a removed connected application stays removed'
                using errcode = 'check_violation';
        end if;
        if new.user_id is distinct from old.user_id or new.membership_id is distinct from old.membership_id then
            raise exception 'client guard: the member a connected application acts as is immutable'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create trigger oauth_client_life_guard before update or delete on oauth_client
    for each row execute function platform_oauth_client_guard();

create table oauth_client_secret (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    client_row_id   uuid        not null references oauth_client (id),
    number          integer     not null,
    secret_hash     text        not null,
    expires_at      timestamptz,
    ended_at        timestamptz,
    ended_reason    text,
    last_used_at    timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint oauth_client_secret_number check (number >= 1),
    constraint oauth_client_secret_hash_shape check (secret_hash ~ '^sha256:[0-9a-f]{64}$'),
    constraint oauth_client_secret_end_reason check (ended_reason in ('rotated', 'revoked', 'removed')),
    constraint oauth_client_secret_end_pair check ((ended_at is null) = (ended_reason is null))
);

create unique index oauth_client_secret_number_live
    on oauth_client_secret (tenant_id, client_row_id, number) where deleted_at is null;
create index oauth_client_secret_client on oauth_client_secret (tenant_id, client_row_id) where deleted_at is null;

create trigger oauth_client_secret_row_guard before insert or update on oauth_client_secret
    for each row execute function platform_row_guard();
create trigger oauth_client_secret_tenant_guard before update on oauth_client_secret
    for each row execute function platform_tenant_guard();

create function platform_oauth_client_secret_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'DELETE' then
        raise exception 'client secret guard: a secret is ended, never deleted' using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.ended_at is not null or new.last_used_at is not null then
            raise exception 'client secret guard: a secret starts unused and not ended'
                using errcode = 'check_violation';
        end if;
        if new.expires_at is not null then
            raise exception 'client secret guard: a new secret has no end' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- update. Messages name the table only, never a value.
    if new.client_row_id is distinct from old.client_row_id or new.number is distinct from old.number
            or new.secret_hash is distinct from old.secret_hash then
        raise exception 'client secret guard: a stored secret is immutable' using errcode = 'check_violation';
    end if;
    if old.ended_at is not null and (new.ended_at is distinct from old.ended_at
            or new.ended_reason is distinct from old.ended_reason) then
        raise exception 'client secret guard: the end of a secret is final' using errcode = 'check_violation';
    end if;
    if old.last_used_at is not null and new.last_used_at is distinct from old.last_used_at
            and (new.last_used_at is null or new.last_used_at < old.last_used_at) then
        raise exception 'client secret guard: the last use of a secret only moves forward'
            using errcode = 'check_violation';
    end if;
    if new.expires_at is distinct from old.expires_at then
        if new.expires_at is null then
            raise exception 'client secret guard: the end of an overlap cannot be taken back'
                using errcode = 'check_violation';
        end if;
        if new.expires_at > now() + interval '24 hours' then
            raise exception 'client secret guard: an overlap is at most 24 hours' using errcode = 'check_violation';
        end if;
        if old.expires_at is not null and new.expires_at > old.expires_at then
            raise exception 'client secret guard: an overlap can only be shortened' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create trigger oauth_client_secret_life_guard before insert or update or delete on oauth_client_secret
    for each row execute function platform_oauth_client_secret_guard();

alter table oauth_client enable row level security;
alter table oauth_client force row level security;
create policy oauth_client_insert on oauth_client for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy oauth_client_select on oauth_client for select
    using (tenant_id = (select platform_current_tenant()));
create policy oauth_client_update on oauth_client for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
-- There is no delete policy: a connected application is marked removed, never deleted.

alter table oauth_client_secret enable row level security;
alter table oauth_client_secret force row level security;
create policy oauth_client_secret_insert on oauth_client_secret for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy oauth_client_secret_select on oauth_client_secret for select
    using (tenant_id = (select platform_current_tenant()));
create policy oauth_client_secret_update on oauth_client_secret for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));

-- The grants of the client-credentials flow (ADR-0269). The existing table keeps one row for each grant of every kind;
-- these two columns say which application and which secret made a machine grant, so that ending the application or
-- the secret ends its tokens in the same transaction. A grant of a person has neither.
alter table oauth2_authorization add column oauth_client_id uuid references oauth_client (id);
alter table oauth2_authorization add column client_secret_id uuid references oauth_client_secret (id);
alter table oauth2_authorization add constraint oauth2_authorization_machine_shape
    check ((authorization_grant_type = 'client_credentials') = (oauth_client_id is not null)
        and (oauth_client_id is null) = (client_secret_id is null));
create index oauth2_authorization_client on oauth2_authorization (oauth_client_id) where oauth_client_id is not null;
create index oauth2_authorization_client_secret
    on oauth2_authorization (client_secret_id) where client_secret_id is not null;

comment on table oauth_client is
    'A connected application of an organization: public identifier, the member it acts as, scopes and token life. Never a secret (ADR-0267).';
comment on table oauth_client_secret is
    'The secrets of a connected application as hashes only, several at once so that a rotation can overlap (ADR-0268).';
