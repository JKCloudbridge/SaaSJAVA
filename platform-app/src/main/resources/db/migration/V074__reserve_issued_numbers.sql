-- Issued numbers survive provisional identity changes and record removal (ADR-0198).
create table number_series_reservation (
    id uuid primary key default uuidv7(),
    tenant_id uuid not null default platform_current_tenant() references tenant (id),
    series_key text not null,
    object_api_name text not null,
    record_id uuid not null,
    number_value text not null,
    fixed boolean not null default false,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid not null,
    updated_at timestamptz not null default now(),
    updated_by uuid not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint number_reservation_series check (series_key ~ '^[a-z][a-z0-9-]{0,39}$'),
    constraint number_reservation_bound check (length(number_value) between 1 and 200),
    constraint number_reservation_kept check (deleted_at is null and deleted_by is null),
    unique (tenant_id, series_key, number_value)
);
create index number_reservation_record on number_series_reservation (tenant_id, series_key, record_id);
create trigger number_reservation_row_guard before insert or update on number_series_reservation
    for each row execute function platform_row_guard();
create trigger number_reservation_tenant_guard before update on number_series_reservation
    for each row execute function platform_tenant_guard();
create function platform_number_reservation_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Number reservations are kept';
    end if;
    if (new.series_key, new.object_api_name, new.record_id, new.number_value)
        is distinct from (old.series_key, old.object_api_name, old.record_id, old.number_value)
        or (old.fixed and not new.fixed) then
        raise exception 'Number reservations cannot be reassigned';
    end if;
    return new;
end;
$$;
create trigger number_reservation_kept before update or delete on number_series_reservation
    for each row execute function platform_number_reservation_guard();
alter table number_series_reservation enable row level security;
alter table number_series_reservation force row level security;
create policy number_reservation_select on number_series_reservation for select
    using (tenant_id = (select platform_current_tenant()));
create policy number_reservation_insert on number_series_reservation for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy number_reservation_update on number_series_reservation for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
