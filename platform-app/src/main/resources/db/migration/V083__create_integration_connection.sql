-- The connection of an organization to one external system (Sprint 26, ADR-0207 to ADR-0211).
--
-- One row is the definition (where, how it authenticates, what it listens for, what it may touch) and the state of the
-- connection (untested, tested, enabled, disabled, failing). The definition is deliberately NOT metadata: an address
-- and a credential reference belong to one environment and must never travel with a release or a rollback.
--
-- What the row never holds: a secret (only the NAME of one, resolved at the moment of use inside the organization's own
-- scope, ADR-0007), a response body, a header. The fingerprint is a hash of the parts that decide whether a successful
-- test still means something; an enabled connection must carry a successful test of exactly its current definition,
-- whatever the application does (the guard below).
--
-- inbound_id is the unguessable public identity the receiver of Sprint 28 will look a connection up by, so that the
-- tenant of an inbound call is found from the connection and never from anything the caller says. Nothing reads it yet;
-- it is here so that Sprint 28 needs no contract migration. Expand only.

create table integration_connection (
    id                      uuid        primary key default uuidv7(),
    tenant_id               uuid        not null default platform_current_tenant() references tenant (id),
    name                    text        not null,
    label                   text        not null,
    connector_type          text        not null,
    connector_version       integer     not null,
    base_url                text        not null,
    auth_kind               text        not null,
    auth_header_name        text,
    credential_name         text,
    settings                jsonb       not null default '{}'::jsonb,
    subscriptions           jsonb       not null default '[]'::jsonb,
    scope                   jsonb       not null default '[]'::jsonb,
    state                   text        not null default 'UNTESTED',
    definition_fingerprint  text        not null,
    tested_fingerprint      text,
    last_test_at            timestamptz,
    last_test_result        text,
    last_test_reason        text,
    test_claimed_at         timestamptz,
    inbound_id              text        not null default replace(gen_random_uuid()::text, '-', ''),
    version                 bigint      not null default 0,
    created_at              timestamptz not null default now(),
    created_by              uuid        not null,
    updated_at              timestamptz not null default now(),
    updated_by              uuid        not null,
    deleted_at              timestamptz,
    deleted_by              uuid,
    constraint integration_connection_name_shape check (name ~ '^[a-z][a-z0-9-]{1,39}$'),
    constraint integration_connection_label_bound check (length(label) between 1 and 80),
    constraint integration_connection_type_shape check (connector_type ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint integration_connection_url_bound check (length(base_url) between 1 and 2048),
    constraint integration_connection_auth_kind check (auth_kind in ('NONE', 'BEARER_TOKEN', 'API_KEY_HEADER', 'BASIC')),
    constraint integration_connection_header_shape check (auth_header_name is null
        or auth_header_name ~ '^[A-Za-z][A-Za-z0-9-]{0,63}$'),
    constraint integration_connection_header_needed check ((auth_kind = 'API_KEY_HEADER') = (auth_header_name is not null)),
    constraint integration_connection_credential_shape check (credential_name is null
        or credential_name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    constraint integration_connection_credential_needed check ((auth_kind = 'NONE') = (credential_name is null)),
    constraint integration_connection_settings_object check (jsonb_typeof(settings) = 'object'
        and length(settings::text) <= 4096),
    constraint integration_connection_subscriptions_array check (jsonb_typeof(subscriptions) = 'array'
        and jsonb_array_length(subscriptions) <= 20),
    constraint integration_connection_scope_array check (jsonb_typeof(scope) = 'array'
        and jsonb_array_length(scope) <= 50),
    constraint integration_connection_state check (state in ('UNTESTED', 'TESTED', 'ENABLED', 'DISABLED', 'FAILING')),
    constraint integration_connection_fingerprint_shape check (definition_fingerprint ~ '^[0-9a-f]{64}$'
        and (tested_fingerprint is null or tested_fingerprint ~ '^[0-9a-f]{64}$')),
    constraint integration_connection_test_result check (last_test_result is null
        or last_test_result in ('PASSED', 'FAILED')),
    constraint integration_connection_test_reason check (last_test_reason is null
        or last_test_reason ~ '^[a-z_]{1,40}$'),
    constraint integration_connection_inbound_shape check (inbound_id ~ '^[0-9a-f]{32}$')
);

create unique index integration_connection_tenant_name
    on integration_connection (tenant_id, name) where deleted_at is null;
create index integration_connection_tenant_state
    on integration_connection (tenant_id, state) where deleted_at is null;
-- Global on purpose: the receiver finds a connection (and through it the organization) from this alone.
create unique index integration_connection_inbound
    on integration_connection (inbound_id) where deleted_at is null;

-- The life of a connection, held at the database as well as in the application (ADR-0210). Messages name the table
-- only, never a value.
create function platform_integration_connection_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.state <> 'UNTESTED' or new.tested_fingerprint is not null or new.last_test_at is not null
                or new.last_test_result is not null or new.test_claimed_at is not null then
            raise exception 'integration guard: a connection starts untested' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if new.name is distinct from old.name or new.connector_type is distinct from old.connector_type
            or new.inbound_id is distinct from old.inbound_id then
        raise exception 'integration guard: name, type and inbound identity of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;

    -- A connection that is switched on, or failing while switched on, is frozen: it is switched off to be changed
    -- and removed.
    if old.state in ('ENABLED', 'FAILING') then
        if new.base_url is distinct from old.base_url or new.auth_kind is distinct from old.auth_kind
                or new.auth_header_name is distinct from old.auth_header_name
                or new.credential_name is distinct from old.credential_name
                or new.settings is distinct from old.settings
                or new.subscriptions is distinct from old.subscriptions
                or new.scope is distinct from old.scope
                or new.connector_version is distinct from old.connector_version
                or new.definition_fingerprint is distinct from old.definition_fingerprint then
            raise exception 'integration guard: a connection that is on cannot be changed' using errcode = 'check_violation';
        end if;
        if new.deleted_at is not null then
            raise exception 'integration guard: a connection that is on cannot be removed' using errcode = 'check_violation';
        end if;
    end if;

    if not (
        (old.state = 'UNTESTED' and new.state in ('UNTESTED', 'TESTED'))
        or (old.state = 'TESTED' and new.state in ('TESTED', 'UNTESTED', 'ENABLED'))
        or (old.state = 'ENABLED' and new.state in ('ENABLED', 'DISABLED', 'FAILING'))
        or (old.state = 'FAILING' and new.state in ('FAILING', 'DISABLED', 'ENABLED'))
        or (old.state = 'DISABLED' and new.state in ('DISABLED', 'UNTESTED', 'ENABLED'))
    ) then
        raise exception 'integration guard: this move of % is not allowed', tg_table_name
            using errcode = 'check_violation';
    end if;

    -- Switched on, or tested, means: a successful test of exactly this definition exists.
    if new.state in ('TESTED', 'ENABLED') and (new.tested_fingerprint is null
            or new.tested_fingerprint is distinct from new.definition_fingerprint) then
        raise exception 'integration guard: no successful test of the current definition' using errcode = 'check_violation';
    end if;
    if new.state = 'FAILING' and new.tested_fingerprint is distinct from new.definition_fingerprint then
        raise exception 'integration guard: no successful test of the current definition' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_connection_row_guard before insert or update on integration_connection
    for each row execute function platform_row_guard();
create trigger integration_connection_tenant_guard before update on integration_connection
    for each row execute function platform_tenant_guard();
create trigger integration_connection_lifecycle_guard before insert or update on integration_connection
    for each row execute function platform_integration_connection_guard();

alter table integration_connection enable row level security;
alter table integration_connection force row level security;
create policy integration_connection_insert on integration_connection for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_connection_select on integration_connection for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_connection_update on integration_connection for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_connection_delete on integration_connection for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table integration_connection is
    'An organization''s connection to one external system: definition and state. Never holds a secret (only the name of one). Not metadata (ADR-0208).';
comment on column integration_connection.inbound_id is
    'The unguessable public identity a receiver finds the connection by (Sprint 28). Nothing reads it in Sprint 26.';
