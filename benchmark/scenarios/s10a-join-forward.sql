-- S10: many-to-many, forward: the employees linked to one case, through the association table, one page of 50.
\set i random(1, :case_n)
\set seq :case_base + :i
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select r.id, r.version, r.payload
  from object_record_link l
  join object_record r on r.tenant_id = l.tenant_id and r.id = l.right_id and r.deleted_at is null
 where l.tenant_id = bench_tenant(1) and l.junction = 'CaseEmployee__c'
   and l.left_id = bench_id(:seq) and l.deleted_at is null
 order by r.id limit 50;
commit;
