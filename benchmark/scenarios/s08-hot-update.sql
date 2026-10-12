-- S8: many sessions update the same ten records, each with the version it read (read-modify-write of a counter).
-- Afterwards the harness checks that no update was lost: the counter and the version rose by the same amount.
\set i random(1, 10)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select version as v, (payload ->> 'Counter__c')::integer as c from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
\gset
update object_record set payload = jsonb_set(payload, '{Counter__c}', to_jsonb(:c + 1)),
                         version = :v + 1, updated_by = bench_tenant(0)
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i)
   and version = :v and deleted_at is null;
commit;
