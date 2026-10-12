-- S9: upsert a batch of 1,000 records by identifier: half of them exist (updated, version raised) and half are new.
-- The conflict update never touches a row of another tenant.
\set base random(1000000000, 2000000000)
\set off random(0, 1000000)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
insert into object_record as o (id, object_api_name, owner_id, payload, s_text_1, s_text_2, s_num_1, s_date_1, s_ref_1,
                                created_by, updated_by)
select case when g % 2 = 0 then bench_id(1 + (:off + g) % :big_n) else bench_id(500000000::bigint + :base + g) end,
       case when g % 2 = 0 then 'Employee__c' else 'Import__c' end,
       bench_tenant(0), r.payload, r.s_text_1, r.s_text_2, r.s_num_1, r.s_date_1, r.s_ref_1,
       bench_tenant(0), bench_tenant(0)
  from generate_series(1, 1000) g cross join lateral bench_row(:base + g) r
on conflict (id) do update
   set payload = excluded.payload, s_text_1 = excluded.s_text_1, s_text_2 = excluded.s_text_2,
       s_num_1 = excluded.s_num_1, s_date_1 = excluded.s_date_1, s_ref_1 = excluded.s_ref_1,
       version = o.version + 1, updated_by = excluded.updated_by
 where o.tenant_id = excluded.tenant_id;
commit;
