-- Tabs, custom pages, the scripts of custom pages and the access tokens given to them (Sprint 18, ADR-0127, ADR-0128).
--
-- Four tables.
--
--   tab                 A reusable place an application can list in its navigation (published metadata, like
--                       application). Today one kind: CUSTOM, which shows a custom page. An object has a tab of its own
--                       without a row (the navigation item OBJECT), so only tabs somebody made are stored.
--   custom_page         A page whose content is a script the organization uploaded: a label, an API name, a reference
--                       to the script (an asset, below) and a reference to code that would run on the server. The
--                       second reference is kept so that no later change of shape is needed; the platform never reads
--                       it and never runs anything because of it (decision D6, ADR-0127).
--   custom_page_asset   One uploaded script, by content: the SHA-256 of its bytes and its size. The bytes are in the
--                       object store under a key made from the organization and the asset (never from a name a person
--                       typed). An asset never changes and is never removed by the application, so a release that
--                       names it can always be rolled back to.
--   embedded_token      The short-lived access token the page around a custom page hands to the script (ADR-0127). It
--                       is platform-level like oauth2_authorization, which it hangs on: it lives only as long as the
--                       sign-in grant it came from, can be no older than fifteen minutes, and is stored only as a hash.
--
-- What must hold even if the application had a bug:
--   * the identity of a tab or custom page (its API name) never changes;
--   * a script is identified by its content and nothing about an asset changes after it is written;
--   * an embedded token never lasts longer than fifteen minutes, belongs to one grant, one person and one organization,
--     and is stored as a hash.
--
-- The change set table of V033 learns the six new kinds of change. Widening a check is backward compatible; nothing is
-- removed.

-- ---------------------------------------------------------------------------------------------------------------
-- change sets learn the new kinds of change
-- ---------------------------------------------------------------------------------------------------------------

alter table metadata_change drop constraint metadata_change_kind_known;
alter table metadata_change add constraint metadata_change_kind_known check (kind in (
    'CREATE_OBJECT', 'UPDATE_OBJECT', 'DELETE_OBJECT', 'CREATE_FIELD', 'UPDATE_FIELD', 'DELETE_FIELD',
    'CREATE_RECORD_TYPE', 'UPDATE_RECORD_TYPE', 'DELETE_RECORD_TYPE',
    'CREATE_LAYOUT', 'UPDATE_LAYOUT', 'DELETE_LAYOUT',
    'CREATE_RECORD_PAGE', 'UPDATE_RECORD_PAGE', 'DELETE_RECORD_PAGE',
    'CREATE_APPLICATION', 'UPDATE_APPLICATION', 'DELETE_APPLICATION',
    'CREATE_LAYOUT_ASSIGNMENT', 'UPDATE_LAYOUT_ASSIGNMENT', 'DELETE_LAYOUT_ASSIGNMENT',
    'CREATE_VALIDATION_RULE', 'UPDATE_VALIDATION_RULE', 'DELETE_VALIDATION_RULE',
    'CREATE_RECORD_TYPE_ASSIGNMENT', 'UPDATE_RECORD_TYPE_ASSIGNMENT', 'DELETE_RECORD_TYPE_ASSIGNMENT',
    'CREATE_TAB', 'UPDATE_TAB', 'DELETE_TAB',
    'CREATE_CUSTOM_PAGE', 'UPDATE_CUSTOM_PAGE', 'DELETE_CUSTOM_PAGE'));

-- ---------------------------------------------------------------------------------------------------------------
-- tables
-- ---------------------------------------------------------------------------------------------------------------

create table tab (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint tab_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint tab_label_length check (char_length(label) between 1 and 80),
    constraint tab_description_length check (char_length(description) <= 500),
    constraint tab_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint tab_definition_size check (octet_length(definition::text) <= 20000)
);

create unique index tab_tenant_api_name on tab (tenant_id, lower(api_name)) where deleted_at is null;

create table custom_page (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint custom_page_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint custom_page_label_length check (char_length(label) between 1 and 80),
    constraint custom_page_description_length check (char_length(description) <= 500),
    constraint custom_page_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint custom_page_definition_size check (octet_length(definition::text) <= 20000)
);

create unique index custom_page_tenant_api_name on custom_page (tenant_id, lower(api_name)) where deleted_at is null;

create table custom_page_asset (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    sha256          text        not null,
    size_bytes      integer     not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint custom_page_asset_sha256_shape check (sha256 ~ '^[0-9a-f]{64}$'),
    -- The application's own limit is lower and can be changed by a setting; this one is the ceiling whatever it says.
    constraint custom_page_asset_size_range check (size_bytes between 1 and 5000000)
);

-- The same script uploaded twice is one asset.
create unique index custom_page_asset_tenant_sha256
    on custom_page_asset (tenant_id, sha256) where deleted_at is null;

create table embedded_token (
    id                uuid        primary key default uuidv7(),
    authorization_id  uuid        not null references oauth2_authorization (id) on delete cascade,
    user_id           uuid        not null references platform_user (id),
    bound_tenant_id   uuid        not null references tenant (id),
    token_hash        text        not null,
    custom_page       text,
    issued_at         timestamptz not null default now(),
    expires_at        timestamptz not null,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint embedded_token_hash_shape check (token_hash ~ '^sha256:[0-9a-f]{64}$'),
    -- The ceiling, whatever the application is set to: a token for a script is never older than fifteen minutes.
    constraint embedded_token_short_lived check (expires_at > issued_at and expires_at <= issued_at + interval '15 minutes'),
    constraint embedded_token_custom_page_shape
        check (custom_page is null or custom_page ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$')
);

create unique index embedded_token_hash_live on embedded_token (token_hash) where deleted_at is null;
create index embedded_token_authorization on embedded_token (authorization_id);
create index embedded_token_expires on embedded_token (expires_at);

-- ---------------------------------------------------------------------------------------------------------------
-- guards
-- ---------------------------------------------------------------------------------------------------------------

-- A script never changes: its identity is its content. (The row guard already keeps the version moving; this keeps the
-- content and the size from being rewritten under a name that others hold.)
create function platform_custom_page_asset_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.sha256 is distinct from old.sha256 or new.size_bytes is distinct from old.size_bytes then
        raise exception 'asset guard: an uploaded script never changes'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_custom_page_asset_guard() is
    'An uploaded script is identified by its content: its hash and size never change (ADR-0127).';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger tab_row_guard before insert or update on tab
    for each row execute function platform_row_guard();
create trigger tab_tenant_guard before update on tab
    for each row execute function platform_tenant_guard();
create trigger tab_identity_guard before insert or update on tab
    for each row execute function platform_definition_identity_guard();
create trigger tab_metadata_version after insert or update or delete on tab
    for each row execute function platform_metadata_version_bump();

create trigger custom_page_row_guard before insert or update on custom_page
    for each row execute function platform_row_guard();
create trigger custom_page_tenant_guard before update on custom_page
    for each row execute function platform_tenant_guard();
create trigger custom_page_identity_guard before insert or update on custom_page
    for each row execute function platform_definition_identity_guard();
create trigger custom_page_metadata_version after insert or update or delete on custom_page
    for each row execute function platform_metadata_version_bump();

create trigger custom_page_asset_row_guard before insert or update on custom_page_asset
    for each row execute function platform_row_guard();
create trigger custom_page_asset_tenant_guard before update on custom_page_asset
    for each row execute function platform_tenant_guard();
create trigger custom_page_asset_content_guard before update on custom_page_asset
    for each row execute function platform_custom_page_asset_guard();

create trigger embedded_token_row_guard before insert or update on embedded_token
    for each row execute function platform_row_guard();

alter table tab enable row level security;
alter table tab force row level security;
create policy tab_insert on tab for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy tab_select on tab for select
    using (tenant_id = (select platform_current_tenant()));
create policy tab_update on tab for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy tab_delete on tab for delete
    using (tenant_id = (select platform_current_tenant()));

alter table custom_page enable row level security;
alter table custom_page force row level security;
create policy custom_page_insert on custom_page for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy custom_page_select on custom_page for select
    using (tenant_id = (select platform_current_tenant()));
create policy custom_page_update on custom_page for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy custom_page_delete on custom_page for delete
    using (tenant_id = (select platform_current_tenant()));

alter table custom_page_asset enable row level security;
alter table custom_page_asset force row level security;
create policy custom_page_asset_insert on custom_page_asset for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy custom_page_asset_select on custom_page_asset for select
    using (tenant_id = (select platform_current_tenant()));
create policy custom_page_asset_update on custom_page_asset for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy custom_page_asset_delete on custom_page_asset for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table tab is
    'A reusable place an application lists in its navigation; one kind today (CUSTOM, shows a custom page). Published metadata, tenant-scoped (ADR-0015, ADR-0128).';
comment on table custom_page is
    'A page whose content is a script the organization uploaded, with a reference to code for the server that is kept and never read (D6). Published metadata, tenant-scoped (ADR-0015, ADR-0127).';
comment on table custom_page_asset is
    'One uploaded script, identified by its content (SHA-256 and size); the bytes are in the object store. Never changes, never removed by the application. Tenant-scoped (ADR-0015, ADR-0127).';
comment on table embedded_token is
    'The short-lived access token a custom page script uses, stored as a hash, bound to a sign-in grant, a person and an organization; never older than fifteen minutes. Platform-level like oauth2_authorization (ADR-0127).';
