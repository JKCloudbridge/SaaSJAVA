-- The key of a reservation follows the convention of every other table: a partial unique index. Rows are never soft
-- deleted (a check constraint says so), so the answer is the same; the form is what the schema scan asks for (ADR-0010).
alter table number_series_reservation drop constraint number_series_reservation_tenant_id_series_key_number_value_key;
create unique index number_reservation_key on number_series_reservation (tenant_id, series_key, number_value)
    where deleted_at is null;
