-- S9: insert a batch of 1,000 new records in one statement (the size of an import batch). The identifier is the
-- database default (uuidv7()). Measured inside the database: the client's own cost is not part of this number.
\set base random(1000000000, 2000000000)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
insert into object_record (object_api_name, owner_id, payload, s_text_1, s_text_2, s_num_1, s_date_1, s_ref_1,
                           created_by, updated_by)
select 'Import__c', bench_tenant(0), r.payload, r.s_text_1, r.s_text_2, r.s_num_1, r.s_date_1, r.s_ref_1,
       bench_tenant(0), bench_tenant(0)
  from generate_series(1, 1000) g cross join lateral bench_row(:base + g) r;
commit;
