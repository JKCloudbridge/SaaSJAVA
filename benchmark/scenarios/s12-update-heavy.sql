-- S12: update-heavy load on the large object. Half of the updates change an indexed field (the salary, in the payload
-- and in its typed copy), half a field that has no index (the notes). Every update raises the version by one, as the
-- row guard requires.
\set i random(1, :big_n)
\set r random(0, 1)
\set sal random(30000, 119999)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
update object_record
   set payload = case when :r = 0 then jsonb_set(payload, '{Salary__c}', to_jsonb((:sal)::integer))
                      else jsonb_set(payload, '{Notes__c}', to_jsonb(md5(random()::text) || repeat('x', 160))) end,
       s_num_1 = case when :r = 0 then (:sal)::double precision else s_num_1 end,
       version = version + 1, updated_by = bench_tenant(0)
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
commit;
