-- S3 date: a three-day range on an indexed date field, sorted by that field then identifier.
\set d random(0, 3640)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and s_date_1 between date '2015-01-01' + (:d)::integer and date '2015-01-01' + (:d)::integer + 3
 order by s_date_1, id limit 50;
commit;
